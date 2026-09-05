package io.github.youndie.sborka.kapkan

import com.pinterest.ktlint.rule.engine.core.api.AutocorrectDecision
import com.pinterest.ktlint.rule.engine.core.api.Rule
import com.pinterest.ktlint.rule.engine.core.api.RuleAutocorrectApproveHandler
import org.jetbrains.kotlin.com.intellij.lang.ASTNode
import org.jetbrains.kotlin.psi.KtAnnotationEntry
import org.jetbrains.kotlin.psi.KtStringTemplateExpression

/**
 * A kapkan rule turned off without saying why.
 *
 * **The whole of kapkan's configuration surface is this annotation**, and that is the design: there
 * is no `kapkan.yml`, no severity and no threshold, because every one of those is a place to disable
 * a rule where nobody reads the diff. What is left is one form:
 *
 * ```kotlin
 * @Suppress("ktlint:kapkan:wall-clock", "this is the Clock port; everything else reads it")
 * ```
 *
 * The rules ktlint runs are suppressed by `ktlint:kapkan:<rule>`; `kapkan:joined-at-neither-end`,
 * which is a Gradle task rather than a ktlint rule, is suppressed without ktlint's prefix — see
 * `PREFIXES`.
 *
 * **`@Suppress` and not an annotation of our own**, which is what the brief asked for. Three reasons,
 * measured rather than preferred:
 *
 *  * `@Kapkan.Allow` in a `commonMain` source set needs a multiplatform artefact on the compile
 *    classpath of every module of every repository — jvm, wasmJs, android, three iOS — with a release
 *    cycle of its own. `@Suppress` is in the standard library and is already everywhere.
 *  * ktlint validates the first string itself: its `ktlint-suppression` rule refuses an id that names
 *    no loaded rule. A typo fails, and — more useful — a build where this jar never reached the
 *    worker fails too, on the suppression rather than on silence.
 *  * the compiler ignores an unknown `@Suppress` name, so the second string costs nothing and is
 *    visible in the diff, which a comment is not: shashki paid for that three times (B-12, B-20).
 *
 * **What it cannot do.** A suppression of this rule's own id turns the guard off. That is not worth
 * defending against — a rule cannot outrank its own name — and it is one line in a diff.
 */
public class SuppressionNeedsAReasonRule :
    Rule(
        ruleId = ruleId("suppression-needs-a-reason"),
        about = ABOUT,
    ),
    RuleAutocorrectApproveHandler {
    override fun beforeVisitChildNodes(
        node: ASTNode,
        emit: (Int, String, Boolean) -> AutocorrectDecision,
    ) {
        val entry = node.psi as? KtAnnotationEntry ?: return
        if (entry.shortName?.asString() !in SUPPRESS) return

        val values =
            entry.valueArguments.mapNotNull { argument ->
                (argument.getArgumentExpression() as? KtStringTemplateExpression)
                    ?.entries
                    ?.singleOrNull()
                    ?.text
            }
        val suppressed = values.filter { value -> PREFIXES.any { value.startsWith(it) } }
        if (suppressed.isEmpty()) return

        val reason = values.firstOrNull { it !in suppressed && isReason(it) }
        if (reason != null) return

        emit(
            entry.node.startOffset,
            "${suppressed.joinToString()} is switched off here and the annotation does not say why — " +
                "add the reason as a second string, in words somebody who has not read this file can " +
                "act on",
            false,
        )
    }

    private fun isReason(value: String): Boolean {
        // NOT A LENGTH, A SENTENCE. Three words is what separates a reason from a label: `legacy`,
        // `needed` and `UNCHECKED_CAST` — the other things that turn up inside a `@Suppress` — are
        // one word each, and no explanation of anything is.
        val words = value.trim().split(WHITESPACE).filter { it.isNotBlank() }
        if (words.size < MINIMUM_WORDS) return false
        if (value.startsWith("ktlint:")) return false
        return value.trim().lowercase() !in STOP_PHRASES
    }

    private companion object {
        val SUPPRESS = setOf("Suppress", "SuppressWarnings")

        /**
         * TWO PREFIXES, AND THE SECOND ONE IS NOT A TYPO. `ktlint:kapkan:…` names a rule ktlint runs,
         * and ktlint validates it against the rules it loaded. `kapkan:…` names one it does not:
         * `joined-at-neither-end` is a Gradle task reading class files, so a `ktlint:` prefix on it
         * would fail every build that used it with "unknown or not loaded". Both still have to say
         * why, which is the whole of this rule's job.
         */
        val PREFIXES = listOf("ktlint:$KAPKAN:", "$KAPKAN:")
        const val MINIMUM_WORDS = 3
        val WHITESPACE = Regex("""\s+""")

        /**
         * The sentences that are three words long and still say nothing. Written down rather than
         * left to judgement, because the point of the reason is that the next person can act on it,
         * and "we need this" is what gets typed when there is nothing to say.
         */
        val STOP_PHRASES =
            setOf(
                "we need this",
                "it is needed",
                "this is needed",
                "needed for now",
                "for now only",
                "legacy code here",
                "to be fixed",
                "will fix later",
                "fix this later",
                "no reason given",
            )
    }
}
