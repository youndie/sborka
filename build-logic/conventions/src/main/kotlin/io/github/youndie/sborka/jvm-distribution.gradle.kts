package io.github.youndie.sborka

import io.github.youndie.zavarnik.ZavarnikExtension
import org.gradle.api.artifacts.ProjectDependency

// The shipped JVM half of a Kotlin/Native service.
//
// `application`, the Ktor Gradle plugin and zavarnik are `kotlinJvm`-only and do not apply to a
// multiplatform module, so a KMP service that wants `installDist` and an AOT cache cannot have them
// in `:server`. It needs a sibling that applies `application`, depends on `:server`, and holds one
// `main` — the same twelve lines in every repository, of which only the main class and the training
// workload differ.
//
// WHAT THIS CARRIES, AND WHAT IT DELIBERATELY DOES NOT.
//
// It applies `application` (a Gradle plugin, so applying it costs a consumer nothing) and wires the
// main class. It refuses the module name that produces a duplicate jar. It gives zavarnik the
// readiness URL every kore service has, when zavarnik is there.
//
// It does NOT apply the Kotlin plugin — `sborka.kmp` explains why at length, and the reason is the
// same here. It does NOT apply zavarnik: the version belongs to the repository, and a convention
// that carried it would put zavarnik on the build classpath of every repository taking any sborka
// convention. And it does NOT check the JDK: zavarnik's own `ConfigurationChecks` already refuses a
// toolchain below 25 at configuration time, naming the project and the version it found. A second
// guard over one failure is worse than one — when the suite is green you cannot tell which of them
// is doing the work.
//
// ONE THING IT CANNOT CARRY, and every repository adding a second module meets it: two sibling
// modules applying different Kotlin plugins need the ROOT build to declare both with `apply false`.
// Otherwise each arrives in its own classloader scope, the Kotlin plugin's shared
// `KotlinNativeBundleBuildService` exists twice, and the build fails at task-graph time with a
// message naming two `InstrumentingVisitableURLClassLoader` instances and nothing about the cause.
// That is a fact about a build script this plugin is not applied to; `stand/build.gradle.kts` here
// carries the arrangement with the same explanation beside it.

plugins {
    id("io.github.youndie.sborka.base")
    application
}

/** What differs between one service's distribution module and the next. */
interface JvmDistributionExtension {
    /** The fully qualified `main` of the JVM binary, e.g. `io.github.youndie.keel.jvm.MainKt`. */
    val mainClass: Property<String>

    /**
     * What zavarnik polls until the service is ready to be trained.
     *
     * Defaulted to kore's readiness route on the portfolio's port, because that is the same URL in
     * every service that takes kore. A service that mounts its probes elsewhere sets this; a service
     * that needs something other than a URL configures `zavarnik { training { readyWhen … } }`
     * directly, and this convention then leaves it alone.
     */
    val readinessUrl: Property<String>
}

val jvmDistribution = extensions.create<JvmDistributionExtension>("jvmDistribution")
jvmDistribution.readinessUrl.convention("http://127.0.0.1:8080/health/ready")

application {
    mainClass.set(jvmDistribution.mainClass)
}

// THE MODULE NAME THAT BREAKS THE DISTRIBUTION, refused with the reason rather than met as a
// `Sync` failure.
//
// Kotlin names a multiplatform module's JVM artefact `<module>-jvm-<version>.jar`. A distribution
// module called `<module>-jvm` produces a jar of exactly that name, both land in the distribution's
// `lib/`, and `installDist` fails with `Entry lib/<module>-jvm-<version>.jar is a duplicate` —
// naming the file and neither of the two modules that produced it.
//
// Checked against the dependency rather than against the suffix: `foo-jvm` is only a collision when
// something called `foo` is on the runtime classpath, and a convention that refused every name
// ending in `-jvm` would be refusing a name that is sometimes right.
afterEvaluate {
    val collisions =
        configurations
            .findByName("runtimeClasspath")
            ?.allDependencies
            ?.filterIsInstance<ProjectDependency>()
            ?.map { it.name }
            ?.filter { "$it-jvm" == project.name }
            .orEmpty()
    check(collisions.isEmpty()) {
        "${project.path} is named after a module it depends on: Kotlin publishes " +
            "${collisions.first()}'s jvm artefact as `${collisions.first()}-jvm-<version>.jar`, this " +
            "module's own jar has the same name, and both are copied into the distribution's `lib/`. " +
            "`installDist` fails with `is a duplicate`, naming the file and neither module. Rename " +
            "this one — `:distribution` is what the portfolio uses."
    }
}

// ZAVARNIK, IF THE REPOSITORY ASKED FOR IT.
//
// Only the readiness URL, and only as a convention: a service that sets its own keeps it. The
// workload is deliberately left here — it is the part that differs per service, and a warm-up that
// exercises the wrong routes produces a cache that looks exactly like a good one.
plugins.withId("io.github.youndie.zavarnik") {
    extensions.configure<ZavarnikExtension> {
        training.readyWhen.url.convention(jvmDistribution.readinessUrl)
    }
}
