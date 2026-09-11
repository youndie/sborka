// A build of its own, deliberately outside sborka's.
//
// The probe answers "what does the same Kotlin source do on two targets", and the answer has to be
// the platforms' and not this repository's conventions. Applying `sborka.kmp` here would put an
// explicit-API rule, a ktlint pass and a test gate between the question and the answer; the root
// build does not include this directory for the same reason.
rootProject.name = "parity-probe"

// Only so that `-PprobeJdk=17` can fetch a JDK nobody has installed. The JDK is a variable for at
// least one row (`\b` before a non-ASCII letter changed in JDK 19), and a claim about "the JVM"
// that cannot be re-run on another JDK is a claim about one machine.
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
