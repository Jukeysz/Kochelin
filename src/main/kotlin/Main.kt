import jdk.internal.misc.Blocker.begin
import java.io.DataInputStream
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.LinkedList
import java.util.Queue
import kotlin.math.log2
import kotlin.math.pow
import kotlin.random.Random
import kotlin.system.exitProcess

data class Info(var comp: Int = 0, var cap: Int = 0, var con: Int = 0,
                var hit: Int = 0, var access: Int = 0)

enum class Replacement {R, L, F}

fun main(args: Array<String>) {
    if (args.size != 6) {
        println("Incorrect number of arguments.")
        println("java Kochelin <nsets> <bsize> <assoc> <subst> <flag_saida> archive")
        exitProcess(1)
    }

    val nsets = args[0].toIntOrNull() ?: parsingError(args[0])
    val bsize = args[1].toIntOrNull() ?: parsingError(args[1])
    val assoc = args[2].toIntOrNull() ?: parsingError(args[2])
    val subst = args[3]
    val flag = when (args[4].toIntOrNull()) {
        1 -> "true"
        0 -> "false"
        else -> null
    }
    val flagOut = flag?.toBooleanStrictOrNull() ?: parsingBoolError(args[4])
    val inArchive = args[5]

    var infos = Info()

    if (!flagOut) {
        println("nsets = $nsets")
        println("bsize = $bsize")
        println("assoc = $assoc")
        println("subst = $subst")
        println("flagOut = $flagOut")
        println("inArchive = $inArchive")
    }


    if (!isPowerOfTwo(nsets)) {
        println("Error: nsets ($nsets) must be a power of two")
        exitProcess(1)
    }
    if (!isPowerOfTwo(bsize)) {
        println("Error: bsize ($bsize) must be a power of two")
        exitProcess(1)
    }

    // The main control flow
    // find a way to generate the entries (binary addresses) as a lazy iter
    // the cache is addressed by bytes and the addresses are 32-bit long

    // I need to know the following: index, offset and tag
    // I get the index by using log2(index). But I dont yet know the
    val nBitsIndex = kotlin.math.log2(nsets.toDouble()).toInt()
    val nBitsOffset = kotlin.math.log2(bsize.toDouble()).toInt()
    val nBitsTag = 32 - nBitsIndex - nBitsOffset
    val replacement = when (subst.uppercase()) {
        "R" -> Replacement.R
        "L" -> Replacement.L
        "F" -> Replacement.F
        else -> {
            println("Error: subst($subst) must be a valid options (LRU, F or R")
            exitProcess(1)
        }
    }

    val cache_val = MutableList(nsets*assoc) { false }
    val cache_tag = MutableList(nsets*assoc) { 0 }
    // one queue per entry
    // this queue stores the indices inside the entry range (the associativity column indices)
    var queues: List<ArrayDeque<Int>> = List(nsets) { ArrayDeque<Int>() }

    val inputStream = ClassLoader.getSystemResourceAsStream(inArchive) ?: run {
        println("Error: could not open file $inArchive")
        exitProcess(1)
    }
    inputStream.use { fis ->
        val buffer = ByteArray(4) // 32 bit

        readLoop@while (fis.read(buffer) == 4) {
            val address = ByteBuffer.wrap(buffer)
                .order(ByteOrder.BIG_ENDIAN)
                .int.toUInt().toInt()
            infos.access++
            val tag = address ushr (nBitsOffset+nBitsIndex)
            val index = (address ushr nBitsOffset) and ((1 shl nBitsIndex) - 1)

            when {
                // DIRECT MAPPING
                assoc == 1 -> {
                    if (!cache_val[index]) {
                        infos.comp++
                        cache_val[index] = true
                        cache_tag[index] = tag
                    } else {
                        if (cache_tag[index] == tag) {
                            infos.hit++
                        } else {
                            infos.con++
                            cache_tag[index] = tag
                            cache_val[index] = true
                        }
                    }
                }

                // ASSOCIATIVITY
                // With associativity, both cache_val and cache_tag are flattened matrices
                // the columns represent the aggregated "blocks" into a single cache entry
                assoc > 1 -> {
                    // go through the range index * assoc until (index + 1) * assoc
                    val begin = index * assoc
                    val end = (index + 1) * assoc

                    // First verify if there is a position in range begin up to  end-1
                    // that has cache_val true and cache_tag == tag
                    // this is the hit.
                    // If this doesn't happen, I should check if there are free spots
                    // in this entry and put into the first one I see, adding a Compulsory.
                    // If there are no free spots, activate the replacement function.

                    // For dealing with LRU, use the column indices (maybe tag) for checking
                    // the priority positions to be replaced.
                    // The difference between LRU and FIFO is that LRU pushes the hit elem to the
                    // front of the queue

                    // CHECK HIT
                    for (i in begin until end) {
                        if (cache_val[i] && cache_tag[i] == tag) {
                            infos.hit++

                            if (replacement == Replacement.L) {
                                // if the element is not in the queue, add it
                                val columnIdx: Int = i - begin
                                if (!queues[index].contains(columnIdx)) {
                                    queues[index].addFirst(columnIdx)
                                } else {
                                    queues[index].remove(columnIdx)
                                    queues[index].addFirst(columnIdx)
                                }
                            }
                            continue@readLoop
                        }
                    }

                    // CHECK IF COMPULSORY
                    for (i in begin until end) {
                        val columnIdx: Int = i - begin
                        if (!cache_val[i]) {
                            infos.comp++
                            cache_val[i] = true
                            cache_tag[i] = tag
                            when (replacement) {
                                Replacement.F, Replacement.L -> {
                                    queues[index].addFirst(columnIdx)
                                }

                                else -> {}
                            }
                            continue@readLoop
                        }
                    }

                    // treat the capacity or conflict MISS
                    if (isCacheFull(cache_val)) infos.cap++ else infos.con++

                    when (replacement) {
                        Replacement.R -> {
                            val random = Random.nextInt(begin, end)
                            cache_tag[random] = tag
                        }

                        Replacement.F, Replacement.L -> {
                            // Takes the last element (the last used element from the hits) from the queue
                            // and use it as index for cache_tag, then replace it treating the fault.
                            // Right away we remove the last element and put it in the front
                            // so the queue always has the same size
                            val lruColumnIdx = queues[index].removeLast()
                            val lruIdx = begin + lruColumnIdx
                            cache_tag[lruIdx] = tag
                            cache_val[lruIdx] = true
                            queues[index].addFirst(lruColumnIdx)
                        }
                    }
                }
            }
        }
    }

    val misses = infos.comp + infos.cap + infos.con
    val hitRate = infos.hit.toDouble() / infos.access
    val missRate = misses.toDouble() / infos.access
    val compFracMiss = infos.comp.toDouble() / misses
    val capFracMiss = infos.cap.toDouble() / misses
    val conFracMiss = infos.con.toDouble() / misses

    if (flagOut) {
        println(
            "${infos.access} " +
                    "%.4f".format(hitRate) + " " +
                    "%.4f".format(missRate) + " " +
                    "%.4f".format(compFracMiss) + " " +
                    "%.4f".format(capFracMiss)  + " " +
                    "%.4f".format(conFracMiss)
        )
    } else {
        println("Accesses: ${infos.access}")
        println("Hit rate: %.2f%%".format(hitRate * 100))
        println("Miss rate: %.2f%%".format(missRate * 100))
        println("Compulsory miss rate: %.2f%%".format(compFracMiss * 100))
        println("Capacity miss rate: %.2f%%".format(capFracMiss * 100))
        println("Conflict miss rate: %.2f%%".format(conFracMiss * 100))
    }
}

fun parsingBoolError(str: String): Nothing {
    println("$str must be either 0 or 1")
    exitProcess(1)
}

fun parsingError(str: String): Nothing {
    println("$str is not a valid integer")
    exitProcess(1)
}

fun isPowerOfTwo(n: Int): Boolean {
    return n > 0 && (n and (n - 1)) == 0
}

fun hasCapacity(entries: List<Boolean>): Boolean {
    return if (false in entries) false else true
}

fun isCacheFull(entries: List<Boolean>): Boolean {
    return entries.all { it }
}