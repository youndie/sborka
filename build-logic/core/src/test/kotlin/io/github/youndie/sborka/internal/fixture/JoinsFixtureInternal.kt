package io.github.youndie.sborka.internal.fixture

/**
 * `internal`, and therefore never a finding: declaring a helper beside its only user is Kotlin's own
 * idiom, and a rule against it is a rule against the language. In bytecode this is `public` and
 * unmangled, so only the source can say.
 */
internal class JoinsFixtureInternal
