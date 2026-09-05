package io.github.youndie.sborka.internal.fixture

/** Holds the implementation, so the call names [JoinsFixtureAdapter] and not [JoinsFixturePort]. */
public class JoinsFixtureOpener {
    public fun use(adapter: JoinsFixtureAdapter): Int = adapter.open()
}
