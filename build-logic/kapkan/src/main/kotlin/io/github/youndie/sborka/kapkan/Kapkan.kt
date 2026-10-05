package io.github.youndie.sborka.kapkan

import com.pinterest.ktlint.cli.ruleset.core.api.RuleSetProviderV3
import com.pinterest.ktlint.rule.engine.core.api.Rule
import com.pinterest.ktlint.rule.engine.core.api.RuleId
import com.pinterest.ktlint.rule.engine.core.api.RuleProvider
import com.pinterest.ktlint.rule.engine.core.api.RuleSetId
import org.jetbrains.kotlin.com.intellij.lang.ASTNode
import org.jetbrains.kotlin.psi.KtFile

/**
 * The rule set id, and therefore the first half of every rule id and of every suppression.
 *
 * `@Suppress("ktlint:kapkan:wall-clock")` is the form, and the `ktlint:` in front of it is ktlint's:
 * its own `ktlint-suppression` rule refuses an id that names no loaded rule, which makes a
 * suppression the cheapest possible proof that this jar reached the worker at all.
 */
internal const val KAPKAN: String = "kapkan"

internal val ABOUT: Rule.About =
    Rule.About(
        maintainer = "youndie",
        repositoryUrl = "https://github.com/youndie/sborka",
        issueTrackerUrl = "https://github.com/youndie/sborka/issues",
    )

internal fun ruleId(name: String): RuleId = RuleId("$KAPKAN:$name")

/**
 * kapkan's rules, as ktlint loads them.
 *
 * Found through `META-INF/services`, which is why the class is public and its name is in a file
 * nobody reads twice. Seven rules, and the set is the whole configuration surface: a rule is here or
 * it is not.
 */
public class KapkanRuleSetProvider : RuleSetProviderV3(RuleSetId(KAPKAN)) {
    override fun getRuleProviders(): Set<RuleProvider> =
        setOf(
            RuleProvider { CancellationSwallowedRule() },
            RuleProvider { ForeignImportInCommonRule() },
            RuleProvider { NativeIdentifierRule() },
            RuleProvider { SignalHandlerInKotlinRule() },
            RuleProvider { SwallowedFailureRule() },
            RuleProvider { WallClockRule() },
            RuleProvider { SuppressionNeedsAReasonRule() },
        )
}

/**
 * The path of the file being linted, or `null` when there is no file.
 *
 * `virtualFilePath` is how ktlint's own `filename` rule reads it, so this is the tool's answer rather
 * than a guess about how it invokes the compiler's PSI. It is absent for a snippet linted from
 * stdin — and a rule that cannot tell which source set it is in must not guess one.
 */
internal fun ASTNode.filePath(): String? = (psi.containingFile as? KtFile)?.virtualFilePath

/**
 * The Kotlin source set a file belongs to, read off its path.
 *
 * `…/src/commonMain/kotlin/…` — the segment after `src`. ktlint-gradle also knows the source set,
 * because it registers one task per source set, but that knowledge does not reach a `Rule`: what
 * reaches a rule is the file.
 *
 * **Which `src`.** A path can hold more than one. A checkout under `~/src/` puts one in front of the
 * module's, which is why the search runs from the end; a package named `src` puts one behind it, and
 * the last `src` in `src/jvmTest/kotlin/x/src/FooTest.kt` made `FooTest.kt` the source set — unknown,
 * so judged, and a false finding in a JVM test. So the last `src` that has the layout around it wins:
 * `src/<set>/kotlin/` or `src/<set>/java/`. A layout without that directory falls back to the last
 * `src`, which is what every path got before.
 */
internal fun sourceSetOf(path: String): String? {
    val segments = path.replace('\\', '/').split('/')
    val roots = segments.indices.filter { segments[it] == "src" && it + 1 < segments.size }
    val src = roots.lastOrNull { segments.getOrNull(it + 2) in SOURCE_ROOTS } ?: roots.lastOrNull()
    return src?.let { segments[it + 1] }
}

/** The directory a Gradle source set keeps its Kotlin in, right below the set's own name. */
private val SOURCE_ROOTS: Set<String> = setOf("kotlin", "java")
