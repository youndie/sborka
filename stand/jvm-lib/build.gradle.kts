plugins {
    alias(libs.plugins.kotlinJvm)
    id("io.github.youndie.sborka.jvm")
    id("io.github.youndie.sborka.lint")
    id("io.github.youndie.sborka.publish")
    id("io.github.youndie.sborka.mutation")
}

// A `kotlin("jvm")` module is the case that publishes NOTHING with a green exit code when the
// convention forgets to register a publication for it. It is first in the stand for that reason.

publishing {
    repositories {
        maven {
            name = "stand"
            url = uri(rootProject.layout.buildDirectory.dir("repo"))
        }
    }
}

// THE CONTROL FOR `forkJvmArgs`, in both places a test can be run from.
//
// `ForkedArgumentTest` fails without this property, so the pair below is what keeps
// `sborkaMutation.forkJvmArgs` honest: drop the second one and `mutationTest` fails here, in a run
// nobody had to remember to make.
tasks.withType<Test>().configureEach {
    systemProperty("stand.forkArgument", "delivered")
}

sborkaMutation {
    forkJvmArgs.add("-Dstand.forkArgument=delivered")
}
