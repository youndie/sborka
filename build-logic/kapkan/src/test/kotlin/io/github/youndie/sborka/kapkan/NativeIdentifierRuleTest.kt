package io.github.youndie.sborka.kapkan

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * This file's own test names carry commas, and it lives in `src/test`, which sborka lints with
 * kapkan — so the half of the rule that leaves a JVM source set alone is exercised on every build of
 * this repository, not only by the cases below.
 */
class NativeIdentifierRuleTest {
    private fun test(name: String): String =
        """
        class NavigationGraphTest {
            @Test
            fun `$name`() {}
        }
        """.trimIndent() + "\n"

    private fun at(sourceSet: String) = "/repo/module/src/$sourceSet/kotlin/NavigationGraphTest.kt"

    @Test
    fun `a comma in a commonTest name is refused, and the message quotes the compiler`() {
        // kompot B-69's name, shortened. The message carries the compiler's own sentence, so the line
        // a person greps a red CI log for is the line this rule printed before the push.
        val errors = lint(test("a route says how it is shown, and a client shows it"), path = at("commonTest"))
        assertEquals(listOf("kapkan:native-identifier"), errors.ids())
        val error = errors.single()
        assertEquals(
            "Kotlin/Native refuses `a route says how it is shown, and a client shows it` in commonTest — " +
                "Name contains illegal characters: \",\" — and the JVM accepts it, so a JVM test run " +
                "stays green and the build that goes red is the native compile",
            error.detail,
        )
        assertEquals(3 to 9, error.line to error.col, "the finding points at the name, not at `fun`")
    }

    @Test
    fun `the same name in jvmTest is legal`() {
        // THE CONTROL: the text is identical and only the source set differs, so a rule that passes
        // this and fails the one above is reading the path.
        val errors = lint(test("a route says how it is shown, and a client shows it"), path = at("jvmTest"))
        assertEquals(emptyList<String>(), errors.ids())
    }

    @Test
    fun `no source set only the JVM or the web compiles is judged`() {
        listOf(
            "jvmTest",
            "jvmMain",
            "jvmSharedMain",
            "androidMain",
            "androidUnitTest",
            "androidHostTest",
            "androidInstrumentedTest",
            "desktopTest",
            "jsTest",
            "wasmJsTest",
            "test",
            "main",
            "testFixtures",
            "functionalTest",
            "integrationTest",
        ).forEach { sourceSet ->
            assertEquals(emptyList<String>(), lint(test("a, b"), path = at(sourceSet)).ids(), sourceSet)
        }
    }

    @Test
    fun `every source set a native target compiles is judged`() {
        // `androidNativeArm64Main` and `desktopNativeMain` start with a JVM word and are native: the
        // pair that a first-word match alone would get wrong.
        listOf(
            "commonMain",
            "commonTest",
            "nativeTest",
            "appleMain",
            "iosTest",
            "iosSimulatorArm64Test",
            "linuxX64Test",
            "mingwX64Main",
            "androidNativeArm64Main",
            "desktopNativeMain",
        ).forEach { sourceSet ->
            assertEquals(listOf("kapkan:native-identifier"), lint(test("a, b"), path = at(sourceSet)).ids(), sourceSet)
        }
    }

    @Test
    fun `a source set this rule does not know is judged`() {
        // A miss costs a red CI after the push, a false finding costs a rename — so an unknown name is
        // judged. `containerTest` is the price, named: bochka's own JVM suite, which only the JVM
        // compiles and this rule cannot tell from an intermediate set of a multiplatform module.
        listOf("nonJvmMain", "skikoMain", "containerTest").forEach { sourceSet ->
            assertEquals(listOf("kapkan:native-identifier"), lint(test("a, b"), path = at(sourceSet)).ids(), sourceSet)
        }
    }

    @Test
    fun `an apostrophe, a hyphen and a space are legal`() {
        val errors = lint(test("it's a well-formed name - and native takes it"), path = at("commonTest"))
        assertEquals(emptyList<String>(), errors.ids())
    }

    @Test
    fun `every character in the compiler's set is refused, and quoted`() {
        NativeIdentifierRule.ILLEGAL.forEach { c ->
            val errors = lint(test("a${c}b"), path = at("commonTest"))
            assertEquals(listOf("kapkan:native-identifier"), errors.ids(), "'$c'")
            assertTrue(errors.single().detail.contains("illegal characters: \"$c\""), errors.single().detail)
        }
    }

    @Test
    fun `several characters are quoted in the compiler's order, not the name's`() {
        // The compiler prints `invalidChars.intersect(name)`, which keeps ITS order: `,` before `(`.
        // Matching that is what makes this message and the CI log say the same string.
        val errors = lint(test("a (b, c)"), path = at("commonTest"))
        assertTrue(errors.single().detail.contains("illegal characters: \",()\""), errors.single().detail)
    }

    @Test
    fun `every kind of name the compiler checks is judged, not only functions`() {
        // FirNativeIdentifierChecker checks classes, functions, type parameters, properties, type
        // aliases, value parameters and enum entries; FirNativePackageDirectiveChecker each package
        // segment. One of each.
        val code =
            """
            package stand.`a,b`

            class `C,1`<`T,2`>(val `p,3`: Int)

            enum class E { `E,4` }

            typealias `A,5` = Int

            fun f(`x,6`: Int) {
                val `l,7` = x
            }
            """.trimIndent() + "\n"
        val errors = lint(code, path = at("commonMain"))
        assertEquals(
            listOf("a,b", "C,1", "T,2", "p,3", "E,4", "A,5", "x,6", "l,7"),
            errors.map { it.detail.substringAfter('`').substringBefore('`') },
        )
    }

    @Test
    fun `a suppression with a reason silences it`() {
        // The one honest suppression: `commonTest` of a module that has no native target, where the
        // name is legal today. It has to say so, which is what makes the day a target is added a diff.
        val code =
            """
            class T {
                @Suppress("ktlint:kapkan:native-identifier", "this module has no native target and is not getting one")
                fun `a, b`() {}
            }
            """.trimIndent() + "\n"
        assertEquals(emptyList<String>(), lint(code, path = at("commonTest")).ids())
    }

    @Test
    fun `a file outside any src directory is not judged`() {
        assertEquals(emptyList<String>(), lint(test("a, b"), path = "/repo/module/Scratch.kt").ids())
    }

    @Test
    fun `a package named src does not become the source set`() {
        // `src/jvmTest/kotlin/x/src/FooTest.kt`: the last `src` is a package, and reading the source
        // set after it gave `FooTest.kt` — an unknown name, judged, and a false finding in jvmTest.
        val inPackageSrc = "/repo/module/src/jvmTest/kotlin/x/src/NavigationGraphTest.kt"
        assertEquals(emptyList<String>(), lint(test("a, b"), path = inPackageSrc).ids())
        // The other side of the same path: in commonTest the finding stays, and names commonTest.
        val inCommon = "/repo/module/src/commonTest/kotlin/x/src/NavigationGraphTest.kt"
        val errors = lint(test("a, b"), path = inCommon)
        assertEquals(listOf("kapkan:native-identifier"), errors.ids())
        assertTrue(errors.single().detail.contains(" in commonTest — "), errors.single().detail)
    }

    @Test
    fun `a checkout under a directory named src still reads the module's source set`() {
        // What taking the LAST `src` was for: `~/src/<repo>/…` must not make `<repo>` the source set.
        val path = "/home/me/src/repo/module/src/jvmTest/kotlin/NavigationGraphTest.kt"
        assertEquals(emptyList<String>(), lint(test("a, b"), path = path).ids())
        assertEquals("jvmTest", sourceSetOf(path))
        // A layout with no `kotlin/` or `java/` under the set is read the way every path was before.
        assertEquals("jvmTest", sourceSetOf("/home/me/src/repo/module/src/jvmTest/NavigationGraphTest.kt"))
    }
}
