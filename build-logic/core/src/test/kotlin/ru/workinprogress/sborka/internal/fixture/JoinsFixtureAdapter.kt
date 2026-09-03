package ru.workinprogress.sborka.internal.fixture

/** The implementation. Its `open` is an `override`, and an override is never asked about. */
public class JoinsFixtureAdapter : JoinsFixturePort {
    override fun open(): Int = 1
}
