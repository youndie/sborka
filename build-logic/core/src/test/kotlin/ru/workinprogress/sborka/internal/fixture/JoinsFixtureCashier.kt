package ru.workinprogress.sborka.internal.fixture

/** Calls the mangled function. Nothing calls this one. */
public class JoinsFixtureCashier {
    public fun ring(till: JoinsFixtureTill): Int = till.charge(JoinsFixtureCents(1))
}
