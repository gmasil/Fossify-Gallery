package org.fossify.gallery.helpers

import java.util.ArrayDeque
import kotlin.math.cos
import kotlin.math.sqrt

internal object PerceptualHash {
    const val SIZE = 32

    private const val DCT_SIZE = 8
    private val cosineMatrix = Array(SIZE) { position ->
        DoubleArray(DCT_SIZE) { frequency ->
            val scale = if (frequency == 0) sqrt(1.0 / SIZE) else sqrt(2.0 / SIZE)
            scale * cos((2 * position + 1) * frequency * Math.PI / (2 * SIZE))
        }
    }

    fun calculate(luminance: DoubleArray): Long {
        val horizontalDct = Array(DCT_SIZE) { DoubleArray(SIZE) }
        for (frequency in 0 until DCT_SIZE) {
            for (row in 0 until SIZE) {
                var sum = 0.0
                for (column in 0 until SIZE) {
                    sum += luminance[row * SIZE + column] * cosineMatrix[column][frequency]
                }
                horizontalDct[frequency][row] = sum
            }
        }

        val coefficients = Array(DCT_SIZE) { DoubleArray(DCT_SIZE) }
        for (verticalFrequency in 0 until DCT_SIZE) {
            for (horizontalFrequency in 0 until DCT_SIZE) {
                var sum = 0.0
                for (row in 0 until SIZE) {
                    sum += horizontalDct[horizontalFrequency][row] * cosineMatrix[row][verticalFrequency]
                }
                coefficients[verticalFrequency][horizontalFrequency] = sum
            }
        }

        val acCoefficients = ArrayList<Double>(DCT_SIZE * DCT_SIZE - 1)
        for (vertical in 0 until DCT_SIZE) {
            for (horizontal in 0 until DCT_SIZE) {
                if (vertical != 0 || horizontal != 0) {
                    acCoefficients.add(coefficients[vertical][horizontal])
                }
            }
        }

        val median = acCoefficients.sorted()[acCoefficients.size / 2]
        var hash = 0L
        var bit = 0
        for (vertical in 0 until DCT_SIZE) {
            for (horizontal in 0 until DCT_SIZE) {
                if (vertical != 0 || horizontal != 0) {
                    if (coefficients[vertical][horizontal] > median) hash = hash or (1L shl bit)
                    bit++
                }
            }
        }
        return hash
    }

    fun distance(first: Long, second: Long): Int = java.lang.Long.bitCount(first xor second)
}

internal class PerceptualHashIndex {
    private class Node(val hash: Long) {
        val indices = ArrayList<Int>()
        val children = HashMap<Int, Node>()
    }

    private var root: Node? = null

    fun add(hash: Long, index: Int) {
        val first = root
        if (first == null) {
            root = Node(hash).apply { indices.add(index) }
            return
        }

        var node: Node = first
        while (true) {
            val distance = PerceptualHash.distance(hash, node.hash)
            if (distance == 0) {
                node.indices.add(index)
                return
            }

            val child = node.children[distance]
            if (child == null) {
                node.children[distance] = Node(hash).apply { indices.add(index) }
                return
            }
            node = child
        }
    }

    fun findWithin(hash: Long, maxDistance: Int): List<Int> {
        val matches = ArrayList<Int>()
        val pending = ArrayDeque<Node>()
        root?.let(pending::addLast)
        while (pending.isNotEmpty()) {
            val node = pending.removeLast()
            val distance = PerceptualHash.distance(hash, node.hash)
            if (distance <= maxDistance) matches.addAll(node.indices)

            val minimumDistance = distance - maxDistance
            val maximumDistance = distance + maxDistance
            node.children.forEach { (edgeDistance, child) ->
                if (edgeDistance in minimumDistance..maximumDistance) pending.addLast(child)
            }
        }
        return matches
    }
}
