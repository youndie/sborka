plugins {
    alias(libs.plugins.kotlinJvm)
    `java-gradle-plugin`
    id("ru.workinprogress.sborka.jvm")
    // LINT HERE TOO, and the module had none. `Kapkan.kt` beside `NoopPlugin.kt` is the stand's proof
    // that the rule set reaches ktlint, and it lives here rather than in `:jvm-lib` because that one
    // carries `sborka.mutation`: a function with no test would have dropped its test strength, and a
    // number that moves for a reason unrelated to what it measures is a number nobody watches.
    id("ru.workinprogress.sborka.lint")
    id("ru.workinprogress.sborka.publish")
}

// A MODULE THAT IS ITSELF A GRADLE PLUGIN, which is a publishing shape of its own: `java-gradle-plugin`
// registers `pluginMaven` and one marker per plugin id, and a convention that also registers a `maven`
// publication gives the same coordinate two publications. They carry different metadata, and whichever
// task runs last decides what a consumer gets.
//
// The stand had no module of this shape until a migration brought one, and the defect shipped in
// 0.1.0.4.

gradlePlugin {
    plugins {
        create("stand") {
            id = "stand.noop"
            implementationClass = "stand.NoopPlugin"
            displayName = "a plugin that exists to be published"
            description = "Nothing but a publishing shape: java-gradle-plugin registers its own publications."
        }
    }
}

publishing {
    repositories {
        maven {
            name = "stand"
            url = uri(rootProject.layout.buildDirectory.dir("repo"))
        }
    }
}

// ONE PUBLICATION PER COORDINATE, checked rather than assumed.
//
// `pluginMaven` and the marker are what `java-gradle-plugin` produces; anything else publishing
// `ru.workinprogress.stand:gradle-plugin` is a second writer of the same files.
// Registered from `afterEvaluate` so the names are read AFTER the conventions have had their turn —
// and captured as a plain list, because a provider that reads an extension is resolved by the
// configuration cache in a scope where that extension does not exist.
afterEvaluate {
    val published =
        extensions
            .getByType<PublishingExtension>()
            .publications.names
            .sorted()

    val verify =
        tasks.register("verifyPublicationShape") {
            group = "verification"
            description = "Checks that the conventions did not add a publication beside the plugin's own"
            outputs.upToDateWhen { false }
            doLast {
                check(published == listOf("pluginMaven", "standPluginMarkerMaven")) {
                    "a java-gradle-plugin module should publish exactly `pluginMaven` and its marker, " +
                        "and this one publishes $published. A second publication under the same " +
                        "coordinate makes what a consumer receives depend on which task ran last."
                }
            }
        }

    tasks.named("check") { dependsOn(verify) }
}
