package org.hashtrees.storage;

import java.nio.ByteBuffer;
import java.util.Iterator;

import org.hashtrees.HashTree;
import org.hashtrees.util.Pair;

/**
 * There could be cases where actual storage is missing (key,value) pairs, or
 * having keys which are not supposed to be there. In these cases, HashTree has
 * to directly talk to storage interface to do the necessary operations.
 * 
 * {@link HashTree} has to be provided with the implementation of this class.
 * 
 */
public interface Storage {

	ByteBuffer get(ByteBuffer key);

	void put(ByteBuffer key, ByteBuffer value) throws Exception;

	ByteBuffer remove(ByteBuffer key) throws Exception;

	Iterator<Pair<ByteBuffer, ByteBuffer>> iterator();
}
