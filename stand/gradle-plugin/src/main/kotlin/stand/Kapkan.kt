package stand

/**
 * WHAT PROVES THE RULE SET REACHED KTLINT, and it proves it by staying green.
 *
 * `sborka.lint` hands ktlint a `ktlintRuleset` coordinate. If that coordinate does not resolve, or
 * the jar has no `META-INF/services` entry, or the provider class is renamed, then kapkan's rules are
 * simply not loaded — and a linter that quietly runs fewer rules than it was given is the exact shape
 * of silence this repository's checks exist to catch.
 *
 * The suppression below is what makes that silence loud. ktlint's own `ktlint-suppression` rule
 * refuses an id naming a rule nobody loaded, so this file lints only while `kapkan:wall-clock` is
 * really there. Delete the `ktlintRuleset` line in `lint.gradle.kts` and the stand goes red here,
 * naming the rule that went missing.
 *
 * That the rule FIRES is proved elsewhere, by `:kapkan`'s own tests, which run ktlint over snippets
 * and read the errors. This is the half those cannot reach: a published jar, resolved by coordinate,
 * loaded by a worker in somebody else's build.
 */
@Suppress(
    "ktlint:kapkan:wall-clock",
    "the stand owns no clock and reports no time; this line exists to name a rule that must be loaded",
)
public fun startedAt(): Long = System.currentTimeMillis()
