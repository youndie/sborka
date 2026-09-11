package io.github.youndie.sborka.internal

/**
 * Telling a test compilation's output from the code that ships, by the directory it landed in.
 *
 * READ OFF THE OUTPUT AND NOT THE SOURCE: `build/classes/kotlin/desktop/desktopTest` and
 * `build/classes/java/test` are what the build actually produced, and one source directory can feed
 * several compilations.
 *
 * IT IS HERE BECAUSE IT WAS ABOUT TO EXIST TWICE, which is the same reason `ConstantPool` is a file
 * of its own. `Joins` had this rule and `MethodSizes` did not — and the half that was missing was
 * the one that decides whether a gate may fail a build: konekt's first run on the new version listed
 * six patterns built per call in `ScreensLookNothingUpTest`, and a scope naming `:server` would have
 * failed on them.
 */
internal object Outputs {
    /**
     * Whether any segment of this path names a test compilation.
     *
     * `test` in any case, or a segment ending in `Test` — `jvmTest`, `desktopTest`, `androidUnitTest`
     * is caught by the first form, `commonTest` by the second.
     */
    fun isTest(path: String): Boolean =
        path
            .split('/')
            .any { segment -> segment.equals("test", ignoreCase = true) || segment.endsWith("Test") }
}
