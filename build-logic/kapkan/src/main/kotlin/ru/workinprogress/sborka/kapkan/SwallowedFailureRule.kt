package ru.workinprogress.sborka.kapkan

import com.pinterest.ktlint.rule.engine.core.api.AutocorrectDecision
import com.pinterest.ktlint.rule.engine.core.api.Rule
import com.pinterest.ktlint.rule.engine.core.api.RuleAutocorrectApproveHandler
import org.jetbrains.kotlin.com.intellij.lang.ASTNode
import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.psi.KtBlockExpression
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtCatchClause
import org.jetbrains.kotlin.psi.KtClassInitializer
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtFinallySection
import org.jetbrains.kotlin.psi.KtFunctionLiteral
import org.jetbrains.kotlin.psi.KtLambdaArgument
import org.jetbrains.kotlin.psi.KtLambdaExpression
import org.jetbrains.kotlin.psi.KtLoopExpression
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtPostfixExpression
import org.jetbrains.kotlin.psi.KtPropertyAccessor
import org.jetbrains.kotlin.psi.KtQualifiedExpression
import org.jetbrains.kotlin.psi.KtSecondaryConstructor
import org.jetbrains.kotlin.psi.KtValueArgument

/**
 * A failure that happened and that nothing can find out about.
 *
 * **What it catches.** Two shapes, and both are exact rather than heuristic:
 *
 *  * a `runCatching { … }` whose failure NOTHING READS — the chain applied to it never mentions
 *    `onFailure`, `getOrElse`, `fold` or their neighbours, and what is left is thrown away by the
 *    language: the last statement of a block body, of a `finally`, of a loop, of an initializer, or
 *    a statement that is not the last one at all;
 *  * `catch (e: Exception)` / `catch (e: Throwable)` whose body never mentions `e`.
 *
 * **Where it was found.** shashki B-39. `ReportingDegradationSink` set no content type of its own and
 * relied on the application client's `defaultRequest`; with any other client the report failed to
 * serialise, and the sink's own `runCatching` swallowed that. The defect was the content type. What
 * made it invisible — and therefore what would have shipped — was the swallow.
 *
 * **What it does not catch, on purpose.** `runCatching { … }.getOrNull()` where nothing is logged.
 * Whether a failure is reported is a question about the whole function, not about the expression, and
 * a proximity search for the word `log` is the kind of guess that produces the ten false positives
 * this tool refuses to have. Measured on shashki, that shape fires four times and all four are
 * correct code.
 *
 * **The suppressions this produces are the point, not a defect.** `finally { runCatching {
 * session.close() } }` is right, and so is a worker that must not die on one bad message. The rule
 * asks each of them to say so once.
 */
public class SwallowedFailureRule :
    Rule(
        ruleId = ruleId("swallowed-failure"),
        about = ABOUT,
    ),
    RuleAutocorrectApproveHandler {
    override fun beforeVisitChildNodes(
        node: ASTNode,
        emit: (Int, String, Boolean) -> AutocorrectDecision,
    ) {
        when (val psi = node.psi) {
            is KtCallExpression -> visitRunCatching(psi, emit)
            is KtCatchClause -> visitCatch(psi, emit)
            else -> Unit
        }
    }

    private fun visitRunCatching(
        call: KtCallExpression,
        emit: (Int, String, Boolean) -> AutocorrectDecision,
    ) {
        if (call.calleeExpression?.text != "runCatching") return
        val chain = chainOf(call)
        if (chain.any { it in READS_THE_FAILURE }) return
        if (!discards(outermost(call))) return
        emit(
            call.node.startOffset,
            "the Result of this runCatching is discarded, so a failure inside it happened and nobody " +
                "can find out — read it, or say in a @Suppress why nobody needs to",
            false,
        )
    }

    private fun visitCatch(
        clause: KtCatchClause,
        emit: (Int, String, Boolean) -> AutocorrectDecision,
    ) {
        val parameter = clause.catchParameter ?: return
        val name = parameter.name ?: return
        // A SPECIFIC TYPE IS A DECISION. `catch (e: SerializationException)` names what it expects and
        // is answerable for it; `catch (e: Exception)` catches everything there is, which is only ever
        // right when something is done with what was caught.
        val caught = parameter.typeReference?.text?.substringAfterLast('.') ?: return
        if (caught != "Exception" && caught != "Throwable") return
        val body = clause.catchBody ?: return
        if (mentions(body, name)) return
        emit(
            clause.node.startOffset,
            "this catches every $caught and never looks at $name — the failure is gone, and the log " +
                "that would have named it does not exist",
            false,
        )
    }

    /**
     * The names applied to this `runCatching`, in order — `onFailure`, `getOrElse`, `map`.
     *
     * WHETHER THE FAILURE IS READ IS A DIFFERENT QUESTION FROM WHETHER THE VALUE IS USED, and the
     * first version of this rule asked the second one. `runCatching { … }.onFailure { log.warn(…) }`
     * as a statement discards a `Result` and reports the failure, which is correct code; the first
     * consumer to run this rule had five of them and four real findings, so the mistake was the
     * majority of what the rule said.
     */
    private fun chainOf(call: KtCallExpression): List<String> {
        val names = mutableListOf<String>()
        var expression: KtExpression = call
        while (true) {
            val parent = expression.parent
            if (parent !is KtQualifiedExpression || parent.receiverExpression !== expression) return names
            val selector = parent.selectorExpression
            names +=
                when (selector) {
                    is KtCallExpression -> selector.calleeExpression?.text.orEmpty()
                    else -> selector?.text.orEmpty()
                }
            expression = parent
        }
    }

    /**
     * The outermost expression this call is the value of.
     *
     * `runCatching { … }.getOrNull()` is one expression as far as "is the value used" is concerned,
     * and asking about the call alone would answer that its value goes to `getOrNull` and stop there.
     */
    private fun outermost(call: KtCallExpression): KtExpression {
        var expression: KtExpression = call
        while (true) {
            val parent = expression.parent
            expression =
                when {
                    parent is KtQualifiedExpression && parent.receiverExpression === expression -> parent
                    parent is KtPostfixExpression -> parent
                    else -> return expression
                }
        }
    }

    /**
     * Whether the language throws this expression's value away — decided by structure, with no types.
     *
     * A block body, a `finally`, a loop body, an initializer and a property accessor's block all
     * discard their last expression: that is what `{ }` means on a `fun`, as opposed to `=`. A
     * statement that is not the last one in its block is discarded too, whatever the block belongs to.
     *
     * A LAMBDA'S LAST EXPRESSION IS ITS VALUE, so it is not discarded here — with one exception, and
     * `launch` is the whole of it. Naming a function is the one place this rule reads a name rather
     * than a shape, and it is the place B-39 lived: `scope.launch { runCatching { … } }` drops the
     * Result on the floor, because `launch`'s block is a `suspend () -> Unit` and returns a `Job`
     * that knows nothing about it. **`async` is deliberately not in the list**: its lambda's value
     * IS the `Deferred`'s value, so `async { runCatching { … } }` discards nothing.
     */
    private fun discards(expression: KtExpression): Boolean {
        val block = expression.parent as? KtBlockExpression ?: return false
        if (block.statements.lastOrNull() !== expression) return true
        return when (val owner = block.parent) {
            is KtNamedFunction, is KtPropertyAccessor, is KtClassInitializer,
            is KtSecondaryConstructor, is KtFinallySection, is KtLoopExpression,
            -> true

            is KtFunctionLiteral -> isUnitBuilderLambda(owner)

            else -> false
        }
    }

    private fun isUnitBuilderLambda(literal: KtFunctionLiteral): Boolean {
        val lambda = literal.parent as? KtLambdaExpression ?: return false
        val argument = lambda.parent
        val call =
            when (argument) {
                is KtLambdaArgument -> argument.parent as? KtCallExpression
                is KtValueArgument -> argument.parent?.parent as? KtCallExpression
                else -> null
            } ?: return false
        return call.calleeExpression?.text in UNIT_BUILDERS
    }

    private fun mentions(
        element: PsiElement,
        name: String,
    ): Boolean {
        if (element is KtNameReferenceExpression && element.getReferencedName() == name) return true
        return element.children.any { mentions(it, name) }
    }

    private companion object {
        val UNIT_BUILDERS = setOf("launch")

        /**
         * The members of `Result` that hand the failure to somebody, and therefore end the question.
         *
         * NOT `getOrNull` OR `getOrDefault`: those answer "what is the value" and drop the exception
         * on the way, which is the shape this rule is about. `isFailure` is here because a branch on
         * it is a decision somebody wrote, and a rule cannot tell a good one from a bad one without
         * reading the branch.
         */
        val READS_THE_FAILURE =
            setOf(
                "onFailure",
                "getOrElse",
                "getOrThrow",
                "fold",
                "recover",
                "recoverCatching",
                "exceptionOrNull",
                "isFailure",
            )
    }
}
