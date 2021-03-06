/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.hashtrees;

import static org.hashtrees.TreeUtils.getImmediateChildren;
import static org.hashtrees.TreeUtils.getLeftMostChildNode;
import static org.hashtrees.TreeUtils.getNoOfNodes;
import static org.hashtrees.TreeUtils.getParent;
import static org.hashtrees.TreeUtils.getRightMostChildNode;
import static org.hashtrees.TreeUtils.height;
import static org.hashtrees.util.ByteUtils.roundUpToPowerOf2;
import static org.hashtrees.util.ByteUtils.sha1;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.CountDownLatch;

import javax.annotation.concurrent.NotThreadSafe;
import javax.annotation.concurrent.ThreadSafe;

import org.apache.commons.codec.binary.Hex;
import org.hashtrees.store.HashTreesMemStore;
import org.hashtrees.store.HashTreesPersistentStore;
import org.hashtrees.store.HashTreesStore;
import org.hashtrees.store.Store;
import org.hashtrees.thrift.generated.KeyValue;
import org.hashtrees.thrift.generated.SegmentData;
import org.hashtrees.thrift.generated.SegmentHash;
import org.hashtrees.util.ByteUtils;
import org.hashtrees.util.LockedBy;
import org.hashtrees.util.NonBlockingQueuingTask;
import org.hashtrees.util.Pair;
import org.hashtrees.util.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.Iterators;
import com.google.common.collect.PeekingIterator;

/**
 * HashTrees has segment blocks and segment trees.
 * 
 * 1) Segment blocks, where the (key, hash of value) pairs are stored. All the
 * pairs are stored in sorted order. Whenever a key addition/removal happens on
 * the node, HashTree segment is updated. Keys are distributed using uniform
 * hash distribution.
 * 
 * 2) Segment trees, where the segments' hashes are updated and maintained. Tree
 * is not updated on every update on a segment. Rather, tree update is happening
 * at regular intervals. Binary tree is used currently. It should be easier to
 * extend to support higher no of children.
 * 
 */
@ThreadSafe
public class HashTreesImpl implements HashTrees, Service {

	/**
	 * Specifies how much element can be queued when hash tree is backed by a
	 * non blocking queue. When the queue is full, the new puts or removes are
	 * rejected.
	 */
	public final static int DEFAULT_NB_QUE_SIZE = Integer.MAX_VALUE;
	/**
	 * Specifies at most how many segments can be used by a single HashTree.
	 */
	public final static int MAX_NO_OF_SEGMENTS = 1 << 30;

	private final static Logger LOGGER = LoggerFactory
			.getLogger(HashTreesImpl.class.getName());
	private final static char COMMA_DELIMETER = ',';
	private final static char NEW_LINE_DELIMETER = '\n';
	private final static int ROOT_NODE = 0;
	private final static int BINARY_TREE = 2;

	private final int noOfChildren;
	private final int internalNodesCount;
	private final int segmentsCount;
	private final int height;

	private final Store store;
	private final HashTreesStore htStore;
	private final HashTreesIdProvider treeIdProvider;
	private final SegmentIdProvider segIdProvider;

	private final boolean enabledNonBlockingCalls;
	private final int nonBlockingQueueSize;
	private final Object nonBlockingCallsLock = new Object();
	@LockedBy("nonBlockingCallsLock")
	private volatile NonBlockingHTDataUpdater bgDataUpdater;
	private final HashTreesObserverNotifier notifier = new HashTreesObserverNotifier();
	private final LockProvider lockProvider;

	public HashTreesImpl(int noOfSegments, boolean enabledNonBlockingCalls,
			int nonBlockingQueueSize, final HashTreesIdProvider treeIdProvider,
			final SegmentIdProvider segIdProvider,
			final HashTreesStore htStore, final Store store,
			final LockProvider lockProvider) {
		this.noOfChildren = BINARY_TREE;
		this.segmentsCount = getValidSegmentsCount(noOfSegments);
		this.enabledNonBlockingCalls = enabledNonBlockingCalls;
		this.nonBlockingQueueSize = nonBlockingQueueSize;
		this.height = height(this.segmentsCount, noOfChildren);
		this.internalNodesCount = getNoOfNodes((height - 1), noOfChildren);
		this.treeIdProvider = treeIdProvider;
		this.segIdProvider = segIdProvider;
		this.htStore = htStore;
		this.store = store;
		this.lockProvider = lockProvider;
	}

	@Override
	public void hPut(final ByteBuffer key, final ByteBuffer value)
			throws IOException {
		hPutInternal(HTOperation.PUT, key, value);
	}

	private void hPutInternal(HTOperation operation, final ByteBuffer key,
			final ByteBuffer value) throws IOException {
		if (enabledNonBlockingCalls) {
			List<ByteBuffer> input = new ArrayList<ByteBuffer>();
			input.add(key);
			input.add(value);
			bgDataUpdater.enque(Pair.create(operation, input));
		} else
			hPutInternal(key, value);
	}

	private void hPutInternal(final ByteBuffer key, final ByteBuffer value)
			throws IOException {
		notifier.preHPut(key, value);
		long treeId = treeIdProvider.getTreeId(key.array());
		int segId = segIdProvider.getSegmentId(key.array());
		ByteBuffer digest = ByteBuffer.wrap(sha1(value.array()));
		htStore.setDirtySegment(treeId, segId);
		htStore.putSegmentData(treeId, segId, key, digest);
		notifier.postHPut(key, value);
	}

	@Override
	public void hRemove(final ByteBuffer key) throws IOException {
		hRemoveInternal(HTOperation.REMOVE, key);
	}

	private void hRemoveInternal(HTOperation operation, final ByteBuffer key)
			throws IOException {
		if (enabledNonBlockingCalls) {
			List<ByteBuffer> input = new ArrayList<ByteBuffer>();
			input.add(key);
			bgDataUpdater.enque(Pair.create(operation, input));
		} else {
			hRemoveInternal(key);
		}
	}

	private void hRemoveInternal(final ByteBuffer key) throws IOException {
		notifier.preHRemove(key);
		long treeId = treeIdProvider.getTreeId(key.array());
		int segId = segIdProvider.getSegmentId(key.array());
		htStore.setDirtySegment(treeId, segId);
		htStore.deleteSegmentData(treeId, segId, key);
		notifier.postHRemove(key);
	}

	@Override
	public SyncDiffResult synch(long treeId, final HashTrees remoteTree)
			throws IOException {
		return synch(treeId, remoteTree, SyncType.UPDATE);
	}

	@Override
	public SyncDiffResult synch(long treeId, final HashTrees remoteTree,
			SyncType syncType) throws IOException {
		if (lockProvider.acquireLock(treeId)) {
			try {
				boolean doUpdate = (syncType == SyncType.UPDATE) ? true : false;

				PeekingIterator<SegmentHash> localItr = null, remoteItr = null;
				SegmentHash local, remote;

				List<Integer> pQueue = new ArrayList<Integer>();
				pQueue.add(ROOT_NODE);

				int totKeyDifferences = 0, totExtrinsicSegments = 0;

				while (!pQueue.isEmpty()) {

					localItr = Iterators.peekingIterator(getSegmentHashes(
							treeId, pQueue).iterator());
					remoteItr = Iterators.peekingIterator(remoteTree
							.getSegmentHashes(treeId, pQueue).iterator());
					pQueue = new ArrayList<Integer>();

					while (localItr.hasNext() || remoteItr.hasNext()) {
						local = localItr.hasNext() ? localItr.peek() : null;
						remote = remoteItr.hasNext() ? remoteItr.peek() : null;

						int compareRes = compareSegNodeIds(local, remote);

						if (compareRes == 0) {
							if (!Arrays.equals(local.getHash(),
									remote.getHash())) {
								if (isLeafNode(local.getNodeId())) {
									totKeyDifferences += syncSegment(treeId,
											getSegmentIdFromLeafId(local
													.getNodeId()), remoteTree,
											doUpdate);
								} else
									pQueue.addAll(getImmediateChildren(
											local.getNodeId(), noOfChildren));

							}
							localItr.next();
							remoteItr.next();
						} else if (compareRes < 0) {
							totKeyDifferences += updateRemoteTreeWithMissingSegment(
									treeId, local.getNodeId(), remoteTree,
									doUpdate);
							localItr.next();
						} else {
							if (doUpdate)
								remoteTree.deleteTreeNode(treeId,
										remote.getNodeId());
							remoteItr.next();
							totExtrinsicSegments += 1;
						}
					}
				}
				return new SyncDiffResult(totKeyDifferences,
						totExtrinsicSegments);
			} finally {
				lockProvider.releaseLock(treeId);
			}
		}
		return new SyncDiffResult(0, 0);
	}

	private int syncSegment(long treeId, int segId, HashTrees remoteTree,
			boolean doUpdate) throws IOException {
		PeekingIterator<SegmentData> localDataItr = Iterators
				.peekingIterator(getSegment(treeId, segId).iterator());
		PeekingIterator<SegmentData> remoteDataItr = Iterators
				.peekingIterator(remoteTree.getSegment(treeId, segId)
						.iterator());

		List<KeyValue> kvsForAddition = new ArrayList<KeyValue>();
		List<ByteBuffer> keysForRemoval = new ArrayList<ByteBuffer>();

		SegmentData local, remote;
		while (localDataItr.hasNext() || remoteDataItr.hasNext()) {
			local = localDataItr.hasNext() ? localDataItr.peek() : null;
			remote = remoteDataItr.hasNext() ? remoteDataItr.peek() : null;

			int compRes = compareSegmentKeys(local, remote);
			if (compRes == 0) {
				if (!Arrays.equals(local.getDigest(), remote.getDigest())) {
					ByteBuffer key = ByteBuffer.wrap(local.getKey());
					byte[] value = store.get(local.getKey());
					if (value != null)
						kvsForAddition.add(new KeyValue(key, ByteBuffer
								.wrap(value)));
				}
				localDataItr.next();
				remoteDataItr.next();
			} else if (compRes < 0) {
				ByteBuffer key = ByteBuffer.wrap(local.getKey());
				byte[] value = store.get(local.getKey());
				if (value != null)
					kvsForAddition
							.add(new KeyValue(key, ByteBuffer.wrap(value)));
				localDataItr.next();
			} else {
				keysForRemoval.add(ByteBuffer.wrap(remote.getKey()));
				remoteDataItr.next();
			}
		}

		if (doUpdate) {
			if (kvsForAddition.size() > 0)
				remoteTree.sPut(kvsForAddition);
			if (keysForRemoval.size() > 0)
				remoteTree.sRemove(keysForRemoval);
		}

		return kvsForAddition.size() + keysForRemoval.size();
	}

	private int updateRemoteTreeWithMissingSegment(long treeId,
			int rootMissingNodeId, HashTrees remoteTree, boolean doUpdate)
			throws IOException {
		final List<KeyValue> keyValuePairs = new ArrayList<>();
		int maxSizeToTransfer = 5000;
		int totKeyUpdates = 0;
		int leftMostNodeId = getSegmentIdFromLeafId(getLeftMostChildNode(
				rootMissingNodeId, noOfChildren, height));
		int rightMostNodeId = getSegmentIdFromLeafId(getRightMostChildNode(
				rootMissingNodeId, noOfChildren, height));
		Iterator<SegmentData> sdItr = htStore.getSegmentDataIterator(treeId,
				leftMostNodeId, rightMostNodeId);
		while (sdItr.hasNext()) {
			SegmentData sd = sdItr.next();
			byte[] value = store.get(sd.getKey());
			if (value != null)
				keyValuePairs.add(new KeyValue(ByteBuffer.wrap(sd.getKey()),
						ByteBuffer.wrap(value)));
			if (keyValuePairs.size() > maxSizeToTransfer) {
				if (doUpdate)
					remoteTree.sPut(keyValuePairs);
				totKeyUpdates += keyValuePairs.size();
				keyValuePairs.clear();
			}
		}
		if (keyValuePairs.size() > 0) {
			if (doUpdate)
				remoteTree.sPut(keyValuePairs);
			totKeyUpdates += keyValuePairs.size();
			keyValuePairs.clear();
		}
		return totKeyUpdates;
	}

	@Override
	public SegmentHash getSegmentHash(long treeId, int nodeId)
			throws IOException {
		return htStore.getSegmentHash(treeId, nodeId);
	}

	@Override
	public List<SegmentHash> getSegmentHashes(long treeId,
			final List<Integer> nodeIds) throws IOException {
		return htStore.getSegmentHashes(treeId, nodeIds);
	}

	@Override
	public SegmentData getSegmentData(long treeId, int segId, ByteBuffer key)
			throws IOException {
		return htStore.getSegmentData(treeId, segId, key);
	}

	@Override
	public List<SegmentData> getSegment(long treeId, int segId)
			throws IOException {
		return htStore.getSegment(treeId, segId);
	}

	@Override
	public int rebuildHashTree(long treeId, long fullRebuildPeriod)
			throws IOException {
		long lastFullRebuiltTime = htStore.getCompleteRebuiltTimestamp(treeId);
		boolean fullRebuild = (lastFullRebuiltTime == 0) ? true
				: ((fullRebuildPeriod < 0) ? false
						: (System.currentTimeMillis() - lastFullRebuiltTime) > fullRebuildPeriod);
		return rebuildHashTree(treeId, fullRebuild);
	}

	@Override
	public int rebuildHashTree(long treeId, boolean fullRebuild)
			throws IOException {
		List<Integer> dirtySegments = null;
		if (lockProvider.acquireLock(treeId)) {
			try {
				notifier.preRebuild(treeId, fullRebuild);
				long buildBeginTS = System.currentTimeMillis();
				if (fullRebuild)
					rebuildCompleteTree(treeId);
				dirtySegments = htStore.getDirtySegments(treeId);
				htStore.markSegments(treeId, dirtySegments);
				List<Integer> dirtyNodes = rebuildLeaves(treeId, dirtySegments);
				rebuildInternalNodes(treeId, dirtyNodes);
				htStore.unmarkSegments(treeId, dirtySegments);
				if (fullRebuild)
					htStore.setCompleteRebuiltTimestamp(treeId, buildBeginTS);
				notifier.postRebuild(treeId, fullRebuild);
				return dirtySegments.size();
			} catch (HashTreesCustomRuntimeException e) {
				if (dirtySegments != null)
					htStore.markSegments(treeId, dirtySegments);
			} finally {
				lockProvider.releaseLock(treeId);
			}
		}
		return 0;
	}

	/**
	 * This reads all the entries from the {@link Store}, and updates
	 * {@link HashTreesStore} with those (key,value) pairs. Also reads all the
	 * existing entries from {@link HashTreesStore} and if they don't exist on
	 * the {@link Store}, removes from {@link HashTreesStore}.
	 * 
	 * @param treeId
	 * @throws IOException
	 */
	private void rebuildCompleteTree(long treeId) throws IOException {
		Iterator<Map.Entry<byte[], byte[]>> itr = store.iterator(treeId);
		while (itr.hasNext()) {
			Map.Entry<byte[], byte[]> pair = itr.next();
			hPutInternal(HTOperation.PUT_IF_ABSENT,
					ByteBuffer.wrap(pair.getKey()),
					ByteBuffer.wrap(pair.getValue()));
		}
		Iterator<SegmentData> segDataItr = htStore
				.getSegmentDataIterator(treeId);
		while (segDataItr.hasNext()) {
			SegmentData sd = segDataItr.next();
			if (!store.contains(sd.getKey())) {
				hRemoveInternal(HTOperation.REMOVE_IF_ABSENT, sd.key);
			}
		}
	}

	/**
	 * Rebuilds the dirty segments, and updates the segment hashes of the
	 * leaves.
	 * 
	 * @param treeId
	 * @param dirtySegments
	 * @return corresponding nodeIds of the segments.
	 * @throws IOException
	 */
	private List<Integer> rebuildLeaves(long treeId,
			final List<Integer> dirtySegments) throws IOException {
		List<Integer> nodeIds = new ArrayList<>();
		for (int dirtySegId : dirtySegments) {
			if (htStore.clearDirtySegment(treeId, dirtySegId)) {
				ByteBuffer digest = digestSegmentData(treeId, dirtySegId);
				int nodeId = getLeafIdFromSegmentId(dirtySegId);
				htStore.putSegmentHash(treeId, nodeId, digest);
				nodeIds.add(nodeId);
			}
		}
		return nodeIds;
	}

	private ByteBuffer digestSegmentData(long treeId, int segId)
			throws IOException {
		List<SegmentData> dirtySegmentData = htStore.getSegment(treeId, segId);
		List<String> hexStrings = new ArrayList<String>();
		for (SegmentData sd : dirtySegmentData)
			hexStrings.add(getHexString(sd.key, sd.digest));
		return digestHexStrings(hexStrings);
	}

	/**
	 * Updates the segment hashes iteratively for each level on the tree.
	 * 
	 * @param dirtyNodeIds
	 * @throws IOException
	 */
	private void rebuildInternalNodes(long treeId,
			final List<Integer> dirtyNodeIds) throws IOException {
		Set<Integer> parentNodeIds = new TreeSet<Integer>();
		Set<Integer> nodeIds = new TreeSet<Integer>(dirtyNodeIds);
		while (!nodeIds.isEmpty()) {
			for (int nodeId : nodeIds)
				parentNodeIds.add(getParent(nodeId, noOfChildren));
			rebuildParentNodes(treeId, parentNodeIds);
			nodeIds.clear();
			nodeIds.addAll(parentNodeIds);
			parentNodeIds.clear();
			if (nodeIds.contains(ROOT_NODE))
				break;
		}
	}

	/**
	 * For each parent id, gets all the child hashes, and updates the parent
	 * hash.
	 * 
	 * @param parentIds
	 * @throws IOException
	 */
	private void rebuildParentNodes(long treeId, final Set<Integer> parentIds)
			throws IOException {
		List<Integer> children;
		List<ByteBuffer> segHashes = new ArrayList<>(noOfChildren);
		ByteBuffer segHashBB;
		SegmentHash segHash;

		for (int parentId : parentIds) {
			children = getImmediateChildren(parentId, noOfChildren);
			for (int child : children) {
				segHash = htStore.getSegmentHash(treeId, child);
				segHashBB = (segHash == null) ? null : segHash.hash.duplicate();
				if (segHashBB != null)
					segHashes.add(segHashBB);
			}
			ByteBuffer digest = digestByteBuffers(segHashes);
			htStore.putSegmentHash(treeId, parentId, digest);
			segHashes.clear();
		}
	}

	@Override
	public void sPut(final List<KeyValue> keyValuePairs) throws IOException {
		notifier.preSPut(keyValuePairs);
		for (KeyValue keyValuePair : keyValuePairs)
			store.put(keyValuePair.getKey(), keyValuePair.getValue());
		notifier.postSPut(keyValuePairs);
	}

	@Override
	public void sRemove(final List<ByteBuffer> keys) throws IOException {
		notifier.preSRemove(keys);
		for (ByteBuffer key : keys)
			store.delete(key.array());
		notifier.postSRemove(keys);
	}

	@Override
	public void deleteTreeNode(long treeId, int nodeId) throws IOException {
		int leftMostNodeId = getSegmentIdFromLeafId(getLeftMostChildNode(
				nodeId, noOfChildren, height));
		int rightMostNodeId = getSegmentIdFromLeafId(getRightMostChildNode(
				nodeId, noOfChildren, height));
		Iterator<SegmentData> sdItr = htStore.getSegmentDataIterator(treeId,
				leftMostNodeId, rightMostNodeId);
		while (sdItr.hasNext()) {
			SegmentData sd = sdItr.next();
			store.delete(sd.getKey());
		}
	}

	// Should be always called before using this instance.
	@Override
	public void start() {
		enableNonBlockingOperations();
	}

	private void enableNonBlockingOperations() {
		synchronized (nonBlockingCallsLock) {
			if (enabledNonBlockingCalls) {
				if (bgDataUpdater == null)
					bgDataUpdater = new NonBlockingHTDataUpdater(this,
							nonBlockingQueueSize);
				new Thread(bgDataUpdater).start();
				LOGGER.info("Non blocking calls are enabled.");
			}
		}
	}

	private void disableNonblockingOperations() {
		synchronized (nonBlockingCallsLock) {
			if (enabledNonBlockingCalls && bgDataUpdater != null) {
				CountDownLatch countDownLatch = new CountDownLatch(1);
				bgDataUpdater.stopAsync(countDownLatch);
				try {
					countDownLatch.await();
				} catch (InterruptedException e) {
					LOGGER.warn(
							"Exception occurred while waiting data updater to stop",
							e);
				}
				bgDataUpdater = null;
				LOGGER.info("Non blocking calls are disabled.");
			}
		}
	}

	@Override
	public void stop() {
		disableNonblockingOperations();
	}

	@Override
	public void addObserver(HashTreesObserver observer) {
		notifier.addObserver(observer);
	}

	@Override
	public void removeObserver(HashTreesObserver observer) {
		notifier.removeObserver(observer);
	}

	private static int compareSegNodeIds(SegmentHash left, SegmentHash right) {
		if (left == null && right == null)
			return 0;
		if (left == null)
			return 1;
		if (right == null)
			return -1;
		return left.getNodeId() - right.getNodeId();
	}

	private static int compareSegmentKeys(SegmentData left, SegmentData right) {
		if (left == null && right == null)
			return 0;
		if (left == null)
			return 1;
		if (right == null)
			return -1;
		return ByteUtils.compareTo(left.getKey(), right.getKey());
	}

	private static int getValidSegmentsCount(int noOfSegments) {
		return ((noOfSegments > MAX_NO_OF_SEGMENTS) || (noOfSegments < 0)) ? MAX_NO_OF_SEGMENTS
				: roundUpToPowerOf2(noOfSegments);
	}

	/**
	 * Concatenates the given ByteBuffer values by first converting them to the
	 * equivalent hex strings, and then concatenates by adding the comma
	 * delimiter.
	 * 
	 * @param values
	 * @return
	 */
	public static String getHexString(ByteBuffer... values) {
		StringBuffer sb = new StringBuffer();
		for (int i = 0; i < values.length - 1; i++)
			sb.append(Hex.encodeHexString(values[i].array()) + COMMA_DELIMETER);
		if (values.length > 0)
			sb.append(Hex.encodeHexString(values[values.length - 1].array()));
		return sb.toString();
	}

	public static ByteBuffer digestByteBuffers(List<ByteBuffer> bbList) {
		List<String> hexStrings = new ArrayList<String>();
		for (ByteBuffer bb : bbList)
			hexStrings.add(Hex.encodeHexString(bb.array()));
		return digestHexStrings(hexStrings);
	}

	public static ByteBuffer digestHexStrings(List<String> hexStrings) {
		StringBuilder sb = new StringBuilder();
		for (String hexString : hexStrings)
			sb.append(hexString + NEW_LINE_DELIMETER);
		return ByteBuffer.wrap(sha1(sb.toString().getBytes()));
	}

	/**
	 * Segment block id starts with 0. Each leaf node corresponds to a segment
	 * block. This function does the mapping from segment block id to leaf node
	 * id.
	 * 
	 * @param segId
	 * @return
	 */
	private int getLeafIdFromSegmentId(int segId) {
		return internalNodesCount + segId;
	}

	private int getSegmentIdFromLeafId(int leafNodeId) {
		return leafNodeId - internalNodesCount;
	}

	private boolean isLeafNode(int nodeId) {
		return nodeId >= internalNodesCount;
	}

	/**
	 * Used to tag type of operation when the input is fed into the non blocking
	 * version of {@link HashTreesImpl} hPut and hRemove methods.
	 * 
	 */
	private static enum HTOperation {
		PUT, REMOVE, PUT_IF_ABSENT, REMOVE_IF_ABSENT
	}

	/**
	 * A task to enable non blocking calls on all
	 * {@link HashTreesImpl#hPut(ByteArray, ByteArray)} and
	 * {@link HashTreesImpl#hRemove(ByteArray)} operation.
	 * 
	 */
	@ThreadSafe
	private static class NonBlockingHTDataUpdater extends
			NonBlockingQueuingTask<Pair<HTOperation, List<ByteBuffer>>> {

		private static final Pair<HTOperation, List<ByteBuffer>> STOP_MARKER = new Pair<HTOperation, List<ByteBuffer>>(
				HTOperation.PUT, null);
		private final ConcurrentSkipListSet<ByteBuffer> keysOnQueue = new ConcurrentSkipListSet<>();
		private final HashTreesImpl hTree;

		public NonBlockingHTDataUpdater(final HashTreesImpl hTree,
				int maxElementsToQue) {
			super(STOP_MARKER, maxElementsToQue);
			this.hTree = hTree;
		}

		@Override
		public void enque(Pair<HTOperation, List<ByteBuffer>> pair) {
			if (pair != STOP_MARKER) {
				ByteBuffer key = pair.getSecond().get(0);
				boolean isAbsent = keysOnQueue.add(key);
				switch (pair.getFirst()) {
				case PUT:
				case REMOVE:
					super.enque(pair);
					break;
				case PUT_IF_ABSENT:
				case REMOVE_IF_ABSENT:
					if (isAbsent)
						super.enque(pair);
					break;
				}
			}
		}

		@Override
		public void handleElement(Pair<HTOperation, List<ByteBuffer>> pair) {
			ByteBuffer key = pair.getSecond().get(0);
			try {
				switch (pair.getFirst()) {
				case PUT:
				case PUT_IF_ABSENT:
					hTree.hPutInternal(key, pair.getSecond().get(1));
					break;
				case REMOVE:
				case REMOVE_IF_ABSENT:
					hTree.hRemoveInternal(key);
					break;
				}
			} catch (IOException e) {
				throw new RuntimeException(e);
			} finally {
				keysOnQueue.remove(key);
			}
		}

	}

	@NotThreadSafe
	public static class Builder {

		private final Store store;
		private final HashTreesStore htStore;
		private final HashTreesIdProvider treeIdProvider;

		private SegmentIdProvider segIdProvider;
		private int noOfSegments = 1 << 17,
				nonBlockingQueueSize = DEFAULT_NB_QUE_SIZE;
		private boolean enabledNonBlockingCalls = true;
		private LockProvider lockProvider;

		public Builder(Store store, HashTreesIdProvider treeIdProvider,
				HashTreesStore htStore) {
			this.store = store;
			this.htStore = htStore;
			this.treeIdProvider = treeIdProvider;
		}

		/**
		 * Creates a in memory based hashtrees storage, and stores the digests
		 * over there. Look at {@link HashTreesMemStore} for more information.
		 * 
		 * @param store
		 * @param treeIdProvider
		 */
		public Builder(Store store, HashTreesIdProvider treeIdProvider) {
			this(store, treeIdProvider, new HashTreesMemStore());
		}

		/**
		 * Creates a LevelDB based store for storing hash tree contents. LevelDB
		 * files are stored in htStoreDirName. Look at
		 * {@link HashTreesPersistentStore}.
		 * 
		 * @param store
		 * @param treeIdProvider
		 * @param htStoreDirName
		 * @throws IOException
		 */
		public Builder(Store store, HashTreesIdProvider treeIdProvider,
				String htStoreDirName) throws IOException {
			this(store, treeIdProvider, new HashTreesPersistentStore(
					htStoreDirName));
		}

		/**
		 * By default {@link ModuloSegIdProvider} is used.
		 * 
		 * @param segIdProvider
		 * @return
		 */
		public Builder setSegmentIdProvider(SegmentIdProvider segIdProvider) {
			this.segIdProvider = segIdProvider;
			return this;
		}

		/**
		 * Depends upon data size, this should be set. Data size and
		 * noOfSegments should be directly proportional. With higher dataSize,
		 * and lesser noOfSegments means each segment will get more amount of
		 * data. When a segment is marked as dirty, the rebuild process has to
		 * read huge data unnecessarily.
		 * 
		 * Default value is 1 << 17. (More than 1 million).
		 * 
		 * @param noOfSegments
		 *            , value should be a power of 2, otherwise it will be
		 *            converted to the equivalent.
		 * @return
		 */
		public Builder setNoOfSegments(int noOfSegments) {
			this.noOfSegments = getValidSegmentsCount(noOfSegments);
			return this;
		}

		/**
		 * Enable/Disable non blocking calls. By default non blocking calls are
		 * enabled.
		 * 
		 * @param enabledNonBlockingCalls
		 *            , true enables non blocking calls, false disables non
		 *            blocking calls.
		 * @return
		 */
		public Builder setEnabledNonBlockingCalls(
				boolean enabledNonBlockingCalls) {
			this.enabledNonBlockingCalls = enabledNonBlockingCalls;
			return this;
		}

		/**
		 * Sets queue size about how many elements can be queued in memory.
		 * Default value is {@link Integer#MAX_VALUE}.
		 * 
		 * @param nonBlockingQueueSize
		 * @return
		 */
		public Builder setNonBlockingQueueSize(int nonBlockingQueueSize) {
			assert (nonBlockingQueueSize > 0);
			this.nonBlockingQueueSize = nonBlockingQueueSize;
			return this;
		}

		/**
		 * Sets a lock provider to use. By default this uses
		 * {@link HTReentrantLockProvider}.
		 * 
		 * @param lockProvider
		 * @return
		 */
		public Builder setLockProvider(LockProvider lockProvider) {
			assert (lockProvider != null);
			this.lockProvider = lockProvider;
			return this;
		}

		public HashTreesImpl build() {
			if (segIdProvider == null)
				segIdProvider = new ModuloSegIdProvider(noOfSegments);
			if (lockProvider == null)
				lockProvider = new HTReentrantLockProvider();
			return new HashTreesImpl(noOfSegments, enabledNonBlockingCalls,
					nonBlockingQueueSize, treeIdProvider, segIdProvider,
					htStore, store, lockProvider);
		}
	}
}
