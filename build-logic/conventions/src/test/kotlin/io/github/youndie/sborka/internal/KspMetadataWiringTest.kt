package io.github.youndie.sborka.internal

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The string matching the wiring stands on, against task and configuration names taken from a real
 * build rather than invented.
 *
 * The names below were printed out of `youndie/kompot` — `:kompot-forms`, which processes common
 * metadata, and `:kompot-ds-material-compose`, which applies the same KSP plugin for a screenshot
 * processor over `desktopTest` and must come out of this untouched. They are the two shapes the
 * convention has to tell apart, and both exist today.
 *
 * Worth testing at all because every failure here is quiet. A name shape that stops matching gives
 * back the race the list exists to prevent — a publish failing on a file that does exist, in one run
 * out of several — and a name shape that matches too much switches off a processor and leaves a
 * green build with nothing generated.
 */
class KspMetadataWiringTest {
    @Test
    fun `what reads the generated sources waits for them`() {
        listOf(
            "compileKotlinJvm",
            "compileCommonMainKotlinMetadata",
            "compileKotlinIosArm64",
            "dokkaGeneratePublicationHtml",
            "dokkaGenerateModuleHtml",
            "jvmSourcesJar",
            "wasmJsSourcesJar",
            "runKtlintCheckOverCommonMainSourceSet",
            "ktlintCommonMainSourceSetCheck",
        ).forEach {
            assertTrue(KspMetadataWiring.readsGeneratedSources(it), "$it should be made to wait for KSP")
        }
    }

    @Test
    fun `dokka is on the list because leaving it off cost a flake`() {
        // Named on its own rather than inside the sweep above: dokka was the third kind of consumer,
        // found only after `FileNotFoundException` on a file that does exist showed up in a publish
        // step once in every few runs. A change that drops this line looks harmless and gives the
        // flake back.
        assertTrue(KspMetadataWiring.readsGeneratedSources("dokkaGeneratePublicationHtml"))
    }

    @Test
    fun `ktlint is on the list because the stand refused to build without it`() {
        // The fourth kind, and the one the seven hand-written copies could never have shown: the
        // repository they came from applies no ktlint at all. Gradle does not race here, it refuses —
        // a ktlint task declares the source set as an input, so the generated directory is an
        // undeclared dependency and the build stops with the task named. The `/build/generated/`
        // exclusion `sborka.lint` carries does not help: it filters what is checked, not what is
        // declared.
        assertTrue(KspMetadataWiring.readsGeneratedSources("runKtlintCheckOverCommonMainSourceSet"))
        assertTrue(KspMetadataWiring.readsGeneratedSources("runKtlintFormatOverCommonMainSourceSet"))
    }

    @Test
    fun `the generator does not wait for itself`() {
        // It would match `readsGeneratedSources` on no other rule than its name starting with a
        // letter — the guard is the explicit exclusion, and Gradle's answer to losing it is a
        // circular dependency between the task and itself.
        assertFalse(KspMetadataWiring.readsGeneratedSources(KspMetadataWiring.GENERATOR))
        assertFalse(KspMetadataWiring.disabledAsRedundant(KspMetadataWiring.GENERATOR))
    }

    @Test
    fun `the per-target tasks the plugin registers are the redundant ones`() {
        listOf(
            "kspKotlinJvm",
            "kspTestKotlinJvm",
            "kspKotlinWasmJs",
            "kspKotlinIosArm64",
            "kspAndroidMain",
            "kspAndroidHostTest",
        ).forEach {
            assertTrue(KspMetadataWiring.disabledAsRedundant(it), "$it has no processor of its own")
        }
        assertFalse(KspMetadataWiring.disabledAsRedundant("compileKotlinJvm"))
    }

    @Test
    fun `a module that only processes common metadata trips nothing`() {
        // Every `ksp*` configuration carrying a dependency in `:kompot-forms`. The two classpath ones
        // are KSP's own plumbing and exist in every module that applies the plugin; a guard that
        // counted them would fire everywhere and be deleted the same week.
        val kompotForms =
            listOf(
                "kspCommonMainMetadata",
                "kspPluginClasspath",
                "kspPluginClasspathNonEmbeddable",
            )
        assertEquals(emptyList<String>(), KspMetadataWiring.processorsOutsideCommonMetadata(kompotForms))
    }

    @Test
    fun `a processor on a platform source set is named, not ignored`() {
        // `:kompot-ds-material-compose`: a screenshot processor on `desktopTest`, nothing on common
        // metadata. If such a module ever also took a common-metadata processor, the wiring would
        // switch off the task this one needs — so it is named and the build stops.
        val both =
            listOf(
                "kspCommonMainMetadata",
                "kspDesktopTest",
                "kspPluginClasspath",
                "kspTestKotlinDesktopProcessorClasspath",
            )
        assertEquals(listOf("kspDesktopTest"), KspMetadataWiring.processorsOutsideCommonMetadata(both))
    }

    @Test
    fun `configurations that are not KSP's are not the wiring's business`() {
        val ordinary = listOf("implementation", "commonMainApi", "ktlintRuleset")
        assertEquals(emptyList<String>(), KspMetadataWiring.processorsOutsideCommonMetadata(ordinary))
    }
}
