import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.log2
import kotlin.random.Random
import kotlin.system.exitProcess

class Mustard(val id: Int, val email: String) {
    val category: String = ""
}

data class Katchup(val id: Int, val flavor: String)

fun main(args: Array<String>) {

    val mustards = Mustard(40, "coconuts")

    val oranges = 10

    when (oranges) {
        10 -> println("We are rich!")
        0 -> println("We have no oranges!")
        else -> println("We have abnormal oranges!")
    }

    val manuscript = 1..4 step 2
    val manuscript1 = 4 downTo 1

    for (i in manuscript) println(i)
    for (i in manuscript1) println(i)

    fun shortIncr(a: Int, b: Int): Int = a + b
    println("The shortincr val is ${shortIncr(1, 1)}")


    val lambdaIncr = { text: String -> text.uppercase() }
    println("The lambdaIncr val is ${lambdaIncr("dance floor")}")

    fun toSeconds(time: String): (Int) -> Int = when (time) {
        "hour" -> { value -> value * 60}
        "minute" -> { value -> value * 60 * 60}
        "second" -> { value -> value * 60 * 60 * 24}
        else -> { value -> value }
    }
}


// returns zero if "a" is null
fun nullables(a: String?) {
    println(a?.length ?: 0)
}

fun icr(a: Int, b: Int = 5): Int {
    return a + b
}

fun lambdaLoop() {
    val numbers = listOf(1, -2, 3, -4, 5, -6)

    val positives = numbers.filter ({ x: Int -> x > 0})
    val isNegative = { x: Int -> x < 0 }
    val negatives = numbers.filter(isNegative)

    // could be rewritten as
    val negatives1 = numbers.filter { x: Int -> x < 0 }
    var myDict = mutableMapOf("apples" to 50, "bananas" to 0)
}


// COLLECTIONS
//    val dogs = 2 // read only
//    var cats = 5 // mut
//
//    val myAnimals = listOf(dogs, cats)
//    myAnimals.forEach { a -> println("$a") }
//
//    val mutMyAnimals = mutableListOf("triangle", "spoon")
//
//    var myDict = mutableMapOf("apples" to 50, "bananas" to 0)
//    myDict.forEach { k, v -> println("key is $k, val is $v") }