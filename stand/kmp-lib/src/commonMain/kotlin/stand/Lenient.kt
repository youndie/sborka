package stand

/** A codec for one field, typed by what the field holds. */
public fun interface FieldEncoder<T> {
    public fun encode(value: T): String
}

/**
 * An encoder for a list field that never looks at the list. Its parameter is still typed, so the
 * value it receives is cast to `List<String>` on the way in — in debug.
 */
public val listFieldEncoder: FieldEncoder<List<String>> = FieldEncoder { _ -> "encoded as a list" }

/**
 * Mongkn M-91's lenient path, cut down: try the field's encoder, and fall back on a
 * [ClassCastException] when the value is not of the field's type.
 *
 * Correct on the JVM and on a debug Kotlin/Native binary. On a release binary the cast out of the
 * generic parameter is not checked (`genericSafeCasts` is off there), nothing is thrown, the fallback
 * never runs, and the field's encoder answers for a value it was never meant to see.
 */
public fun encodeLeniently(
    encoder: FieldEncoder<*>,
    value: Any,
): String =
    try {
        @Suppress("UNCHECKED_CAST", "the cast out of the star projection is the point of the fixture")
        (encoder as FieldEncoder<Any>).encode(value)
    } catch (mismatch: ClassCastException) {
        "fallback for $value"
    }
