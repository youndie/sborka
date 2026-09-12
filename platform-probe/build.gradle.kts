plugins {
    alias(libs.plugins.kotlinMultiplatform)
    `maven-publish`
    alias(libs.plugins.ktlint)
}

ktlint {
    version.set(libs.versions.ktlintTool)
}

val kapkanVersion: String =
    providers
        .gradleProperty("VERSION")
        .orElse(providers.gradleProperty("sborka.version"))
        .get()

// Linted by the rules this repository publishes, the same way `:catalog` is.
dependencies {
    ktlintRuleset("io.github.youndie.sborka:kapkan:$kapkanVersion")
}

group = "io.github.youndie.sborka"
version = providers.gradleProperty("VERSION").orElse(providers.gradleProperty("sborka.version")).get()

// THE ASSERTIONS A REPOSITORY PUTS ON `commonTest`.
//
// Not a convention plugin, and it could not be one: a convention configures a build, and these have
// to compile and run inside it, on every target the repository declares. Not a generated source
// either — they go through ktor rather than through the syscall underneath it, and a generated source
// cannot bring a dependency.
//
// WHY THROUGH KTOR. The hostname failure this portfolio actually paid for was in ktor's own
// `InetSocketAddress`, not in `getaddrinfo`: agents reporting through a Kubernetes service name were
// silent from their first day while every syscall underneath worked. An assertion one layer below the
// one the service uses is an assertion that passes while production does not.
//
// TARGETS: the four the portfolio's services declare. Others are not excluded on principle, they are
// excluded because nothing here has run them — which is the kind of claim this module exists to stop
// people making.
kotlin {
    explicitApi()

    jvm()
    linuxX64()
    linuxArm64()
    macosArm64()

    sourceSets {
        commonMain.dependencies {
            api(libs.ktor.network)
            api(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.io.core)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

publishing {
    repositories {
        // A directory, so the root's `verifyBuildLogicPublications` can read what this actually wrote
        // rather than trust that the task reported success. Version 0.1.0.3 shipped without `:core`
        // exactly that way.
        maven {
            name = "local"
            url = uri(rootProject.layout.buildDirectory.dir("local-repo"))
        }
        maven {
            name = "wip"
            url = uri("https://reposilite.kotlin.website/snapshots")
            credentials {
                username = providers.gradleProperty("REPOSILITE_USER").orNull
                password = providers.gradleProperty("REPOSILITE_SECRET").orNull
            }
        }
    }
}
