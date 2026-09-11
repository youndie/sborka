package stand

/**
 * A pattern built on every call, inside the module the stand declares hot — and answered for.
 *
 * THE SUPPRESSION IS THE POINT OF THE FIXTURE. The gate fails on a finding here, so this file is
 * the stand's proof that the escape hatch works and that the form is the one the failure message
 * asks for. Taking the `@Suppress` away is the control that makes `./gradlew -p stand check` red —
 * run by hand, because a build that has to fail cannot live in CI beside one that has to pass.
 *
 * The reason below is also not decoration: `kapkan:suppression-needs-a-reason` fails a suppression
 * that has no second string.
 */
public fun looksLikeCode(value: String): Boolean =
    @Suppress("kapkan:pattern-built-per-call", "the stand's proof that a suppression answers the gate")
    Regex("[A-Z]{2}-\\d{4}").matches(value)
