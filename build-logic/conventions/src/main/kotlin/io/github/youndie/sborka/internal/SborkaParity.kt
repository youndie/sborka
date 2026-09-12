package io.github.youndie.sborka.internal

/**
 * The two variable names `sborka.parity` and `platform-probe` have to agree on.
 *
 * Spelled once here and once in the probe, because the two are in different builds and neither can
 * see the other's constants — which is exactly the shape that drifts, so both sides name this file.
 */
public object SborkaParity {
    public const val HOST_VARIABLE: String = "SBORKA_PARITY_HOST"
    public const val PORT_VARIABLE: String = "SBORKA_PARITY_PORT"
}
