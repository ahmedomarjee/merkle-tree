package org.hashtrees;

import java.util.ArrayList;
import java.util.List;

public class TreeUtils {

	/**
	 * Finds the count of internal nodes, given the height of the tree.
	 * 
	 * @param h
	 *            , height of the tree.
	 * @param k
	 *            , no of children for each parent.
	 * @return
	 */
	public static int getNoOfNodes(int h, int k) {
		int result = (((int) Math.pow(k, h + 1)) - 1) / (k - 1);
		return result;
	}

	/**
	 * Calculates the height of the tree, given the no of leaves.
	 * 
	 * @param noOfLeaves
	 * @param k
	 *            , no of children
	 * @return
	 */
	public static int height(int noOfLeaves, int k) {
		int height = -1;
		while (noOfLeaves > 0) {
			noOfLeaves /= k;
			height++;
		}
		return height;
	}

	/**
	 * Returns the parent node id.
	 * 
	 * @param childId
	 * @param k
	 *            , no of children for each parent.
	 * @return
	 */
	public static int getParent(int childId, int k) {
		if (childId <= k)
			return 0;
		return (childId % k == 0) ? ((childId / k) - 1) : (childId / k);
	}

	/**
	 * Returns the ids of the children, which can be directly reached from
	 * parentId.
	 * 
	 * @param parentId
	 * @param k
	 *            , no of children for each parent
	 * @return
	 */
	public static List<Integer> getImmediateChildren(int parentId, int k) {
		List<Integer> children = new ArrayList<Integer>(2);
		for (int i = 1; i <= k; i++) {
			children.add((k * parentId) + i);
		}
		return children;
	}

	public static int getLevelOfNode(int nodeId, int k) {
		if (nodeId == 0)
			return 0;
		int level = 0;
		while (nodeId > 0) {
			nodeId = getParent(nodeId, k);
			level++;
		}
		return level;
	}

	public static int getLeftMostChildNode(int parentId, int k, int height) {
		int parentNodeLevel = getLevelOfNode(parentId, k);
		int noOfItr = height - parentNodeLevel;
		int leftMostChild = parentId;
		while (noOfItr > 0) {
			leftMostChild = k * leftMostChild + 1;
			noOfItr--;
		}
		return leftMostChild;
	}

	public static int getRightMostChildNode(int parentId, int k, int height) {
		int parentNodeLevel = getLevelOfNode(parentId, k);
		int noOfItr = height - parentNodeLevel;
		int rightMostChild = parentId;
		while (noOfItr > 0) {
			rightMostChild = k * rightMostChild + k;
			noOfItr--;
		}
		return rightMostChild;
	}
}
