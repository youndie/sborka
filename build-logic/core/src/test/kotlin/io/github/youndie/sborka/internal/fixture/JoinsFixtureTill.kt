package io.github.youndie.sborka.internal.fixture

/** `charge` is compiled as `charge-<hash>`, because a value class is in its signature. */
public class JoinsFixtureTill {
    public fun charge(cents: JoinsFixtureCents): Int = cents.amount
}
