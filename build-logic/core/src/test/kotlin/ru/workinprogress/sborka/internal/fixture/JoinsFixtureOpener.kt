package ru.workinprogress.sborka.internal.fixture

/** Holds the implementation, so the call names [JoinsFixtureAdapter] and not [JoinsFixturePort]. */
public class JoinsFixtureOpener {
    public fun use(adapter: JoinsFixtureAdapter): Int = adapter.open()
}
