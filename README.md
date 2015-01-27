##merkle-tree

Merkle tree(Hash trees) is used in distributed systems(and many other places) to detect differences between two large datasets by using minimal network transfers. More information is avaiable in wikipedia. http://en.wikipedia.org/wiki/Merkle_tree. 

![Image of merkle tree] 
(http://upload.wikimedia.org/wikipedia/commons/9/95/Hash_Tree.svg)


###### Idea behind the merkle-tree is as following

1. Divide the data into blocks, and compute digest(hash) of each block. 
2. All hashes of these blocks become leaves of a tree. Using these leaves, we can build the complete tree upto the root.
3. If we have the merkle-trees of two similar datasets, that will help in detecting whether those datasets are same, or differing, by comparing only these digests, and we can quickly figure out which blocks are differing.

###### Merkle-tree assumptions

1) Network transfers are costlier than local computing. Hence digest calculation is done(even though it is a heavy process, but cpu capacity is higher than network transfer capacity), and digest is transferred through network, and that is used to detect inconsistencies, and hence avoiding unnecessary transfer entire block of data.

[Amazon Dynamo] (http://www.allthingsdistributed.com/files/amazon-dynamo-sosp2007.pdf) has used that in its implementation.
There are even few [opensource] (http://www.ccnx.org/releases/ccnx-0.6.0/javasrc/src/org/ccnx/ccn/impl/security/crypto/MerkleTree.java) implementations available. 

###### Problem

In one of my projects, we replicate some users' data acros multiple locations asynchronously. Replica nodes can differ from primary node's data for various reasons

1. Replica nodes can miss replication events from primary node due to operational error(like disabling replication, bringing up a node without bootstrapping the initial data), a node being down for a longer period of time, disk crash.
2. software inconsistency between primary and replica(hence data generated by primary might be differing)
3. not following write path properly (for example, in Hbase you can disable writes write ahead log entry during batch loading, hence those events wont be replicated at all)

So we needed a system where it can detect inconsistency between primary and replica with minimal overhead, and also synchronize the replica's data if needed. Even though there are many opensource implementations of merkle tree available, those are main memory based, does not retain anything between process restarts. So I have implemented a merkle-tree based system, and currently running in production. 

In the following design I will use the term HashTree instead of Merkle-tree. Both terms refer to same idea.

###### Design

1. HashTrees accepts inserts of (key,value) pairs, and deletions of previously inserted keys. It calculates digest of value and stores (key,digest) pairs. Since we store only digest, the space usage is low comparing to the actual storage. These pairs are hashed into buckets as shown in Merkle tree diagram. In current implementation, I am using LevelDB for hash tree storage. LevelDB is good at large insertion rate, and avoid unnecessary disk seeks. 

2. HashTrees calculates the digest of each block, and also the complete tree using the digests of these blocks. Building and maintinaing the complete tree on every insertion/deletion to HashTrees will be costly. Hence I have implemented dirty buckets holder (implemented [AtomicBitSet] (https://github.com/gomathi/merkle-tree/blob/master/src/org/hashtrees/util/AtomicBitSet.java)), which will be marked on every insertions/deletions. Using the dirty segments, HashTrees periodically build and update the tree, and not on every update. Also added some code to persist dirty buckets information to LevelDB. 

3. HashTrees need to be informed about insertions/deletions, so the actual storage need to forward the call when the changes are happening to the storage. In order to avoid any increased latency, I have used a non blocking queue, and insertions/deletions are added as entries, so storage won't get blocked at any case. Still if the process shuts down immediately, then we might loose the entries on the non blocking queue. There are few workarounds, either by listening to the changes on hashtrees by implementing [HashTreesObserver] (https://github.com/gomathi/merkle-tree/blob/master/src/org/hashtrees/HashTreesObserver.java) and replay the storage calls after the process restart from last commit entry, or ask HashTrees to persist the changes to LevelDB instead of using non blocking queue.

###### Usage

There are two main components, 

1. HashTrees (which manages queueing up insertions/deletions, rebuilding the tree, and synch another remote tree on request). I have provided an example class [HashTreesUsage.java] (https://github.com/gomathi/merkle-tree/blob/master/src/org/hashtrees/usage/HashTreesUsage.java) in using HashTrees.
2. HashTreesManager(which schedules rebuild/synch actions, and authorizes whether sync can happen from a tree to another tree). Read this class [HashTreesManagerUsage.java] (https://github.com/gomathi/merkle-tree/blob/master/src/org/hashtrees/usage/HashTreesManagerUsage.java) to understand how to use HashTreesManager.

###### Implementation

1. Most of the classes are implemented against an interface. So different implementations can be provided. For example, [HashTreesStore] (https://github.com/gomathi/merkle-tree/blob/master/src/org/hashtrees/store/HashTreesStore.java) defines the storage to be used by hashtrees. [HashTreesPersistentStore.java] (https://github.com/gomathi/merkle-tree/blob/master/src/org/hashtrees/store/HashTreesPersistentStore.java) is an LevelDB based storage implementation. You can plugin your own class, if you dont want to use LevelDB.
2. Also HashTrees and HashTreesManager provides observers, [HashTreesObserver.java] (https://github.com/gomathi/merkle-tree/blob/master/src/org/hashtrees/HashTreesObserver.java) and [HashTreesManagerObserver.java] (https://github.com/gomathi/merkle-tree/blob/master/src/org/hashtrees/synch/HashTreesManagerObserver.java). So you can plugin your own actions on certain events. 

###### Things to do
1. Expose metrics through JMX. [Netflix opensourced a library for that] (https://github.com/Netflix/servo/wiki). Seems like this is a good option.



