package control

// NEGATIVE CONTROL for rule 1: a pattern hoisted to a top-level val compiles into <clinit>.
private val HOISTED = Regex("[A-Z]{2}-\\d{4}")

// POSITIVE CONTROL for rule 1: the same pattern built inside the function.
fun validateInBody(code: String): Boolean = Regex("[A-Z]{2}-\\d{4}").matches(code)

fun validateHoisted(code: String): Boolean = HOISTED.matches(code)

// POSITIVE CONTROL for rule 2: three eager operators, three intermediate collections.
fun chain(xs: List<Int>): List<String> = xs.filter { it > 0 }.map { it * 2 }.sortedBy { it }.map { it.toString() }

// NEGATIVE CONTROL for rule 2: one operator is not a chain.
fun single(xs: List<Int>): List<Int> = xs.map { it + 1 }

// NEGATIVE CONTROL for rule 2: a sequence has no intermediate collection per link.
fun lazyChain(xs: List<Int>): List<Int> = xs.asSequence().filter { it > 0 }.map { it * 2 }.toList()

// POSITIVE CONTROL for rule 3: a body over FreqInlineSize.
fun long(a: Int, b: Int, c: Int): Int {
    var s = 0
    for (i in 0 until a) { s += i * b - c + 1; if (s % 3 == 0) s += a * 1 else s -= b - 1 }
    for (i in 0 until a) { s += i * b - c + 2; if (s % 4 == 0) s += a * 2 else s -= b - 2 }
    for (i in 0 until a) { s += i * b - c + 3; if (s % 5 == 0) s += a * 3 else s -= b - 3 }
    for (i in 0 until a) { s += i * b - c + 4; if (s % 6 == 0) s += a * 4 else s -= b - 4 }
    for (i in 0 until a) { s += i * b - c + 5; if (s % 7 == 0) s += a * 5 else s -= b - 5 }
    for (i in 0 until a) { s += i * b - c + 6; if (s % 8 == 0) s += a * 6 else s -= b - 6 }
    for (i in 0 until a) { s += i * b - c + 7; if (s % 9 == 0) s += a * 7 else s -= b - 7 }
    for (i in 0 until a) { s += i * b - c + 8; if (s % 10 == 0) s += a * 8 else s -= b - 8 }
    return s
}

// NEGATIVE CONTROL for rule 3: a short body.
fun short(a: Int): Int = a + 1

// POSITIVE CONTROL for rule 2, the string half: four intermediates, and the shape the profile of a
// real service charged the most bytes to (MoneyFormat.group in konekt).
fun stringChain(n: Long): String = n.toString().reversed().chunked(3).joinToString(",").reversed()

// NEGATIVE CONTROL: one operator is not a chain.
fun oneString(s: String): String = s.reversed().toString()

// THE TWO PATTERN CASES, which the report tells apart and answers differently (B-06).
// A constant can be hoisted into <clinit>; a string assembled at the call site cannot, and a rule
// that tells its author to hoist it is a rule that teaches suppression without reading.
fun constantPattern(code: String): Boolean = Regex("[A-Z]{2}-\\d{4}").matches(code)

fun interpolatedPattern(key: String, line: String): Boolean = Regex("$key=\\d+").matches(line)
