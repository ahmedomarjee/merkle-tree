package org.hashtrees.manager;

import java.util.Iterator;
import java.util.concurrent.ConcurrentLinkedQueue;

import org.hashtrees.SyncDiffResult;
import org.hashtrees.thrift.generated.ServerName;

import com.google.common.base.Function;
import com.google.common.collect.Iterators;

/**
 * A helper class that is used by {@link HashTreesManager} to notify its
 * observers about certain events.
 * 
 */
public class HashTreesManagerObserverNotifier implements
		HashTreesManagerObserver {

	private final ConcurrentLinkedQueue<HashTreesManagerObserver> observers = new ConcurrentLinkedQueue<>();

	@Override
	public void preSync(final long treeId, final ServerName remoteServerName) {
		notifyObservers(new Function<HashTreesManagerObserver, Void>() {

			@Override
			public Void apply(HashTreesManagerObserver input) {
				input.preSync(treeId, remoteServerName);
				return null;
			}
		});
	}

	public void addObserver(HashTreesManagerObserver observer) {
		assert (observer != null);
		observers.add(observer);
	}

	public void removeObserver(HashTreesManagerObserver observer) {
		assert (observer != null);
		observers.remove(observer);
	}

	@Override
	public void postSync(final long treeId, final ServerName remoteServerName,
			final SyncDiffResult result) {
		notifyObservers(new Function<HashTreesManagerObserver, Void>() {

			@Override
			public Void apply(HashTreesManagerObserver input) {
				input.postSync(treeId, remoteServerName, result);
				return null;
			}
		});
	}

	private void notifyObservers(
			Function<HashTreesManagerObserver, Void> function) {
		Iterator<Void> itr = Iterators
				.transform(observers.iterator(), function);
		while (itr.hasNext())
			itr.next();
	}

}
