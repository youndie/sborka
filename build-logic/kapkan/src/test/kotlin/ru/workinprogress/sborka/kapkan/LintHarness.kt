package ru.workinprogress.sborka.kapkan

import com.pinterest.ktlint.rule.engine.api.Code
import com.pinterest.ktlint.rule.engine.api.KtLintRuleEngine
import com.pinterest.ktlint.rule.engine.api.LintError
import com.pinterest.ktlint.rule.engine.core.api.RuleProvider
import java.nio.file.Path

/**
 * A rule is tested by running ktlint over a snippet — the same entry point ktlint-gradle drives, so a
 * rule that fires here fires in a build.
 *
 * The PATH IS PART OF THE INPUT and not decoration: `foreign-import-in-common` decides what to do
 * from the source set in it, exactly as it will in a repository, and a snippet with no path is a
 * snippet in no source set.
 */
internal fun lint(
    code: String,
    path: String = "/repo/module/src/commonMain/kotlin/Sample.kt",
    rules: Set<RuleProvider> = KapkanRuleSetProvider().getRuleProviders(),
): List<LintError> {
    val errors = mutableListOf<LintError>()
    KtLintRuleEngine(ruleProviders = rules)
        .lint(Code.fromSnippetWithPath(code, Path.of(path))) { errors += it }
    return errors
}

internal fun List<LintError>.ids(): List<String> = map { it.ruleId.value }
