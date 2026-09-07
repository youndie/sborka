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
 * THE POOL WALK ITSELF IS IN `ConstantPool`, shared with `MethodSizes`.
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

            val pool = ConstantPool.read(input) ?: return null
            val utf8 = pool.utf8
            val classNameIndex = pool.classNameIndex

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
                    pool.methodRefs.values
                        .mapNotNull { reference ->
                            val owner = classNameIndex[reference.classIndex]?.let { utf8[it] } ?: return@mapNotNull null
                            val memberIndex =
                                pool.nameOfNameAndType[reference.nameAndTypeIndex] ?: return@mapNotNull null
                            val member = utf8[memberIndex] ?: return@mapNotNull null
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
