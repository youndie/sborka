package io.github.youndie.sborka.internal

import java.io.DataInputStream
import java.io.File

/**
 * As much of a class file as the joins report needs, and not one field more.
 *
 * WHAT IT READS: the constant pool, and out of it every class name the file mentions; then
 * `this_class`, which sits immediately after the pool. Nothing beyond that is parsed — no fields, no
 * methods, no attributes — which is why this is sixty lines rather than a library.
 *
 * THE CONSTANT POOL IS THE WHOLE REFERENCE GRAPH. Every type a class touches has a `CONSTANT_Class`
 * entry, and the owner of every method or field it calls is one of them. So "does anything mention
 * this class" is answered by a set union, without loading a single class or resolving anything.
 *
 * WHAT IT CANNOT SEE, and both are written down in `Joins` rather than discovered later: an `inline`
 * function leaves no reference behind, because its body is copied into the caller; and a `const val`
 * is folded into the call site, so the object holding it is never named.
 */
internal object ClassFile {
    private const val MAGIC = -0x35014542 // 0xCAFEBABE as a signed Int

    private const val UTF8 = 1
    private const val INTEGER = 3
    private const val FLOAT = 4
    private const val LONG = 5
    private const val DOUBLE = 6
    private const val CLASS = 7
    private const val STRING = 8
    private const val FIELD_REF = 9
    private const val METHOD_REF = 10
    private const val INTERFACE_METHOD_REF = 11
    private const val NAME_AND_TYPE = 12
    private const val METHOD_HANDLE = 15
    private const val METHOD_TYPE = 16
    private const val DYNAMIC = 17
    private const val INVOKE_DYNAMIC = 18
    private const val MODULE = 19
    private const val PACKAGE = 20

    /**
     * The class this file declares, every class name it names, and every method it calls.
     *
     * `calls` is `owner.name` — no descriptor, so an overload is one entry. The question being asked
     * is "did anything call this function at all", and telling two overloads apart would answer a
     * question nobody has while making the source side of the comparison need signatures.
     *
     * AND THE NAME IS THE ONE THE SOURCE WROTE, which is not the one the pool holds. Kotlin mangles
     * the JVM name of any function whose signature mentions a value class — `drawTileRoads` is
     * `drawTileRoads-Rg1IO4c` in the bytecode — and appends `$default`, `$lambda$…` and a module hash
     * to others. Comparing raw names against a source declaration therefore reports every function
     * that takes a `Color`, a `Dp` or a `Duration` as called by nobody, which on shashki was five of
     * the first six findings and every one of them wrong.
     */
    data class Parsed(
        val name: String,
        val supertypes: Set<String>,
        val mentions: Set<String>,
        val calls: Set<String>,
    )

    fun parse(file: File): Parsed? =
        DataInputStream(file.inputStream().buffered()).use { input ->
            if (input.readInt() != MAGIC) return null
            input.readUnsignedShort() // minor
            input.readUnsignedShort() // major

            val poolCount = input.readUnsignedShort()
            val utf8 = HashMap<Int, String>()
            val classNameIndex = HashMap<Int, Int>()
            val nameOfNameAndType = HashMap<Int, Int>()
            val methodRefs = ArrayList<Pair<Int, Int>>()

            var index = 1
            while (index < poolCount) {
                when (val tag = input.readUnsignedByte()) {
                    UTF8 -> {
                        utf8[index] = input.readUTF()
                    }

                    CLASS -> {
                        classNameIndex[index] = input.readUnsignedShort()
                    }

                    STRING, METHOD_TYPE, MODULE, PACKAGE -> {
                        input.skipBytes(2)
                    }

                    METHOD_REF, INTERFACE_METHOD_REF -> {
                        methodRefs += input.readUnsignedShort() to input.readUnsignedShort()
                    }

                    NAME_AND_TYPE -> {
                        nameOfNameAndType[index] = input.readUnsignedShort()
                        input.skipBytes(2) // the descriptor, which this does not need
                    }

                    INTEGER, FLOAT, FIELD_REF, DYNAMIC, INVOKE_DYNAMIC -> {
                        input.skipBytes(4)
                    }

                    METHOD_HANDLE -> {
                        input.skipBytes(3)
                    }

                    // A long or a double TAKES TWO POOL SLOTS. The spec calls this a historical
                    // mistake; a reader that forgets it walks off the end of the pool and reads
                    // rubbish as tags, which fails as a parse error somewhere far from here.
                    LONG, DOUBLE -> {
                        input.skipBytes(8)
                        index++
                    }

                    else -> {
                        return null
                    } // a tag from a class file version this does not know
                }
                index++
            }

            input.readUnsignedShort() // access_flags
            val thisClass = input.readUnsignedShort()
            val name = classNameIndex[thisClass]?.let { utf8[it] } ?: return null

            // THE SUPERTYPES, and they are read for one reason: a call is compiled against the type
            // the caller holds. `viewport.toGeo(…)` on a `MapViewport` names `MapViewport.toGeo` even
            // though `toGeo` is declared on the `Projection` it implements — so without the hierarchy
            // every interface function called through an implementation is a finding.
            fun classAt(index: Int) = classNameIndex[index]?.let { utf8[it] }?.let(::binaryToDotted)

            val superClass = classAt(input.readUnsignedShort())
            val interfaceCount = input.readUnsignedShort()
            val interfaces = (0 until interfaceCount).mapNotNull { classAt(input.readUnsignedShort()) }

            Parsed(
                name = binaryToDotted(name),
                supertypes = (listOfNotNull(superClass) + interfaces).toSet(),
                mentions =
                    classNameIndex.values
                        .mapNotNull { utf8[it] }
                        .map(::binaryToDotted)
                        .toSet(),
                calls =
                    methodRefs
                        .mapNotNull { (classIndex, nameAndTypeIndex) ->
                            val owner = classNameIndex[classIndex]?.let { utf8[it] } ?: return@mapNotNull null
                            val member = nameOfNameAndType[nameAndTypeIndex]?.let { utf8[it] } ?: return@mapNotNull null
                            "${binaryToDotted(owner)}.${sourceName(member)}"
                        }.toSet(),
            )
        }

    /**
     * The name as the source wrote it: everything before the compiler's own suffix.
     *
     * Neither `-` nor `$` can appear in a Kotlin identifier, so cutting at the first of either is
     * exact rather than a heuristic.
     */
    private fun sourceName(member: String): String = member.substringBefore('-').substringBefore('$')

    /**
     * `a/b/C` and `[[La/b/C;` both name `a.b.C`.
     *
     * An array type appears in the pool in descriptor form, and reading it as a name would file every
     * reference through an array under a class that does not exist.
     */
    private fun binaryToDotted(raw: String): String =
        raw
            .trimStart('[')
            .removePrefix("L")
            .removeSuffix(";")
            .replace('/', '.')
}
