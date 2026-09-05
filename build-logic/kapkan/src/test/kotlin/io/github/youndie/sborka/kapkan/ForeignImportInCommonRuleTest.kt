package io.github.youndie.sborka.kapkan

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ForeignImportInCommonRuleTest {
    @Test
    fun `a java import in commonMain is a defect`() {
        val errors = lint("import java.io.File\n\nval f: File? = null\n")
        assertEquals(listOf("kapkan:foreign-import-in-common"), errors.ids())
    }

    @Test
    fun `the same import in jvmMain is not`() {
        // THE CONTROL, and it is the whole design of the rule: the import is identical and only the
        // source set differs, so a rule that passes this and fails the one above is reading the path
        // rather than the text.
        val errors =
            lint(
                "import java.io.File\n\nval f: File? = null\n",
                path = "/repo/module/src/jvmMain/kotlin/Sample.kt",
            )
        assertEquals(emptyList<String>(), errors.ids())
    }

    @Test
    fun `commonTest is common too`() {
        val errors =
            lint(
                "import java.io.File\n\nval f: File? = null\n",
                path = "/repo/module/src/commonTest/kotlin/SampleTest.kt",
            )
        assertEquals(listOf("kapkan:foreign-import-in-common"), errors.ids())
    }

    @Test
    fun `androidx is not android`() {
        // `androidx.lifecycle` and `androidx.navigation3` are multiplatform artefacts under an
        // android-shaped name, and shashki's commonMain imports them correctly. The dot in
        // "android." is what separates them, and this is the test that says so.
        val errors = lint("import androidx.lifecycle.ViewModel\n\nval v: ViewModel? = null\n")
        assertEquals(emptyList<String>(), errors.ids())
    }

    @Test
    fun `a kotlinx import is not foreign`() {
        val errors = lint("import kotlinx.coroutines.launch\n")
        assertEquals(emptyList<String>(), errors.ids())
    }

    @Test
    fun `a file outside any src directory is not judged`() {
        // A rule that cannot tell which source set it is in must not guess one: a build script, a
        // generated file under `build/`, a snippet from stdin.
        val errors = lint("import java.io.File\n", path = "/repo/module/build.gradle.kts")
        assertEquals(emptyList<String>(), errors.ids())
    }
}
