package io.github.youndie.sborka.kapkan

import com.pinterest.ktlint.rule.engine.core.api.AutocorrectDecision
import com.pinterest.ktlint.rule.engine.core.api.Rule
import com.pinterest.ktlint.rule.engine.core.api.RuleAutocorrectApproveHandler
import org.jetbrains.kotlin.com.intellij.lang.ASTNode
import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtBlockExpression
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtCatchClause
import org.jetbrains.kotlin.psi.KtClassInitializer
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtLambdaArgument
import org.jetbrains.kotlin.psi.KtLambdaExpression
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtPropertyAccessor
import org.jetbrains.kotlin.psi.KtSecondaryConstructor
import org.jetbrains.kotlin.psi.KtThrowExpression
import org.jetbrains.kotlin.psi.KtTryExpression
import org.jetbrains.kotlin.psi.KtValueArgument

/**
 * A cancellation turned into a value.
 *
 * **What it catches.** In a suspending context, and there only:
 *
 *  * `runCatching { … }` — it catches `CancellationException` like anything else, so the coroutine
 *    that was cancelled goes on running and the caller is handed the cancellation dressed as a
 *    failure;
 *  * `catch (e: Exception)` / `catch (e: Throwable)` with nothing that lets a cancellation back out.
 *    `CancellationException` **is** an `Exception`, which is exactly what makes this invisible on
 *    inspection.
 *
 * **Three shapes answer it, and all three are written in this portfolio.** A rethrowing
 * `catch (e: CancellationException)` ahead of the broad one; the broad one ending in `throw e` — the
 * compensating handler, which hands the cancellation on untouched; and `ensureActive()`, which is the
 * shape kotlinx.coroutines documents. `withContext(NonCancellable) { … }` answers it for everything
 * inside: a block that says it cannot be cancelled has no cancellation to turn into a value.
 *
 * **Where it was found.** mani, `UseCase`: every call went through a `withTry` that turned the
 * failure into a `Result`, so leaving a screen did not cancel the call — the coroutine kept running
 * and the screen drew the cancellation as a network error. Looking for the same shape turned up
 * three more (youndie/mani-kotlin-fullstack#164): the client repository fell back to the cache and
 * lit up "showing the last known data" over a live connection; `TokenService.verify` answered
 * `null` for a cancelled request, so the server replied 401 to a client that had already gone; and
 * `DemoService.createSandbox` swallowed the cancellation together with a sweep failure and went on
 * registering a user for nobody.
 *
 * **This is not [SwallowedFailureRule], and the pair is easy to confuse.** That one asks whether a
 * failure is read; this one asks whether one particular failure had any business becoming a value.
 * All four mani sites read their failure properly — folded it, showed it on screen — so
 * `swallowed-failure` looked straight at them and had nothing to say. `DemoService` makes the point:
 * it carries `@Suppress("ktlint:kapkan:swallowed-failure", …)` and that suppression is correct, the
 * sweep failure really should be swallowed. The cancellation riding along with it should not.
 *
 * **The fix is one shape** — `runCatching` with `catch (e: CancellationException) { throw e }` in
 * front of it, which mani carries as `suspendRunCatching`. sborka does not ship that helper: it would
 * have to be a multiplatform artefact on every consumer's compile classpath, which is a publication
 * of a kind this repository has none of, for eight lines. See `docs/kapkan.md` §11.
 *
 * **The false positives are real, and the narrowing that was supposed to remove them was measured
 * instead.** kapkan reads syntax, not types, so it cannot tell whether the block inside a
 * `runCatching` reaches a suspension point at all; a `suspend fun` whose `runCatching` wraps purely
 * synchronous work cannot swallow a cancellation. The proposed cure — flag only a block that
 * contains a call — **removes none of the 89 `runCatching` findings in the portfolio**, because
 * synchronous work is made of calls too: all seven call-free `runCatching` blocks there are in
 * property getters and plain functions, which this rule never looked at. See `docs/kapkan.md` §11.
 * What is left is one suppression per such block, and the suppression has to say which one it is.
 */
public class CancellationSwallowedRule :
    Rule(
        ruleId = ruleId("cancellation-swallowed"),
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
        if (!suspends(call)) return
        emit(
            call.node.startOffset,
            "runCatching catches CancellationException with everything else, so cancelling this " +
                "coroutine leaves it running and hands the caller the cancellation dressed as a " +
                "failure — use a runCatching that rethrows CancellationException first, or say in a " +
                "@Suppress why this block cannot be cancelled",
            false,
        )
    }

    private fun visitCatch(
        clause: KtCatchClause,
        emit: (Int, String, Boolean) -> AutocorrectDecision,
    ) {
        val parameter = clause.catchParameter ?: return
        val caught = parameter.typeReference?.text?.substringAfterLast('.') ?: return
        // THE SAME TWO TYPES `swallowed-failure` ASKS ABOUT, and for the same reason: a `catch` that
        // names what it expects is answerable for it, and `CancellationException` is not among the
        // things anybody expects from a name like `SerializationException`.
        if (caught != "Exception" && caught != "Throwable") return
        if (!suspends(clause)) return
        if (letsCancellationOut(clause, parameter.name)) return
        emit(
            clause.node.startOffset,
            "CancellationException is an $caught, so this catch turns a cancellation into a value " +
                "and the coroutine that was cancelled goes on running — rethrow it in a " +
                "catch (e: CancellationException) ahead of this one, or say in a @Suppress why " +
                "nothing here can be cancelled",
            false,
        )
    }

    /**
     * Whether a cancellation can leave this `catch` instead of becoming a value.
     *
     * Three shapes, because all three are written and all three work: a `catch (e:
     * CancellationException)` ahead of this one that throws; this body testing for the type itself
     * and throwing; and `ensureActive()`, which rethrows the context's own cancellation and is the
     * shape kotlinx.coroutines documents. A `catch (e: CancellationException)` that does NOT throw
     * is not one of them — it swallows the cancellation in a clause that merely looks deliberate.
     */
    private fun letsCancellationOut(
        clause: KtCatchClause,
        name: String?,
    ): Boolean {
        val body = clause.catchBody
        if (body != null && (mentions(body, ENSURE_ACTIVE) || (mentions(body, CANCELLATION) && throws(body)))) {
            return true
        }
        // `catch (e: Throwable) { rollBack(); throw e }` — the compensating handler, and the shape a
        // cancellation survives untouched. It has to be the LAST statement and it has to be the
        // parameter itself: `throw Wrapped(e)` at the end of the same block is how a cancellation
        // stops being one.
        if (name != null && rethrows(body, name)) return true
        val clauses = (clause.parent as? KtTryExpression)?.catchClauses ?: return false
        return clauses
            .takeWhile { it !== clause }
            .any { earlier ->
                val type =
                    earlier.catchParameter
                        ?.typeReference
                        ?.text
                        ?.substringAfterLast('.')
                type == CANCELLATION && earlier.catchBody?.let(::throws) == true
            }
    }

    /**
     * Whether this element sits in a suspending context, decided by structure and by a dozen names.
     *
     * The walk stops at the first thing that answers: a named function (an anonymous `fun` included)
     * answers with its own `suspend` modifier, and a property accessor, an initialiser or a
     * constructor answers no — none of them can be `suspend`. Everything else is walked through,
     * which is what makes `list.map { runCatching { … } }` inside a `suspend fun` a hit: `map` is
     * inline, so its lambda suspends with the function around it. A lambda handed to a function that
     * is neither inline nor suspending is the false positive this costs, and it is rarer in this
     * portfolio than the shape it buys.
     *
     * THE BUILDER NAMES ARE THE ONE PLACE THIS READS A NAME, and they earn it by being the only way a
     * suspending block appears inside a function that is not itself `suspend` — `scope.launch { … }`
     * in a `ViewModel`, or the `flow { }` that shashki's `ObserveTripUseCase` emits a `Result` from.
     * mani's own four sites needed none of them; every one was inside a plain `suspend fun`.
     */
    private fun suspends(element: PsiElement): Boolean {
        var current: PsiElement? = element.parent
        while (current != null) {
            // `withContext(NonCancellable) { … }` says out loud that this block is not cancellable,
            // so there is no cancellation here to turn into a value. It is the one exemption the
            // language itself writes, and it is asked before the builder names — `withContext` is on
            // both lists.
            if (current is KtLambdaExpression) {
                if (isNonCancellableLambda(current)) return false
                if (isSuspendingBuilderLambda(current)) return true
            }
            if (current is KtNamedFunction) return current.hasModifier(KtTokens.SUSPEND_KEYWORD)
            if (current is KtPropertyAccessor || current is KtClassInitializer || current is KtSecondaryConstructor) {
                return false
            }
            current = current.parent
        }
        return false
    }

    private fun isSuspendingBuilderLambda(lambda: KtLambdaExpression): Boolean =
        callTaking(lambda)?.calleeExpression?.text in SUSPENDING_BUILDERS

    private fun isNonCancellableLambda(lambda: KtLambdaExpression): Boolean {
        val call = callTaking(lambda) ?: return false
        if (call.calleeExpression?.text != "withContext") return false
        return call.valueArguments.any { it.getArgumentExpression()?.text?.contains(NON_CANCELLABLE) == true }
    }

    private fun callTaking(lambda: KtLambdaExpression): KtCallExpression? =
        when (val argument = lambda.parent) {
            is KtLambdaArgument -> argument.parent as? KtCallExpression
            is KtValueArgument -> argument.parent?.parent as? KtCallExpression
            else -> null
        }

    private fun rethrows(
        body: KtExpression?,
        name: String,
    ): Boolean {
        val last =
            when (body) {
                is KtBlockExpression -> body.statements.lastOrNull()
                else -> body
            }
        val thrown = (last as? KtThrowExpression)?.thrownExpression as? KtNameReferenceExpression
        return thrown?.getReferencedName() == name
    }

    private fun mentions(
        element: PsiElement,
        name: String,
    ): Boolean {
        if (element is KtNameReferenceExpression && element.getReferencedName() == name) return true
        return element.children.any { mentions(it, name) }
    }

    private fun throws(element: PsiElement): Boolean {
        if (element is KtThrowExpression) return true
        return element.children.any { throws(it) }
    }

    private companion object {
        const val CANCELLATION = "CancellationException"
        const val ENSURE_ACTIVE = "ensureActive"
        const val NON_CANCELLABLE = "NonCancellable"

        /**
         * Calls whose lambda suspends whatever the function around it is.
         *
         * Deliberately short. Every name here changes the answer for a block inside a plain `fun`, so
         * a name that does not really take a suspending lambda would invent findings out of nothing;
         * these are the builders the portfolio actually writes.
         */
        val SUSPENDING_BUILDERS =
            setOf(
                "launch",
                "async",
                "runBlocking",
                "runTest",
                "withContext",
                "coroutineScope",
                "supervisorScope",
                "withTimeout",
                "withTimeoutOrNull",
                "flow",
                "channelFlow",
                "callbackFlow",
            )
    }
}
