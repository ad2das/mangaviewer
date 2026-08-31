package ml.melun.mangaview.viewer

internal class ImmutableLongSumTree private constructor(
    private val root: Node,
) {
    val size: Int
        get() = root.endExclusive

    val sum: Long
        get() = root.sum

    fun prefixSum(endExclusive: Int): Long {
        require(endExclusive in 0..size)
        return prefixSum(root, endExclusive)
    }

    fun update(index: Int, value: Long): ImmutableLongSumTree {
        require(index in 0 until size)
        require(value >= 0L)
        return ImmutableLongSumTree(update(root, index, value))
    }

    fun indexAtOffset(offset: Long): Int {
        require(offset in 0 until sum)
        return indexAtOffset(root, offset)
    }

    fun firstPositiveAtOrAfter(start: Int): Int? {
        require(start in 0..size)
        if (start == size || root.sum == 0L) return null
        return firstPositiveAtOrAfter(root, start)
    }

    fun lastPositiveAtOrBefore(start: Int): Int? {
        require(start in 0 until size)
        if (root.sum == 0L) return null
        return lastPositiveAtOrBefore(root, start)
    }

    fun values(): List<Long> = buildList(size) { appendValues(root, this) }

    private fun prefixSum(node: Node, endExclusive: Int): Long = when {
        endExclusive <= node.start -> 0L
        endExclusive >= node.endExclusive -> node.sum
        node.isLeaf -> node.sum
        else -> prefixSum(requireNotNull(node.left), endExclusive) +
            prefixSum(requireNotNull(node.right), endExclusive)
    }

    private fun update(node: Node, index: Int, value: Long): Node {
        if (node.isLeaf) return node.copy(sum = value)
        val left = requireNotNull(node.left)
        val right = requireNotNull(node.right)
        return if (index < left.endExclusive) {
            val replacement = update(left, index, value)
            node.copy(sum = Math.addExact(replacement.sum, right.sum), left = replacement)
        } else {
            val replacement = update(right, index, value)
            node.copy(sum = Math.addExact(left.sum, replacement.sum), right = replacement)
        }
    }

    private fun indexAtOffset(node: Node, offset: Long): Int {
        if (node.isLeaf) return node.start
        val left = requireNotNull(node.left)
        return if (offset < left.sum) {
            indexAtOffset(left, offset)
        } else {
            indexAtOffset(requireNotNull(node.right), offset - left.sum)
        }
    }

    private fun firstPositiveAtOrAfter(node: Node, start: Int): Int? {
        if (node.endExclusive <= start || node.sum == 0L) return null
        if (node.isLeaf) return node.start
        return firstPositiveAtOrAfter(requireNotNull(node.left), start)
            ?: firstPositiveAtOrAfter(requireNotNull(node.right), start)
    }

    private fun lastPositiveAtOrBefore(node: Node, start: Int): Int? {
        if (node.start > start || node.sum == 0L) return null
        if (node.isLeaf) return node.start
        return lastPositiveAtOrBefore(requireNotNull(node.right), start)
            ?: lastPositiveAtOrBefore(requireNotNull(node.left), start)
    }

    private fun appendValues(node: Node, output: MutableList<Long>) {
        if (node.isLeaf) {
            output += node.sum
            return
        }
        appendValues(requireNotNull(node.left), output)
        appendValues(requireNotNull(node.right), output)
    }

    private data class Node(
        val start: Int,
        val endExclusive: Int,
        val sum: Long,
        val left: Node? = null,
        val right: Node? = null,
    ) {
        val isLeaf: Boolean
            get() = endExclusive - start == 1
    }

    companion object {
        fun create(values: List<Long>): ImmutableLongSumTree {
            require(values.isNotEmpty())
            require(values.all { it >= 0L })
            return ImmutableLongSumTree(build(values, 0, values.size))
        }

        private fun build(values: List<Long>, start: Int, endExclusive: Int): Node {
            if (endExclusive - start == 1) return Node(start, endExclusive, values[start])
            val middle = (start + endExclusive) ushr 1
            val left = build(values, start, middle)
            val right = build(values, middle, endExclusive)
            return Node(start, endExclusive, Math.addExact(left.sum, right.sum), left, right)
        }
    }
}
