// The shipped JVM half of a service, applying the convention that carries it.
//
// The pattern it stands in for: a multiplatform `:server` compiled to a native binary and to the
// JVM, plus this module — which applies `application`, depends on the server and holds one `main`,
// because `application` and zavarnik are `kotlinJvm`-only and do not apply to a multiplatform
// module at all.
//
// NOT CALLED `:kmp-lib-jvm`, and the convention refuses that name rather than leaving it to be
// discovered: Kotlin publishes a multiplatform module's JVM artefact as `<module>-jvm-<version>.jar`,
// so a module of that name produces a second jar called exactly that, both land in `lib/`, and
// `installDist` fails with `is a duplicate` — naming the file and neither of the two modules.

plugins {
    alias(libs.plugins.kotlinJvm)
    id("io.github.youndie.sborka.jvm-distribution")
    // APPLIED BY THE REPOSITORY, NOT BY SBORKA — the arrangement razves already proves next door,
    // and for the same two reasons: the version belongs to the repository, and a convention that
    // carried the plugin would put it on the build classpath of every repository taking any sborka
    // convention.
    alias(libs.plugins.zavarnik)
}

// THE TOOLCHAIN ZAVARNIK REQUIRES, above the stand's own 21.
//
// zavarnik refuses a toolchain below 25 at configuration time — the one-step AOT workflow is JEP 514
// — so this module says 25 after `sborka.base` has set the repository's number. Written here rather
// than raised repository-wide: everything else in the stand is built at the floor a consumer uses,
// and this is the one module with a reason not to be.
kotlin { jvmToolchain(25) }

dependencies {
    implementation(project(":kmp-lib"))
}

jvmDistribution {
    mainClass = "stand.distribution.MainKt"
    // `readinessUrl` is deliberately NOT set: the convention's default is the one route every kore
    // service mounts, and `verifyDistributionWiring` below reads it back out of zavarnik to prove
    // the default arrived rather than assuming it.
}

zavarnik {
    // NO TRAINING RUN IN THIS BUILD. `aotVerify` on `check` would start the application, hold it up,
    // train a cache and measure it — minutes, and a real server on a port, for a module whose `main`
    // prints one line. What the stand is proving is that the convention wires zavarnik at all; that
    // the cache works is zavarnik's own suite, against its own subjects.
    verify { onCheck = false }
    training {
        // The workload stays with the service on purpose — a warm-up that exercises the wrong routes
        // produces a cache indistinguishable from a good one — so the stand declares one too.
        workload { get("http://127.0.0.1:8080/items") }
    }
}

// WHAT THE CONVENTION ACTUALLY SET, read back rather than believed.
//
// Both halves are invisible in a green build: an unset `mainClass` fails only when somebody runs the
// start script, and a readiness URL that never reached zavarnik fails only during a training run
// nobody makes here.
val verifyDistributionWiring =
    tasks.register("verifyDistributionWiring") {
        group = "verification"
        description = "Checks that sborka.jvm-distribution wired the main class and zavarnik's readiness URL"
        outputs.upToDateWhen { false }

        // AND THE DISTRIBUTION ITSELF, because the name guard in the convention is a rule about a
        // failure that happens here: `installDist` is where two jars of one name meet.
        dependsOn(tasks.named("installDist"))

        val mainClass = application.mainClass
        val readiness = zavarnik.training.readyWhen.url
        val installed = layout.buildDirectory.dir("install/${project.name}")
        doLast {
            check(mainClass.get() == "stand.distribution.MainKt") {
                "the convention did not set the main class: ${mainClass.orNull}"
            }
            check(readiness.get() == "http://127.0.0.1:8080/health/ready") {
                "zavarnik's readiness URL is ${readiness.orNull}, not the convention's default"
            }
            val lib = installed.get().asFile.resolve("lib")
            check(lib.isDirectory) { "installDist produced no lib/ at ${lib.path}" }
            val jars = lib.listFiles().orEmpty().map { it.name }
            check(jars.any { it.startsWith("kmp-lib-jvm") }) {
                "the multiplatform module's jvm jar is not in the distribution: $jars"
            }
            check(jars.size == jars.toSet().size) { "two jars of one name in lib/: $jars" }

            logger.lifecycle(
                "verifyDistributionWiring: ${mainClass.get()} ready at ${readiness.get()}, " +
                    "${jars.size} jars in lib/",
            )
        }
    }

tasks.named("check") { dependsOn(verifyDistributionWiring) }
