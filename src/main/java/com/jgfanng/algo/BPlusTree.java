package com.jgfanng.algo;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;

/**
 * Generic B+Tree implementation — Task 3 (Index) 答辩核心。
 *
 * <h3>B+Tree vs B-Tree（答辩必问）</h3>
 * <ul>
 *   <li>数据全在叶子层：内部节点只存 key（路由用），不存 value
 *       → 内部节点出度更大 → 树更矮 → 磁盘 I/O 更少</li>
 *   <li>叶子节点有 next 指针形成有序链表
 *       → 范围查询 O(log n + k)，顺序扫描叶子链表即可，不需要回溯上层</li>
 * </ul>
 *
 * <h3>节点结构</h3>
 * <pre>
 * BPlusTree<K, V>
 * ├── Node (abstract)         → List<K> keys
 * │   ├── InternalNode        → keys + List<Node> children
 * │   │   keys[i] = children[i+1] 子树的最小键
 * │   └── LeafNode            → keys + List<V> values + LeafNode next（链表指针）
 * </pre>
 *
 * <h3>分裂点差异（答辩重点）</h3>
 * InternalNode split: from = keyNumber() / 2 + 1（偏右）
 *   — 中间键上移到父节点，子节点不再保留
 * LeafNode split:     from = (keyNumber() + 1) / 2（偏左，向上取半）
 *   — 中间键复制到父节点，叶子保留一份，保证两边数据均衡
 *
 * <h3>容量约束</h3>
 * InternalNode 溢出: children.size() > branchingFactor
 * InternalNode 下溢: children.size() < (branchingFactor + 1) / 2
 * LeafNode 溢出:     values.size() > branchingFactor - 1
 * LeafNode 下溢:     values.size() < branchingFactor / 2
 * 注：LeafNode 容量少 1 是因为分裂时中间键会被复制到父节点，少一个槽位保证不会溢出。
 *
 * @param <K> key 类型，必须实现 Comparable
 * @param <V> value 类型（无约束）
 */
public class BPlusTree<K extends Comparable<? super K>, V> {

	public static enum RangePolicy {
		EXCLUSIVE, INCLUSIVE
	}

	/**
	 * The branching factor used when none specified in constructor.
	 */
	private static final int DEFAULT_BRANCHING_FACTOR = 128;

	/**
	 * The branching factor for the B+ tree, that measures the capacity of nodes
	 * (i.e., the number of children nodes) for internal nodes in the tree.
	 */
	private int branchingFactor;

	/**
	 * The root node of the B+ tree.
	 */
	private Node root;

	public BPlusTree() {
		this(DEFAULT_BRANCHING_FACTOR);
	}

	public BPlusTree(int branchingFactor) {
		if (branchingFactor <= 2)
			throw new IllegalArgumentException("Illegal branching factor: "
					+ branchingFactor);
		this.branchingFactor = branchingFactor;
		root = new LeafNode();
	}

	/**
	 * Returns the value to which the specified key is associated, or
	 * {@code null} if this tree contains no association for the key.
	 *
	 * <p>
	 * A return value of {@code null} does not <i>necessarily</i> indicate that
	 * the tree contains no association for the key; it's also possible that the
	 * tree explicitly associates the key to {@code null}.
	 * 
	 * @param key
	 *            the key whose associated value is to be returned
	 * 
	 * @return the value to which the specified key is associated, or
	 *         {@code null} if this tree contains no association for the key
	 */
	public V search(K key) {
		return root.getValue(key);
	}

	/**
	 * Returns the values associated with the keys specified by the range:
	 * {@code key1} and {@code key2}.
	 * 
	 * @param key1
	 *            the start key of the range
	 * @param policy1
	 *            the range policy, {@link RangePolicy#EXCLUSIVE} or
	 *            {@link RangePolicy#INCLUSIVE}
	 * @param key2
	 *            the end end of the range
	 * @param policy2
	 *            the range policy, {@link RangePolicy#EXCLUSIVE} or
	 *            {@link RangePolicy#INCLUSIVE}
	 * @return the values associated with the keys specified by the range:
	 *         {@code key1} and {@code key2}
	 */
	public List<V> searchRange(K key1, RangePolicy policy1, K key2,
			RangePolicy policy2) {
		return root.getRange(key1, policy1, key2, policy2);
	}

	/**
	 * Associates the specified value with the specified key in this tree. If
	 * the tree previously contained a association for the key, the old value is
	 * replaced.
	 * 
	 * @param key
	 *            the key with which the specified value is to be associated
	 * @param value
	 *            the value to be associated with the specified key
	 */
	public void insert(K key, V value) {
		root.insertValue(key, value);
	}

	/**
	 * Removes the association for the specified key from this tree if present.
	 * 
	 * @param key
	 *            the key whose association is to be removed from the tree
	 */
	public void delete(K key) {
		root.deleteValue(key);
	}

	public String toString() {
		Queue<List<Node>> queue = new LinkedList<List<Node>>();
		queue.add(Arrays.asList(root));
		StringBuilder sb = new StringBuilder();
		while (!queue.isEmpty()) {
			Queue<List<Node>> nextQueue = new LinkedList<List<Node>>();
			while (!queue.isEmpty()) {
				List<Node> nodes = queue.remove();
				sb.append('{');
				Iterator<Node> it = nodes.iterator();
				while (it.hasNext()) {
					Node node = it.next();
					sb.append(node.toString());
					if (it.hasNext())
						sb.append(", ");
					if (node instanceof BPlusTree.InternalNode)
						nextQueue.add(((InternalNode) node).children);
				}
				sb.append('}');
				if (!queue.isEmpty())
					sb.append(", ");
				else
					sb.append('\n');
			}
			queue = nextQueue;
		}

		return sb.toString();
	}

	private abstract class Node {
		List<K> keys;

		int keyNumber() {
			return keys.size();
		}

		abstract V getValue(K key);

		abstract void deleteValue(K key);

		abstract void insertValue(K key, V value);

		abstract K getFirstLeafKey();

		abstract List<V> getRange(K key1, RangePolicy policy1, K key2,
				RangePolicy policy2);

		abstract void merge(Node sibling);

		abstract Node split();

		abstract boolean isOverflow();

		abstract boolean isUnderflow();

		public String toString() {
			return keys.toString();
		}
	}

	private class InternalNode extends Node {
		List<Node> children;

		InternalNode() {
			this.keys = new ArrayList<K>();
			this.children = new ArrayList<Node>();
		}

		@Override
		V getValue(K key) {
			return getChild(key).getValue(key);
		}

		/**
		 * 删除流程（答辩高频追问）：
		 * 1. 递归到叶子删除 key/value → 检查 child.isUnderflow()
		 * 2. 下溢处理：获取左右兄弟 → merge(兄弟)
		 * 3. merge 后如果 left.isOverflow() → split() 再分裂（先合再分）
		 * 4. deleteChild(right.getFirstLeafKey()) — 从父节点移除被合并节点的索引
		 * 5. 如果根 keys 变为 0 → root = left（降高度）
		 */
		@Override
		void deleteValue(K key) {
			Node child = getChild(key);
			child.deleteValue(key);
			if (child.isUnderflow()) {
				Node childLeftSibling = getChildLeftSibling(key);
				Node childRightSibling = getChildRightSibling(key);
				Node left = childLeftSibling != null ? childLeftSibling : child;
				Node right = childLeftSibling != null ? child
						: childRightSibling;
				left.merge(right);
				deleteChild(right.getFirstLeafKey());
				if (left.isOverflow()) {
					Node sibling = left.split();
					insertChild(sibling.getFirstLeafKey(), sibling);
				}
				if (root.keyNumber() == 0)
					root = left;
			}
		}

		/**
		 * 插入流程：
		 * 1. getChild(key) 二分定位子节点 → child.insertValue(key, value) 递归到叶子
		 * 2. 回溯：如果 child.isOverflow() → child.split() → 新 sibling 插入父节点
		 * 3. 如果根节点本身也溢出（递归到根）→ split() 本节点 → 创建新 InternalNode 作为新根（树高 +1）
		 */
		@Override
		void insertValue(K key, V value) {
			Node child = getChild(key);
			child.insertValue(key, value);
			if (child.isOverflow()) {
				Node sibling = child.split();
				insertChild(sibling.getFirstLeafKey(), sibling);
			}
			if (root.isOverflow()) {
				Node sibling = split();
				InternalNode newRoot = new InternalNode();
				newRoot.keys.add(sibling.getFirstLeafKey());
				newRoot.children.add(this);
				newRoot.children.add(sibling);
				root = newRoot;
			}
		}

		@Override
		K getFirstLeafKey() {
			return children.get(0).getFirstLeafKey();
		}

		@Override
		List<V> getRange(K key1, RangePolicy policy1, K key2,
				RangePolicy policy2) {
			return getChild(key1).getRange(key1, policy1, key2, policy2);
		}

		/**
		 * InternalNode merge: 将兄弟节点的 keys 和 children 合并到当前节点。
		 * 先插入兄弟的 firstLeafKey 作为分隔键，再合并其余 keys 和所有 children。
		 */
		@Override
		void merge(Node sibling) {
			@SuppressWarnings("unchecked")
			InternalNode node = (InternalNode) sibling;
			keys.add(node.getFirstLeafKey());
			keys.addAll(node.keys);
			children.addAll(node.children);

		}

		/**
		 * InternalNode split: 中间键上移到父节点（子节点不再保留）。
		 * 分裂点偏右（keyNumber()/2 + 1）保证 keys[i] 对应 children[i+1] 的关系正确。
		 * 
		 * 例：7 个 key [k1..k7] 对应 8 个 children [c1..c8]
		 *   from = 7/2 + 1 = 4 → 原节点保留 k1..k3 + c1..c4
		 *                      sibling 得到 k5..k7 + c5..c8（k4 上移父节点）
		 */
		@Override
		Node split() {
			int from = keyNumber() / 2 + 1, to = keyNumber();
			InternalNode sibling = new InternalNode();
			sibling.keys.addAll(keys.subList(from, to));
			sibling.children.addAll(children.subList(from, to + 1));

			keys.subList(from - 1, to).clear();
			children.subList(from, to + 1).clear();

			return sibling;
		}

		@Override
		boolean isOverflow() {
			return children.size() > branchingFactor;
		}

		/**
		 * 下溢条件：children 数低于半满。
		 * 阈值 = (branchingFactor + 1) / 2，向上取整。
		 * 例：branchingFactor=4 → 阈值=2.5 向上取整=2。children<2 时下溢。
		 */
		@Override
		boolean isUnderflow() {
			return children.size() < (branchingFactor + 1) / 2;
		}

		/**
		 * 二分查找定位 key 对应的子节点索引。
		 * Collections.binarySearch 返回值：
		 *   loc >= 0 → key 正好等于 keys[loc] → childIndex = loc + 1
		 *   loc < 0  → key 不在 keys 中 → childIndex = -loc - 1（插入点）
		 * 这是 B+Tree 内部节点路由的核心：keys[i] 是 children[i+1] 子树的最小键。
		 */
		Node getChild(K key) {
			int loc = Collections.binarySearch(keys, key);
			int childIndex = loc >= 0 ? loc + 1 : -loc - 1;
			return children.get(childIndex);
		}

		void deleteChild(K key) {
			int loc = Collections.binarySearch(keys, key);
			if (loc >= 0) {
				keys.remove(loc);
				children.remove(loc + 1);
			}
		}

		void insertChild(K key, Node child) {
			int loc = Collections.binarySearch(keys, key);
			int childIndex = loc >= 0 ? loc + 1 : -loc - 1;
			if (loc >= 0) {
				children.set(childIndex, child);
			} else {
				keys.add(childIndex, key);
				children.add(childIndex + 1, child);
			}
		}

		Node getChildLeftSibling(K key) {
			int loc = Collections.binarySearch(keys, key);
			int childIndex = loc >= 0 ? loc + 1 : -loc - 1;
			if (childIndex > 0)
				return children.get(childIndex - 1);

			return null;
		}

		Node getChildRightSibling(K key) {
			int loc = Collections.binarySearch(keys, key);
			int childIndex = loc >= 0 ? loc + 1 : -loc - 1;
			if (childIndex < keyNumber())
				return children.get(childIndex + 1);

			return null;
		}
	}

	private class LeafNode extends Node {
		List<V> values;
		LeafNode next;

		LeafNode() {
			keys = new ArrayList<K>();
			values = new ArrayList<V>();
		}

		@Override
		V getValue(K key) {
			int loc = Collections.binarySearch(keys, key);
			return loc >= 0 ? values.get(loc) : null;
		}

		@Override
		void deleteValue(K key) {
			int loc = Collections.binarySearch(keys, key);
			if (loc >= 0) {
				keys.remove(loc);
				values.remove(loc);
			}
		}

		@Override
		void insertValue(K key, V value) {
			int loc = Collections.binarySearch(keys, key);
			int valueIndex = loc >= 0 ? loc : -loc - 1;
			if (loc >= 0) {
				values.set(valueIndex, value);
			} else {
				keys.add(valueIndex, key);
				values.add(valueIndex, value);
			}
			if (root.isOverflow()) {
				Node sibling = split();
				InternalNode newRoot = new InternalNode();
				newRoot.keys.add(sibling.getFirstLeafKey());
				newRoot.children.add(this);
				newRoot.children.add(sibling);
				root = newRoot;
			}
		}

		@Override
		K getFirstLeafKey() {
			return keys.get(0);
		}

		/**
		 * 范围查询 — B+Tree 的核心优势（答辩必答）。
		 * 从当前叶子节点开始，顺着 LeafNode.next 链表顺序扫描。
		 * 时间复杂度 O(log n + k)，k 为范围内的键数。
		 * B-Tree 做范围查询需要中序遍历整棵树，B+Tree 只需要链表顺序扫描。
		 * 
		 * 边界处理：遇 key > key2（EXCLUSIVE）或 key >= key2（INCLUSIVE）时停止扫描，
		 * 因为叶子链表是有序的，后面的 key 只会更大。
		 */
		@Override
		List<V> getRange(K key1, RangePolicy policy1, K key2,
				RangePolicy policy2) {
			List<V> result = new LinkedList<V>();
			LeafNode node = this;
			while (node != null) {
				Iterator<K> kIt = node.keys.iterator();
				Iterator<V> vIt = node.values.iterator();
				while (kIt.hasNext()) {
					K key = kIt.next();
					V value = vIt.next();
					int cmp1 = key.compareTo(key1);
					int cmp2 = key.compareTo(key2);
					if (((policy1 == RangePolicy.EXCLUSIVE && cmp1 > 0) || (policy1 == RangePolicy.INCLUSIVE && cmp1 >= 0))
							&& ((policy2 == RangePolicy.EXCLUSIVE && cmp2 < 0) || (policy2 == RangePolicy.INCLUSIVE && cmp2 <= 0)))
						result.add(value);
					else if ((policy2 == RangePolicy.EXCLUSIVE && cmp2 >= 0)
							|| (policy2 == RangePolicy.INCLUSIVE && cmp2 > 0))
						return result;
				}
				node = node.next;
			}
			return result;
		}

		/**
		 * LeafNode merge: 将兄弟叶子的 keys + values 合并到当前节点。
		 * 关键：合并后更新 next 指针指向兄弟的 next，维护链表连续性。
		 */
		@Override
		void merge(Node sibling) {
			@SuppressWarnings("unchecked")
			LeafNode node = (LeafNode) sibling;
			keys.addAll(node.keys);
			values.addAll(node.values);
			next = node.next;
		}

		/**
		 * LeafNode split: 中间键复制到父节点（叶子自己保留一份）。
		 * 分裂点偏左（(keyNumber()+1)/2，向上取半）保证两边数据量接近。
		 * 
		 * 例：5 个 key [k1..k5]
		 *   from = (5+1)/2 = 3 → 原节点保留 k1..k2
		 *                      sibling 得到 k3..k5（k3 复制到父节点作为索引键）
		 * 分裂后设置 sibling.next = next, next = sibling，维护链表连续性。
		 */
		@Override
		Node split() {
			LeafNode sibling = new LeafNode();
			int from = (keyNumber() + 1) / 2, to = keyNumber();
			sibling.keys.addAll(keys.subList(from, to));
			sibling.values.addAll(values.subList(from, to));

			keys.subList(from, to).clear();
			values.subList(from, to).clear();

			sibling.next = next;
			next = sibling;
			return sibling;
		}

		/**
		 * LeafNode 溢出条件：values.size() > branchingFactor - 1。
		 * 叶子容量比内部节点少 1，因为分裂时中间键会被复制到父节点，
		 * 叶子保留该键，所以预留一个槽位防止分裂后溢出。
		 */
		@Override
		boolean isOverflow() {
			return values.size() > branchingFactor - 1;
		}

		/**
		 * LeafNode 下溢条件：values.size() < branchingFactor / 2（向下取整）。
		 * 例：branchingFactor=64 → 阈值=32。values<32 时下溢。
		 */
		@Override
		boolean isUnderflow() {
			return values.size() < branchingFactor / 2;
		}
	}
}
