package parity

import kotlin.test.Test

/*
 * The header of the transcript, not a row in it.
 *
 * `PARITYENV` rather than `PARITY`: `run.sh` turns these into `#` comment lines and `compare.py`
 * skips them, so the environment is recorded without being compared — the two targets are supposed
 * to differ here.
 *
 * The JDK belongs in the header because for at least one row it IS the variable:
 * `Regex("\\bé").find("é")` returns null on this runtime, and that is JDK 19+ behaviour
 * (JDK-8264160 aligned `\b` with the ASCII `\w` it is defined against). A transcript that names
 * Kotlin and the libraries but not the JDK cannot be re-checked for that row.
 */
class Env {
    @Test
    fun env() {
        println("PARITYENV\truntime\tJVM ${System.getProperty("java.runtime.version")} (${System.getProperty("java.vendor")})")
        println("PARITYENV\thost\t${System.getProperty("os.name")} ${System.getProperty("os.arch")}")
    }
}
