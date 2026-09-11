// A build of its own, deliberately outside sborka's.
//
// The probe answers "what does the same Kotlin source do on two targets", and the answer has to be
// the platforms' and not this repository's conventions. Applying `sborka.kmp` here would put an
// explicit-API rule, a ktlint pass and a test gate between the question and the answer; the root
// build does not include this directory for the same reason.
rootProject.name = "parity-probe"
