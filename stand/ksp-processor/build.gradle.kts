plugins {
    alias(libs.plugins.kotlinJvm)
}

// NO CONVENTION APPLIED, and that is not an oversight. This module is a FIXTURE for `:kmp-ksp`, not
// a subject: it stands in for the processor a real repository writes, and holding it to
// `explicitApi()` and a publish shape would be testing the fixture instead of the convention.

dependencies {
    implementation(libs.ksp.symbol.processing.api)
}
