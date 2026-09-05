package io.github.youndie.sborka.internal.fixture

/** `open` is called through an implementation, never through this interface. */
public interface JoinsFixturePort {
    public fun open(): Int
}
