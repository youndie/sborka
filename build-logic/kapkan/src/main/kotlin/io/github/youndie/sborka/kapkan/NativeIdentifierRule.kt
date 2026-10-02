package io.github.youndie.sborka.kapkan

import com.pinterest.ktlint.rule.engine.core.api.AutocorrectDecision
import com.pinterest.ktlint.rule.engine.core.api.Rule
import com.pinterest.ktlint.rule.engine.core.api.RuleAutocorrectApproveHandler
import org.jetbrains.kotlin.com.intellij.lang.ASTNode
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.KtPackageDirective

/**
 * A name Kotlin/Native refuses, in a source set Kotlin/Native compiles.
 *
 * **What it catches.** A backticked name with a character the native compiler will not take —
 * almost always a comma in a test name:
 *
 * ```kotlin
 * @Test
 * fun `a route says how it is shown, and a client shows it`() { … }
 * ```
 *
 * The JVM compiles that. Kotlin/Native stops with `Name contains illegal characters: ","`. So in
 * `commonTest` a local `jvmTest` is green and CI goes red on `compileTestKotlinLinuxX64` or
 * `compileTestKotlinIosArm64` — the one compile nobody runs before pushing.
 *
 * **Where it was found.** Three times, in telek B-01 and B-02 and in kompot B-69 (30.09.2026,
 * `kompot-navigation/src/commonTest`), while the rule was written down as a sentence in two skills
 * and five repositories' `CLAUDE.md`. A sentence did not stop the third one; this is the sentence
 * moved into the build. kompot has no `linuxX64` target at all, so there the only compile that
 * refuses the name is iOS's, on a macOS runner — and this rule is the cheaper check only in a module
 * that applies `sborka.lint`. kompot's modules did not when the rule was written; since
 * youndie/kompot#199 (2026-10-02) every one of them does (`docs/kapkan.md` §12).
 *
 * **The set is the compiler's, copied rather than remembered.** [ILLEGAL] is
 * `FirNativeIdentifierChecker.invalidChars` in JetBrains/kotlin at `v2.4.20`,
 * `compiler/fir/checkers/checkers.native/src/org/jetbrains/kotlin/fir/analysis/native/checkers/FirNativeIdentifierChecker.kt`
 * lines 21–24, in the same order — the order the compiler prints them in, so this message and the
 * compiler's quote the same string. The K1 checker,
 * `native/frontend/src/org/jetbrains/kotlin/resolve/konan/diagnostics/NativeIdentifierChecker.kt`
 * lines 23–26, carries the identical set. A space, an apostrophe and a hyphen are NOT in it, and
 * `it's a well-formed name` compiles everywhere.
 *
 * **Every name, not only functions, because the compiler checks every name**: the same file, lines
 * 33–39, runs the check on classes and objects, functions, type parameters, properties, type
 * aliases, value parameters and enum entries, and `FirNativePackageDirectiveChecker` on each segment
 * of a package directive. A test name is where people hit it; a backticked property in a test
 * fixture fails the same compile.
 *
 * **Which source sets.** Read off the path, the way [ForeignImportInCommonRule] reads it, with the
 * finite list the other way round: what is known NOT to reach Kotlin/Native is short — see
 * [compiledForNative] — and everything else is judged. `commonMain`, `commonTest`, `nativeTest`,
 * `iosMain`, `linuxX64Test`, `appleMain` are judged; `jvmTest`, `androidUnitTest`,
 * `androidHostTest`, `desktopTest`, `jsTest`, `wasmJsTest` and a plain JVM project's `src/test` are
 * not, because the JVM and the web backends take the same name.
 *
 * **A source-set name this rule does not know is judged.** `nonJvmMain`, `skikoMain`, a native
 * target given a name of its own — any of them may compile for native, and the two ways to be wrong
 * do not cost the same: a false finding costs renaming one test, a miss costs a red CI after the
 * push. The price lands in two places. A plain JVM project's own suite under a name of its own —
 * bochka's `src/containerTest` — is judged although only the JVM compiles it. And `commonTest` of a
 * module with no native target is judged, where the name is legal today and stops compiling the
 * day a native target is added: shashki's two clients (wasmJs and a desktop JVM) carried 13 such
 * names when the rule was written, the cost of taking it there, and youndie/shashki#35 (2026-10-02)
 * paid it with 13 renames on taking 0.5.0. Both answer with a rename, or with a suppression that
 * says which case it is. The count over the portfolio is in `docs/kapkan.md` §12.
 *
 * **The source set is the segment after the module's `src`** — see [sourceSetOf] for which `src`
 * that is when a path holds more than one.
 *
 * **It runs where ktlint runs**: `ktlintCheck`, and `check` through it — not `jvmTest`. A module
 * that does not apply `sborka.lint` does not get it at all.
 */
public class NativeIdentifierRule :
    Rule(
        ruleId = ruleId("native-identifier"),
        about = ABOUT,
    ),
    RuleAutocorrectApproveHandler {
    override fun beforeVisitChildNodes(
        node: ASTNode,
        emit: (Int, String, Boolean) -> AutocorrectDecision,
    ) {
        // THE SOURCE SET IS DECIDED ON THE FILE NODE, and a file the native compiler never sees is
        // not walked at all — most of a JVM repository's files, which is most of the portfolio.
        if (node.treeParent == null) {
            val sourceSet = node.filePath()?.let(::sourceSetOf)
            if (sourceSet == null || !compiledForNative(sourceSet)) stopTraversalOfAST()
            return
        }
        when (val psi = node.psi) {
            is KtNamedDeclaration -> psi.nameIdentifier?.let { judge(psi.name, it.node, emit) }
            is KtPackageDirective -> psi.packageNames.forEach { judge(it.getReferencedName(), it.node, emit) }
            else -> Unit
        }
    }

    private fun judge(
        name: String?,
        at: ASTNode,
        emit: (Int, String, Boolean) -> AutocorrectDecision,
    ) {
        if (name == null) return
        val illegal = ILLEGAL.filter { it in name }
        if (illegal.isEmpty()) return
        val sourceSet = at.filePath()?.let(::sourceSetOf) ?: return
        emit(
            at.startOffset,
            "Kotlin/Native refuses `$name` in $sourceSet — Name contains illegal characters: " +
                "\"$illegal\" — and the JVM accepts it, so a JVM test run stays green and the build " +
                "that goes red is the native compile",
            false,
        )
    }

    internal companion object {
        /**
         * `FirNativeIdentifierChecker.invalidChars` at Kotlin `v2.4.20`, lines 21–24, in its order.
         * The compiler's comment on it: "Also includes characters used by IR mangler (see
         * MangleConstant)" — which is why it is wider than the JVM's list and why a comma is in it.
         */
        const val ILLEGAL: String = ".;,()[]{}/<>:\\\$&~*?#|§%@"

        /**
         * First words of a source-set name that only the JVM, Android or the web backends compile.
         *
         *  * `jvm`, `android` — Kotlin's JVM and Android targets: `jvmTest`, `androidMain`,
         *    `androidUnitTest`, `androidInstrumentedTest`, and `androidHostTest` /
         *    `androidDeviceTest` from the Android multiplatform library plugin; also an
         *    intermediate set that starts with one of them, `jvmSharedMain`.
         *  * `desktop` — not Kotlin's name but Compose Multiplatform's: `jvm("desktop")` is how its
         *    template names the JVM target, and the portfolio's desktop clients kept the name.
         *  * `js`, `wasm`, `web` — the web targets. The JS compiler has a check of its own, on the
         *    names it has to keep stable for JavaScript, and this rule is not that check.
         *  * `main`, `test` — a plain JVM or Android project: `src/main`, `src/test`,
         *    `src/testFixtures`. `debug` and `release` are Android's build types in such a project.
         *  * `functional`, `integration` — the two suite names Gradle itself hands a JVM project:
         *    `gradle init` generates `src/functionalTest` for a plugin, and `integrationTest` is the
         *    JVM Test Suite plugin's own example. zavarnik's plugin has 12 comma names in
         *    `src/functionalTest`, every one legal — see `docs/kapkan.md` §12.
         */
        val NOT_NATIVE: Set<String> =
            setOf(
                "jvm",
                "android",
                "desktop",
                "js",
                "wasm",
                "web",
                "main",
                "test",
                "functional",
                "integration",
                "debug",
                "release",
            )

        /**
         * Words that put a source set on a native target wherever they stand in its name — which is
         * what keeps `androidNativeArm64Main` and `desktopNativeMain` (tracy's) from being read as
         * Android and desktop by their first word.
         */
        val NATIVE: Set<String> = setOf("native", "apple", "ios", "macos", "tvos", "watchos", "linux", "mingw")

        private val CAMEL = Regex("(?<=[a-z0-9])(?=[A-Z])")

        /**
         * Whether Kotlin/Native compiles this source set, or might: no for a name that starts with a
         * JVM or web word and has no native word in it, yes for everything else, unknown names
         * included.
         */
        fun compiledForNative(sourceSet: String): Boolean {
            val words = sourceSet.split(CAMEL).map { it.lowercase() }
            if (words.any { it in NATIVE }) return true
            return words.first() !in NOT_NATIVE
        }
    }
}
