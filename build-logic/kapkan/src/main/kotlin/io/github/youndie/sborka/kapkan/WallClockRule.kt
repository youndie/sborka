package io.github.youndie.sborka.kapkan

import com.pinterest.ktlint.rule.engine.core.api.AutocorrectDecision
import com.pinterest.ktlint.rule.engine.core.api.Rule
import com.pinterest.ktlint.rule.engine.core.api.RuleAutocorrectApproveHandler
import org.jetbrains.kotlin.com.intellij.lang.ASTNode
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtQualifiedExpression

/**
 * Code that asks its own machine what time it is.
 *
 * **What it catches.** `System.currentTimeMillis()`, `Clock.System.now()`, `Instant.now()` and the
 * `java.time` `now()`s, anywhere but the one place a repository has said owns the clock.
 *
 * **Where it was found.** shashki B-29. `OfferView` carried `expiresAtEpochMs` and nothing else, so
 * the browser had to subtract its own wall clock from a server's deadline — and a laptop an hour out
 * draws a fifteen-second countdown that never starts. The wire now carries `nowEpochMs` beside it and
 * the client counts a duration it was handed. The test sets the server's clock four billion
 * milliseconds away from anything this process would call "now" and still expects fifteen.
 *
 * **`System.nanoTime()` is deliberately absent.** It is a monotonic clock: it cannot be subtracted
 * from anybody's epoch and cannot produce B-29's defect. Including it would have added seven
 * suppressions across the portfolio — `GraphHopperRouteEstimator`, four tests in shashki and kompot
 * — every one of them measuring a duration, which is the thing `nanoTime` is for.
 *
 * **This rule fires on correct code by design.** A composition root binds the clock —
 * `viewModel { TripViewModel(…, now = { Clock.System.now().toEpochMilliseconds() }) }` — and that
 * binding is a reading of the wall clock. There is no configuration that could exempt it, because
 * there is no configuration: the exemption is a suppression, and the suppression is the sentence
 * saying which file owns time in this repository.
 */
public class WallClockRule :
    Rule(
        ruleId = ruleId("wall-clock"),
        about = ABOUT,
    ),
    RuleAutocorrectApproveHandler {
    override fun beforeVisitChildNodes(
        node: ASTNode,
        emit: (Int, String, Boolean) -> AutocorrectDecision,
    ) {
        val call = node.psi as? KtCallExpression ?: return
        val callee = call.calleeExpression?.text ?: return
        if (callee != "now" && callee != "currentTimeMillis") return

        // The receiver, and it is what keeps this from being a search for the word `now`. A
        // `Repository.now()` of somebody's own is not this, and neither is a `now` parameter being
        // invoked — that one is the fix, not the defect.
        val qualified = call.parent as? KtQualifiedExpression ?: return
        if (qualified.selectorExpression !== call) return
        val receiver = qualified.receiverExpression.text.replace(WHITESPACE, "")
        val clock = CLOCKS.firstOrNull { receiver == it || receiver.endsWith(".$it") } ?: return
        if (callee == "currentTimeMillis" && clock != "System") return
        if (callee == "now" && clock == "System") return

        emit(
            call.node.startOffset,
            "$receiver.$callee() reads the clock of whatever machine this runs on — a time that has " +
                "to agree with somebody else's is a value carried on the wire, not one read here",
            false,
        )
    }

    private companion object {
        val WHITESPACE = Regex("""\s+""")

        /**
         * Receivers, not functions. `now()` is a common enough name that matching it alone would
         * catch every port this rule exists to encourage; what is unambiguous is `Clock.System`,
         * `Instant` and their `java.time` neighbours.
         *
         * LONGEST FIRST, AND THE ORDER IS LOAD-BEARING. The match is `endsWith(".$it")`, and
         * `Clock.System` ends with `.System` — so with `System` first, `Clock.System.now()` would be
         * read as a `now()` on `System`, which is a function that does not exist, and the rule would
         * silently pass the commonest form of the defect it is for.
         */
        val CLOCKS =
            listOf(
                "Clock.System",
                "System",
                "Instant",
                "LocalDate",
                "LocalDateTime",
                "LocalTime",
                "OffsetDateTime",
                "ZonedDateTime",
                "Year",
                "YearMonth",
            )
    }
}
