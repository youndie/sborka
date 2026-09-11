package parity

import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlin.random.Random
import kotlin.test.Test

/*
 * One source, two runtimes, one transcript.
 *
 * Every probe prints a line `PARITY<tab>class<tab>name<tab>value`. Nothing is asserted here on
 * purpose: an assertion encodes which target is right, and that is the question rather than the
 * setup. `compare.py` diffs the two transcripts; a row that differs is a finding, a row that agrees
 * is a divergence class this portfolio can stop worrying about — and the second kind is most of the
 * value, because it is what an unmeasured catalogue never tells you.
 *
 * A probe that throws records the throw. An exception's class and message are themselves one of the
 * classes under test, and a probe that killed the run would take the rest of the transcript with it.
 */
private fun p(cls: String, name: String, value: () -> Any?) {
    val v =
        runCatching { value()?.toString() ?: "null" }
            .getOrElse { "!${it::class.simpleName}: ${it.message}" }
    // One row, one line. A serialization message carries its own newlines and the JSON it choked on,
    // and a transcript that let them through would compare a row against the middle of another one.
    println("PARITY\t$cls\t$name\t" + v.replace("\n", "\\n").replace("\t", "\\t"))
}

@Serializable
private data class Point(val x: Int, val y: String)

@Serializable
private data class WithDefault(val a: Int, val b: String = "d")

@Serializable
private data class Numbers(val d: Double, val f: Float, val l: Long)

class ParityProbe {
    @Test
    fun collections() {
        val keys = listOf("alpha", "beta", "gamma", "delta", "epsilon", "zeta", "eta", "theta")
        p("collections", "hashmap-iteration-order") {
            HashMap<String, Int>().apply { keys.forEachIndexed { i, k -> put(k, i) } }.keys.joinToString(",")
        }
        p("collections", "hashset-iteration-order") { HashSet(keys).joinToString(",") }
        p("collections", "hashmap-int-keys") {
            HashMap<Int, Int>().apply { (1..12).forEach { put(it * 7, it) } }.keys.joinToString(",")
        }
        p("collections", "mutablemap-iteration-order") {
            mutableMapOf<String, Int>().apply { keys.forEachIndexed { i, k -> put(k, i) } }.keys.joinToString(",")
        }
        p("collections", "groupby-key-order") { keys.groupBy { it.length }.keys.joinToString(",") }
        p("collections", "toset-order") { keys.toSet().joinToString(",") }
    }

    @Test
    fun hashing() {
        p("hashing", "string-hashcode") { "hello world".hashCode() }
        p("hashing", "string-hashcode-unicode") { "héllo ᚠ 𝔘".hashCode() }
        p("hashing", "data-class-hashcode") { Point(7, "seven").hashCode() }
        p("hashing", "list-hashcode") { listOf(1, "a", 2.5).hashCode() }
        p("hashing", "map-hashcode") { mapOf("a" to 1, "b" to 2).hashCode() }
        p("hashing", "char-hashcode") { 'é'.hashCode() }
        p("hashing", "double-hashcode") { (0.1 + 0.2).hashCode() }
        p("hashing", "long-hashcode") { 1234567890123L.hashCode() }
        p("hashing", "boolean-hashcode") { true.hashCode() }
        p("hashing", "enum-name-hashcode") { RegexOption.IGNORE_CASE.name.hashCode() }
    }

    @Test
    fun numberFormatting() {
        p("number-format", "sum-0.1-0.2") { (0.1 + 0.2).toString() }
        p("number-format", "one-third") { (1.0 / 3.0).toString() }
        p("number-format", "1e23") { 1e23.toString() }
        p("number-format", "1e7") { 1e7.toString() }
        p("number-format", "1e-5") { 1e-5.toString() }
        p("number-format", "1e-4") { 1e-4.toString() }
        p("number-format", "whole-100") { 100.0.toString() }
        p("number-format", "negative-zero") { (-0.0).toString() }
        p("number-format", "double-min-value") { Double.MIN_VALUE.toString() }
        p("number-format", "double-max-value") { Double.MAX_VALUE.toString() }
        p("number-format", "nan") { Double.NaN.toString() }
        p("number-format", "positive-infinity") { Double.POSITIVE_INFINITY.toString() }
        p("number-format", "float-one-third") { (1.0f / 3.0f).toString() }
        p("number-format", "float-0.1") { 0.1f.toString() }
        p("number-format", "float-widened") { (0.1f.toDouble()).toString() }
        p("number-format", "int-to-radix-36") { 123456789.toString(36) }
        p("number-format", "long-min-to-radix-2") { Long.MIN_VALUE.toString(2).length }
    }

    @Test
    fun numberParsing() {
        p("number-parse", "1e400") { "1e400".toDouble() }
        p("number-parse", "leading-dot") { ".5".toDouble() }
        p("number-parse", "trailing-dot") { "5.".toDouble() }
        p("number-parse", "hex-float") { "0x1p3".toDoubleOrNull() }
        p("number-parse", "plus-sign") { "+5".toIntOrNull() }
        p("number-parse", "whitespace-padded") { " 5 ".toIntOrNull() }
        p("number-parse", "unicode-digits") { "٣٤".toIntOrNull() }
        p("number-parse", "int-overflow-message") { "2147483648".toInt() }
        p("number-parse", "empty-double") { "".toDouble() }
        p("number-parse", "infinity-word") { "Infinity".toDouble() }
        p("number-parse", "nan-word") { "NaN".toDouble() }
    }

    @Test
    fun strings() {
        p("string", "uppercase-sharp-s") { "straße".uppercase() }
        p("string", "uppercase-ligature-fi") { "\uFB01le".uppercase() }
        p("string", "uppercase-dotless") { "i".uppercase() }
        p("string", "lowercase-dotted-I") { "\u0130".lowercase() }
        p("string", "uppercase-digraph") { "\u01C6".uppercase() }
        p("string", "replacefirstchar-titlecase") { "\u01C6x".replaceFirstChar { it.titlecase() } }
        p("string", "compare-case") { "a".compareTo("B") }
        p("string", "compare-accent") { "é".compareTo("z") }
        p("string", "sorted-mixed") { listOf("b", "A", "é", "_", "10", "2").sorted().joinToString(",") }
        p("string", "trim-nbsp") { "\u00A0x\u00A0".trim().length }
        p("string", "trim-ideographic-space") { "\u3000x".trim().length }
        p("string", "isletter-roman-numeral") { '\u216B'.isLetter() }
        p("string", "isdigit-arabic-indic") { '\u0663'.isDigit() }
        p("string", "iswhitespace-nbsp") { '\u00A0'.isWhitespace() }
        p("string", "surrogate-length") { "\uD835\uDD18".length }
        p("string", "surrogate-uppercase") { "\uD801\uDC28".uppercase() == "\uD801\uDC00" }
        p("string", "codepoint-count-via-chars") { "\uD835\uDD18a".count() }
        p("string", "lines-crlf") { "a\r\nb\rc\nd".lines().size }
        p("string", "split-empty-delimiter-count") { "abc".split("").size }
    }

    @Test
    fun regex() {
        p("regex", "word-class-on-accent") { Regex("\\w+").find("héllo")?.value }
        p("regex", "digit-class-on-arabic-indic") { Regex("\\d+").matches("\u0663\u0664") }
        p("regex", "space-class-on-nbsp") { Regex("\\s").matches("\u00A0") }
        p("regex", "unicode-letter-property") { Regex("\\p{L}+").find("héllo")?.value }
        p("regex", "unicode-script-property") { Regex("\\p{IsCyrillic}+").find("абв")?.value }
        p("regex", "posix-bracket-class") { Regex("[[:alpha:]]+").find("abc")?.value }
        p("regex", "lookbehind") { Regex("(?<=a)b").find("ab")?.value }
        p("regex", "variable-lookbehind") { Regex("(?<=a{1,3})b").find("aaab")?.value }
        p("regex", "named-group") { Regex("(?<first>a)(?<second>b)").find("ab")?.groups?.get("first")?.value }
        p("regex", "backreference") { Regex("(a)\\1").matches("aa") }
        p("regex", "possessive-quantifier") { Regex("a++b").matches("aab") }
        p("regex", "atomic-group") { Regex("(?>a+)b").matches("aab") }
        p("regex", "ignore-case-sharp-s") { Regex("ß", RegexOption.IGNORE_CASE).matches("SS") }
        p("regex", "ignore-case-turkish-i") { Regex("i", RegexOption.IGNORE_CASE).matches("\u0130") }
        p("regex", "word-boundary-unicode") { Regex("\\bé").find("é")?.value }
        p("regex", "dot-matches-line-separator") { Regex(".").matches("\u2028") }
        p("regex", "split-trailing-empty") { "a,b,,".split(Regex(",")).size }
        p("regex", "replace-group-reference") { Regex("(a)(b)").replace("ab", "$2$1") }
        p("regex", "replace-dollar-literal") { Regex("a").replace("a", "\\$") }
        p("regex", "empty-match-iteration") { Regex("a*").findAll("bab").count() }
        p("regex", "bad-pattern-message") { Regex("(") }
        p("regex", "quote-escaping") { Regex.escape("a.b") == "\\Qa.b\\E" }
    }

    @Test
    fun serialization() {
        val json = Json
        val lenient = Json { allowSpecialFloatingPointValues = true }
        p("serialization", "object-field-order") { json.encodeToString(Point.serializer(), Point(1, "x")) }
        p("serialization", "map-from-hashmap") {
            val m = HashMap<String, Int>().apply { listOf("z", "a", "m", "b").forEachIndexed { i, k -> put(k, i) } }
            json.encodeToString(MapSerializer(String.serializer(), Int.serializer()), m)
        }
        p("serialization", "double-0.1-0.2") { json.encodeToString(Double.serializer(), 0.1 + 0.2) }
        p("serialization", "double-1e23") { json.encodeToString(Double.serializer(), 1e23) }
        p("serialization", "float-one-third") { json.encodeToString(Float.serializer(), 1.0f / 3.0f) }
        p("serialization", "numbers-record") { json.encodeToString(Numbers.serializer(), Numbers(0.1 + 0.2, 1.0f / 3.0f, Long.MAX_VALUE)) }
        p("serialization", "nan-default-config") { json.encodeToString(Double.serializer(), Double.NaN) }
        p("serialization", "nan-allowed") { lenient.encodeToString(Double.serializer(), Double.NaN) }
        p("serialization", "infinity-allowed") { lenient.encodeToString(Double.serializer(), Double.POSITIVE_INFINITY) }
        p("serialization", "long-max") { json.encodeToString(Long.serializer(), Long.MAX_VALUE) }
        p("serialization", "missing-field-message") { json.decodeFromString(Point.serializer(), """{"x":1}""") }
        p("serialization", "type-mismatch-message") { json.decodeFromString(Point.serializer(), """{"x":"no","y":"a"}""") }
        p("serialization", "unknown-key-message") { json.decodeFromString(Point.serializer(), """{"x":1,"y":"a","z":2}""") }
        p("serialization", "malformed-message") { json.decodeFromString(Point.serializer(), """{"x":1,""") }
        p("serialization", "big-number-to-double") { json.decodeFromString(Double.serializer(), "1e400") }
        p("serialization", "long-overflow-message") { json.decodeFromString(Long.serializer(), "9223372036854775808") }
        p("serialization", "default-not-encoded") { json.encodeToString(WithDefault.serializer(), WithDefault(1)) }
        p("serialization", "escaped-output") { json.encodeToString(String.serializer(), "a\u2028b\u0007c/") }
        p("serialization", "descriptor-tostring") { Point.serializer().descriptor.toString() }
    }

    @Test
    fun datetime() {
        p("datetime", "available-zone-count") { TimeZone.availableZoneIds.size }
        p("datetime", "has-europe-moscow") { "Europe/Moscow" in TimeZone.availableZoneIds }
        p("datetime", "moscow-offset-1990") {
            Instant.parse("1990-07-01T12:00:00Z").toLocalDateTime(TimeZone.of("Europe/Moscow")).toString()
        }
        p("datetime", "moscow-offset-2011") {
            Instant.parse("2011-07-01T12:00:00Z").toLocalDateTime(TimeZone.of("Europe/Moscow")).toString()
        }
        p("datetime", "kiritimati-1994-skip") {
            Instant.parse("1994-12-30T12:00:00Z").toLocalDateTime(TimeZone.of("Pacific/Kiritimati")).toString()
        }
        p("datetime", "dst-gap-local-to-instant") {
            LocalDateTime.parse("2026-03-29T02:30:00").toInstant(TimeZone.of("Europe/Berlin")).toString()
        }
        p("datetime", "unknown-zone-message") { TimeZone.of("Mars/Olympus") }
        p("datetime", "instant-parse-offset") { Instant.parse("2026-09-11T00:00:00+03:00").toString() }
        p("datetime", "instant-parse-no-zone-message") { Instant.parse("2026-09-11T00:00:00") }
        p("datetime", "localdate-parse-bad-message") { LocalDate.parse("2026-13-40") }
        p("datetime", "leap-second-message") { Instant.parse("2016-12-31T23:59:60Z").toString() }
        p("datetime", "far-future-tostring") { Instant.parse("+12026-09-11T00:00:00Z").toString() }
    }

    @Test
    fun exceptionsAndMisc() {
        p("exception", "integer-division-by-zero") { 1 / listOf(0).first() }
        p("exception", "list-index-out-of-bounds") { listOf(1, 2, 3)[7] }
        p("exception", "string-index-out-of-bounds") { "abc"[7] }
        p("exception", "not-null-assertion") { (null as String?)!!.length }
        p("exception", "cast-failure") { (listOf<Any>("a").first() as Int) }
        p("exception", "first-on-empty") { emptyList<Int>().first() }
        p("exception", "map-getvalue-missing") { mapOf("a" to 1).getValue("b") }
        p("exception", "substring-bad-range") { "abc".substring(2, 1) }
        p("exception", "require-message") { require(false) { "nope" } }
        p("exception", "arraylist-negative-capacity") { ArrayList<Int>(-1) }
        p("random", "seeded-sequence") { Random(42).let { listOf(it.nextInt(), it.nextInt(), it.nextInt()) }.joinToString(",") }
        p("random", "seeded-double") { Random(42).nextDouble().toString() }
        p("random", "seeded-shuffle") { listOf(1, 2, 3, 4, 5, 6, 7, 8).shuffled(Random(42)).joinToString(",") }
    }
}
