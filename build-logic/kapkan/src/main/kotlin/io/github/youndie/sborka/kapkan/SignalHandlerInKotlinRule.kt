package io.github.youndie.sborka.kapkan

import com.pinterest.ktlint.rule.engine.core.api.AutocorrectDecision
import com.pinterest.ktlint.rule.engine.core.api.Rule
import com.pinterest.ktlint.rule.engine.core.api.RuleAutocorrectApproveHandler
import org.jetbrains.kotlin.com.intellij.lang.ASTNode
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtBinaryExpression
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtPsiUtil
import org.jetbrains.kotlin.psi.KtQualifiedExpression
import org.jetbrains.kotlin.psi.KtValueArgument
import org.jetbrains.kotlin.psi.psiUtil.collectDescendantsOfType

/**
 * A signal handler written in Kotlin.
 *
 * **What it catches.** A `staticCFunction` installed as a POSIX signal handler, in the three shapes
 * the portfolio writes it:
 *
 * ```kotlin
 * signal(SIGTERM, staticCFunction<Int, Unit> { raised.compareAndSet(0, SIGTERM) })
 *
 * val handler = staticCFunction<Int, Unit> { code -> leaveFullScreen(); exit(128 + code) }
 * signal(SIGINT, handler)
 *
 * action.__sigaction_handler.sa_handler = staticCFunction<Int, Unit> { … }
 * ```
 *
 * A `staticCFunction` is a C-to-Kotlin bridge, and a signal is delivered on whichever thread the
 * kernel picks. The bridge enters the Kotlin runtime on that thread — which may be in the middle of
 * an allocation, or not initialised yet. **The body does not matter**: one atomic store fails as
 * often as anything else, because what fails is the entry, not the work.
 *
 * **Where it was found.** Twice, measured both times:
 *
 *  * kore B-64 — a `SIGTERM` landing on a newborn worker thread kills it with `SIGSEGV`, exit 139.
 *    Over 1 000 runs the Kotlin handler crashed 16 times and the same handler in C through cinterop
 *    0 times. The fix is `koreSignal.def`: a C handler that does one `sig_atomic_t` store.
 *  * razves, `docs/research/research-profiler.md` §1.1 — the sampler's Kotlin handler hung 3 runs in
 *    10 at 100 Hz and 8 in 10 at 1 kHz, deadlocked against the allocator it had interrupted. The
 *    sampler is C now, in `sampler.def`, whose header says why.
 *
 * `kotlin-native-cinterop` §6 already said "Handlers are C, through cinterop, and do one atomic
 * store". A skill is read by whoever opens it; this fires on whoever writes the code.
 *
 * **Which `signal`.** The rule reads syntax, and `signal` is a common name — a coroutine primitive, a
 * Compose state, a condition variable. So it reads a file only when the file imports
 * `platform.posix.signal`, `platform.posix.sigaction` or `platform.posix.*`, follows an `as` alias
 * on that import, and takes `signal(…)` only as a call with no receiver or with `platform.posix` as
 * its receiver. `sigaction` is caught at the assignment to the struct's handler field, which is where
 * the `staticCFunction` is: `sa_handler` / `sa_sigaction` as cinterop names them for glibc (behind
 * the `__sigaction_handler` union), and `__sa_handler` / `__sa_sigaction` for Apple (behind
 * `__sigaction_u`).
 *
 * **A `staticCFunction` named first is followed by name, inside the file.** A `val` initialised with
 * one and passed to `signal` is the same handler; the finding is at the installation, because a
 * `staticCFunction` handed to `qsort` or to a curl callback is what the function is for. The match is
 * by name and not by scope — two `val handler`s in one file, only one of them a `staticCFunction`,
 * would be read as one. Not seen in the portfolio, and the answer is a suppression saying so.
 *
 * **Counted on 2026-10-05** over 67 repositories at their default branch: two files, four
 * installations — kore's `SignalHandler.macos.kt` (deliberate: macOS is a development target and
 * Apple cinterop is not built on kore's Linux release host; a suppression with that reason) and
 * metrik's CLI `Terminal.kt` (a real finding). Neither module applies `sborka.lint` yet, so neither
 * is reached. See `docs/kapkan.md` §13.
 */
public class SignalHandlerInKotlinRule :
    Rule(
        ruleId = ruleId("signal-handler-in-kotlin"),
        about = ABOUT,
    ),
    RuleAutocorrectApproveHandler {
    /** The names `platform.posix.signal` answers to in this file: `signal`, or its `as` alias. */
    private var signalNames: Set<String> = emptySet()

    /** Properties in this file whose initialiser is a `staticCFunction`. */
    private var bridges: Set<String> = emptySet()

    override fun beforeVisitChildNodes(
        node: ASTNode,
        emit: (Int, String, Boolean) -> AutocorrectDecision,
    ) {
        // THE GATE IS DECIDED ON THE FILE NODE, and a file that does not import POSIX's `signal` or
        // `sigaction` is not walked at all — every file in the portfolio but a handful.
        if (node.treeParent == null) {
            val file = node.psi as? KtFile
            if (file == null || !importsPosixSignals(file)) {
                stopTraversalOfAST()
                return
            }
            bridges =
                file
                    .collectDescendantsOfType<KtProperty> { isStaticCFunction(it.initializer) }
                    .mapNotNull { it.name }
                    .toSet()
            return
        }
        when (val psi = node.psi) {
            is KtCallExpression -> if (isPosixSignal(psi)) psi.valueArguments.forEach { judge(it, emit) }
            is KtBinaryExpression -> if (isHandlerAssignment(psi)) judge(psi.right, emit)
            else -> Unit
        }
    }

    /**
     * `struct sigaction`'s handler field on the left of `=`: `act.__sigaction_handler.sa_handler`, or
     * a bare `sa_handler` inside `alloc<sigaction> { … }`.
     */
    private fun isHandlerAssignment(assignment: KtBinaryExpression): Boolean {
        if (assignment.operationToken != KtTokens.EQ) return false
        val name =
            when (val left = assignment.left) {
                is KtQualifiedExpression -> (left.selectorExpression as? KtNameReferenceExpression)?.getReferencedName()
                is KtNameReferenceExpression -> left.getReferencedName()
                else -> null
            }
        return name in HANDLER_FIELDS
    }

    private fun judge(
        argument: KtValueArgument,
        emit: (Int, String, Boolean) -> AutocorrectDecision,
    ) = judge(argument.getArgumentExpression(), emit)

    private fun judge(
        expression: KtExpression?,
        emit: (Int, String, Boolean) -> AutocorrectDecision,
    ) {
        if (expression != null && isHandler(expression)) report(expression, emit)
    }

    private fun importsPosixSignals(file: KtFile): Boolean {
        // `import platform.posix.*` has `platform.posix` as its fq name and `isAllUnder` set.
        val star = file.importDirectives.any { it.isAllUnder && it.importedFqName?.asString() == POSIX }
        val single = file.importDirectives.filter { !it.isAllUnder }
        val names =
            single
                .filter { it.importedFqName?.asString() == "$POSIX.signal" }
                .map { it.aliasName ?: "signal" }
                .toMutableSet()
        if (star) names += "signal"
        signalNames = names
        val sigaction = single.any { it.importedFqName?.asString() == "$POSIX.sigaction" }
        return star || names.isNotEmpty() || sigaction
    }

    private fun isPosixSignal(call: KtCallExpression): Boolean {
        val callee = call.calleeExpression?.text ?: return false
        val qualified = call.parent as? KtQualifiedExpression
        if (qualified != null && qualified.selectorExpression === call) {
            // `condition.signal(…)` is somebody else's; `platform.posix.signal(…)` is the one.
            return callee == "signal" && qualified.receiverExpression.text.replace(WHITESPACE, "") == POSIX
        }
        return callee in signalNames
    }

    private fun isHandler(expression: KtExpression): Boolean {
        val value = KtPsiUtil.deparenthesize(expression) ?: return false
        if (isStaticCFunction(value)) return true
        return value is KtNameReferenceExpression && value.getReferencedName() in bridges
    }

    private fun isStaticCFunction(expression: KtExpression?): Boolean {
        val value = KtPsiUtil.deparenthesize(expression) ?: return false
        val call =
            when (value) {
                is KtCallExpression -> value
                is KtQualifiedExpression -> value.selectorExpression as? KtCallExpression
                else -> null
            }
        return call?.calleeExpression?.text == "staticCFunction"
    }

    private fun report(
        handler: KtExpression,
        emit: (Int, String, Boolean) -> AutocorrectDecision,
    ) {
        emit(
            handler.node.startOffset,
            "a signal handler written in Kotlin — a staticCFunction enters the Kotlin runtime on " +
                "whichever thread the signal lands on, mid-allocation or not yet initialised, and that " +
                "kills or hangs the process whatever the body does; write the handler in C, in a cinterop " +
                ".def after `---`, doing one atomic store, as kore's koreSignal.def and razves's " +
                "sampler.def do",
            false,
        )
    }

    private companion object {
        const val POSIX = "platform.posix"

        val WHITESPACE = Regex("""\s+""")

        /**
         * `struct sigaction`'s handler, as cinterop names it. glibc keeps it in a union reached as
         * `__sigaction_handler.sa_handler`; Apple's is `__sigaction_u.__sa_handler`. The C macros
         * that let C write `act.sa_handler` do not reach Kotlin, so both spellings are here.
         */
        val HANDLER_FIELDS = setOf("sa_handler", "sa_sigaction", "__sa_handler", "__sa_sigaction")
    }
}
