import jdk.internal.misc.Blocker.begin
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

enum class Replacement {R, LRU, F}

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
    val flagOut = args[4].toIntOrNull() ?: parsingError(args[4])
    val inArchive = args[5]

    var infos = Info()

    println("nsets = $nsets")
    println("bsize = $bsize")
    println("assoc = $assoc")
    println("subst = $subst")
    println("flagOut = $flagOut")
    println("inArchive = $inArchive")

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
        "LRU" -> Replacement.LRU
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
                .int

            infos.access++
            val tag = address shr (nBitsOffset+nBitsIndex)
            val index = (address shr nBitsOffset) and ((1 shl nBitsIndex) - 1)

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
                assoc != 1 && nsets != 1 -> {
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

                            if (replacement == Replacement.LRU) {
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
                                Replacement.F, Replacement.LRU -> {
                                    queues[index].add(columnIdx)
                                }

                                else -> {}
                            }
                            continue@readLoop
                        }
                    }

                    // treat the capacity or conflict MISS
                    if (hasSetCapacity(cache_val, begin, end)) infos.con++ else infos.cap++

                    when (replacement) {
                        Replacement.R -> {
                            val random = Random.nextInt(begin, end)
                            cache_tag[random] = tag
                        }

                        Replacement.F, Replacement.LRU -> {
                            // Takes the last element (the last used element from the hits) from the queue
                            // and use it as index for cache_tag, then replace it treating the fault.
                            // Right away we remove the last element and put it in the front
                            // so the queue always has the same size
                            cache_tag[queues[index].last()] = tag
                            queues[index].add(queues[index].removeLast())
                        }
                    }
                }

                // TOTALLY ASSOCIATIVE
                nsets.toInt() == 1 -> {
                    when (replacement) {
                        Replacement.R -> {
                            for (entry in cache_tag) {
                                if (entry == tag) {
                                    infos.hit++
                                    continue@readLoop
                                }
                            }
                            if (hasCapacity(cache_val)) infos.con++ else infos.cap++
                            val begin = index * assoc
                            val end = (index + 1) * assoc
                            val random = Random.nextInt(begin, end - 1)
                            cache_tag[random] = tag
                            cache_val[random] = true
                        }

                        Replacement.F -> {
                            //TODO
                        }

                        Replacement.LRU -> {
                            //TODO
                        }
                    }
                }
            }
        }
    }

    println("Results:")
    val misses       = infos.comp + infos.cap + infos.con

    val hitRate      = infos.hit   .toDouble() / infos.access
    val missRate     = misses      .toDouble() / infos.access

    val compFracMiss = infos.comp  .toDouble() / misses
    val capFracMiss  = infos.cap   .toDouble() / misses
    val conFracMiss  = infos.con   .toDouble() / misses

    println(
        "${infos.access} " +
                "%.4f".format(hitRate) + " " +
                "%.4f".format(missRate) + " " +
                "%.4f".format(compFracMiss) + " " +
                "%.4f".format(capFracMiss)  + " " +
                "%.4f".format(conFracMiss)
    )
}

fun parsingError(str: String): Nothing {
    println("$str is not a valid integer")
    exitProcess(1)
}

fun isPowerOfTwo(n: Int): Boolean {
    return n > 0 && (n and (n - 1)) == 0
}

fun hasCapacity(entries: List<Boolean>): Boolean {
    return if(false in entries) true else false
}

fun hasSetCapacity(entries: List<Boolean>, begin: Int, end: Int): Boolean {
    for (i in begin until end) {
        if (!entries[i]) return true
    }
    return false
}