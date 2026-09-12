package io.github.youndie.sborka

import io.github.youndie.sborka.internal.SborkaParity
import io.github.youndie.sborka.internal.SborkaVersion
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

// THE PLATFORM GATE, and it is a plugin of its own rather than a line in `sborka.kmp`.
//
// `sborka.kmp` is applied by library modules that have no platform to probe, and a network assertion
// in their test run is a flaky test they did not ask for. A service says it wants this, the same way
// it says which targets it builds.
//
// What it asks the platform is in `io.github.youndie.sborka:platform-probe`, and the reason those
// assertions go through ktor rather than through the syscall underneath is the whole argument for
// the module: the hostname failure this portfolio paid for was in ktor's own `InetSocketAddress`
// while every syscall below it worked. See `docs/research/research-parity.md` §1.5.

plugins {
    id("io.github.youndie.sborka.base")
}

/** Where the probe should look, which has to be this repository's own stand. */
interface ParityProbeExtension {
    /**
     * A hostname the build can resolve and connect to — a service on the compose network, not a
     * public address. A probe that needs the internet goes red on somebody else's outage, and then
     * gets switched off.
     */
    val host: Property<String>

    /** The port on [host]. Something has to be listening: a refused connection is a failed probe. */
    val port: Property<Int>

    /** An environment variable to read. Absent is fine — the question is whether it can be asked. */
    val environmentVariable: Property<String>
}

val parity = extensions.create<ParityProbeExtension>("parityProbe")
parity.environmentVariable.convention("PATH")

plugins.withId("org.jetbrains.kotlin.multiplatform") {
    extensions.configure<KotlinMultiplatformExtension> {
        // AT THE SAME VERSION AS THE CONVENTION THAT NAMES IT, like the rule set in `sborka.lint`.
        // A probe from a different release than the plugin wiring it is two halves of one decision
        // moving separately.
        sourceSets.getByName("commonTest").dependencies {
            implementation("io.github.youndie.sborka:platform-probe:${SborkaVersion.CURRENT}")
        }
    }
}

// THE TARGET REACHES THE TEST THROUGH THE ENVIRONMENT, which is the one channel a JVM test task and
// a Kotlin/Native one both have. A system property would need two different calls and would not
// survive the native test binary at all.
//
// Set on EVERY test task rather than only under `parityCheck`: a task cannot change another task's
// environment at execution time, and a probe configured for one entry point and not for the other is
// a probe that answers differently depending on how it was started.
// SET WHEN THE TASK IS REALIZED, not in a `doFirst`. A `doFirst` that calls `environment(...)` on a
// test task compiles and runs and the variable does not arrive — the first version of this did
// exactly that, and the plumbing assertion in the consumer is what said so rather than a silent
// fallback to the default host.
//
// On every test task rather than only under `parityCheck`: one task cannot change another's
// environment, and a probe configured for one entry point and not the other answers differently
// depending on how it was started.
// Twice rather than once through a `when`, and the duplication is the honest shape: `Test` and
// `KotlinNativeTest` share no supertype that declares `environment`, so a single branch would be a
// `when` over types that then calls two different methods — which ktlint rightly makes ugly and
// which reads as though the two were interchangeable.
tasks.withType<Test>().configureEach {
    val host = parity.host.orNull
    val port = parity.port.orNull
    if (host != null && port != null) {
        environment(SborkaParity.HOST_VARIABLE, host)
        environment(SborkaParity.PORT_VARIABLE, port.toString())
    }
}

tasks.withType<org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeTest>().configureEach {
    val host = parity.host.orNull
    val port = parity.port.orNull
    if (host != null && port != null) {
        environment(SborkaParity.HOST_VARIABLE, host)
        environment(SborkaParity.PORT_VARIABLE, port.toString())
    }
}

// A LIFECYCLE TASK OVER THE TEST TASKS THAT ALREADY EXIST.
//
// The probe lives in `commonTest`, so every target's test task already runs it; what was missing was
// a name for "ask the platform on every target" and a line saying what that did and did not cover.
// Registering a second, filtered test task per target would run the same assertions twice and give
// the build two answers to one question.
tasks.register("parityCheck") {
    group = "verification"
    description = "Runs this module's tests on every target, which is where the platform probe lives"

    val testTasks = tasks.matching { it.name.endsWith("Test") && it.name != "test" }
    dependsOn(testTasks)

    // ONLY THE TARGETS WHOSE TESTS CAN RUN HERE. A Kotlin/Native test task for a target the host
    // cannot execute is disabled rather than removed, so naming every task would have this line
    // claim linuxX64 coverage from a Mac — a gate reporting what it did not do, which is the failure
    // it exists to prevent. The first version did exactly that.
    val targets =
        project.provider {
            testTasks.filter { it.enabled }.map { it.name.removeSuffix("Test") }.sorted()
        }
    val where =
        parity.host
            .zip(parity.port) { h, p -> "$h:$p" }
            .orElse("a listener each test binds for itself")

    doLast {
        // WHAT IT DID NOT COVER, IN THE SAME LINE AS THE RESULT. Every divergence this portfolio has
        // paid for was in the platform layer, and a green gate that does not say where it stopped
        // looking accumulates trust it has not earned.
        logger.lifecycle(
            "parityCheck: platform probed at ${where.get()} on ${targets.get().joinToString(", ")}; " +
                "NOT covered — TLS through this repository's own engine, the Ktor plugins it pins, " +
                "and standard-library behaviour, which moves at a version bump rather than at a commit",
        )
    }
}
