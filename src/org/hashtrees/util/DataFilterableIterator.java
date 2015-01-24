package org.hashtrees.util;

import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Queue;

import javax.annotation.concurrent.NotThreadSafe;

import com.google.common.base.Function;

/**
 * LevelDB iterators can be seeked upto only the startkey. Endkey can not be
 * specified in leveldb iterator. Hence this class is used for that purpose.
 * This class accepts a prefixKey as argument
 * {@link #DataFilterableIterator(byte[], boolean, Function, Iterator)}. This
 * class uses the leveldb iterator, and when it sees the endkey, it stops
 * iterating after onwards.
 * 
 * @param <T>
 */
@NotThreadSafe
public class DataFilterableIterator<T> implements Iterator<T> {
	private final Queue<T> dataQueue = new ArrayDeque<>(1);
	private final byte[] prefixKey;
	private final boolean checkIfKeyHasThePrefix;
	private final Function<Map.Entry<byte[], byte[]>, T> converter;
	private final Iterator<Map.Entry<byte[], byte[]>> kvBytesItr;

	/**
	 * 
	 * @param prefixKey
	 * @param checkIfKeyHasThePrefix
	 *            , if true only returns keys that has the prefix value,
	 *            otherwise return all the keys that are less than or equivalent
	 *            to prefixKey.
	 * @param converter
	 * @param kvBytesItr
	 */
	public DataFilterableIterator(byte[] prefixKey,
			boolean checkIfKeyHasThePrefix,
			Function<Map.Entry<byte[], byte[]>, T> converter,
			Iterator<Map.Entry<byte[], byte[]>> kvBytesItr) {
		this.checkIfKeyHasThePrefix = checkIfKeyHasThePrefix;
		this.prefixKey = prefixKey;
		this.converter = converter;
		this.kvBytesItr = kvBytesItr;
	}

	@Override
	public boolean hasNext() {
		loadNextElement();
		return dataQueue.size() > 0;
	}

	@Override
	public T next() {
		if (!hasNext())
			throw new NoSuchElementException("No more elements exist.");
		return dataQueue.remove();
	}

	private void loadNextElement() {
		if (dataQueue.isEmpty() && kvBytesItr.hasNext()) {
			Map.Entry<byte[], byte[]> entry = kvBytesItr.next();
			int compResult = ByteUtils.compareTo(entry.getKey(), 0,
					prefixKey.length, prefixKey, 0, prefixKey.length);
			if ((checkIfKeyHasThePrefix && compResult != 0)
					|| (!checkIfKeyHasThePrefix && compResult > 0))
				return;
			dataQueue.add(converter.apply(entry));
		}
	}
}
