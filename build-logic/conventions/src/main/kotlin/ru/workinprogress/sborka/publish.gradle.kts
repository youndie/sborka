package ru.workinprogress.sborka

import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.tasks.bundling.AbstractArchiveTask
import ru.workinprogress.sborka.internal.SborkaSettings

// Everything the portfolio has learned about getting an artefact to a consumer, in one place.
//
// Five hand-written copies of this existed across the repositories, and no two of them knew the same
// things: one carried the group and the jvm-version attribute and the aar naming, one carried half of
// that, one carried none of it and kept the missing half inline in a module. Each gap was found the
// same way — by asking the server whether the thing resolves, long after a green build said it had
// been published.

plugins {
    id("ru.workinprogress.sborka.base")
    `maven-publish`
}

// A JAR OF SOURCES, where there is a `java` component to take them from. Cheap, and the difference
// between a consumer stepping into this library in a debugger and a consumer reading decompiled
// bytecode.
plugins.withId("java") {
    extensions.configure<JavaPluginExtension> {
        withSourcesJar()
    }
}

// THE KMP PLUGIN REGISTERS PUBLICATIONS ON ITS OWN; THE PLAIN KOTLIN/JVM PLUGIN DOES NOT.
//
// Without this block a `kotlin("jvm")` module builds fine, its publish task reports success and
// uploads NOTHING — there is simply nothing to upload. Gradle's exit code cannot tell that apart from
// a successful publish; only asking the server whether the artifact resolves can.
plugins.withId("org.jetbrains.kotlin.jvm") {
    afterEvaluate {
        // NOT WHEN `java-gradle-plugin` IS THERE, and the guard is not "is there already one called
        // maven". That plugin registers a publication of its own called `pluginMaven`, plus a marker
        // per plugin id — so the name-based check saw no `maven`, made one, and the module ended up
        // with TWO publications writing the same coordinate. Whichever task ran last decided what a
        // consumer got, and the two carry different metadata.
        //
        // Found by viddik, whose `viddik-gradle-plugin` is the first module of that shape to take
        // these conventions. sborka's own `build-logic` had the right guard and this did not, which
        // is what a second reader is for.
        if (plugins.hasPlugin("java-gradle-plugin")) return@afterEvaluate
        if (extensions.getByType<PublishingExtension>().publications.findByName("maven") == null) {
            publishing.publications.create<MavenPublication>("maven") {
                from(components["java"])
            }
        }
    }
}

// A platform registers no publication of its own either, and unlike a Kotlin/JVM module it has no
// sources to give away — only the component that carries its constraints.
plugins.withId("java-platform") {
    afterEvaluate {
        if (extensions.getByType<PublishingExtension>().publications.findByName("maven") == null) {
            publishing.publications.create<MavenPublication>("maven") {
                from(components["javaPlatform"])
            }
        }
    }
}

// THE FLOOR IN THE METADATA IS NOT SET HERE ANY MORE — `sborka.kmp` sets it, beside the `jvmTarget`
// it compiles the same variants to. The two are one statement said twice, and they were in different
// plugins: a repository that takes `sborka.kmp` and publishes some other way — smtpkn does, through
// vanniktech to Maven Central — got the bytecode right and advertised nothing at all.

// AN .aar LEAVES THE BUILD NAMED AFTER ITS MODULE AND NOTHING ELSE — `kompot-core.aar`, with no
// version in it at all. Two releases then put identically named files on a consumer's classpath, and
// anything reading file names rather than coordinates cannot tell them apart.
//
// Matched by name rather than by type: the AGP classes are not on this plugin's classpath. And the
// whole file name is set rather than its base and version parts, because the task that packages an aar
// composes the name itself and ignores them.
//
// The lint variant of the same task keeps its own name: nothing publishes it, and a second file called
// the same thing in the same build is how a task ends up overwriting another one's output.
tasks
    .matching { it.name.matches(Regex("^bundle.*Aar$")) && !it.name.contains("LocalLint") }
    .configureEach {
        (this as AbstractArchiveTask).archiveFileName.set("${project.name}-android-${project.version}.aar")
    }

publishing {
    repositories {
        maven {
            name = "wip"
            url = uri(SborkaSettings.snapshotRepository(project))
            // /snapshots is readable anonymously; credentials are needed only for writing. They come
            // from the environment (ORG_GRADLE_PROJECT_REPOSILITE_USER / ..._SECRET), so a checkout of
            // any repository can build and test but cannot publish.
            credentials {
                username = providers.gradleProperty("REPOSILITE_USER").orNull
                password = providers.gradleProperty("REPOSILITE_SECRET").orNull
            }
        }
    }

    // THE POM, DERIVED FROM ONE PROPERTY instead of copied into every repository.
    //
    // `sborka.repository` is `owner/name` on GitHub; the url, the licence link and the three scm
    // strings all follow from it, and those three were the part that got pasted wrong. The description
    // is the repository's own — a library that cannot say what it is in one line in `gradle.properties`
    // will not say it better in a build script.
    val slug = SborkaSettings.repository(project)
    val summary = providers.gradleProperty("sborka.description").orNull
    val inception = providers.gradleProperty("sborka.inceptionYear").orNull
    val licenceName = providers.gradleProperty("sborka.licence").orElse("MIT License")
    val developerId = providers.gradleProperty("sborka.developerId").orElse("youndie")
    val developerName = providers.gradleProperty("sborka.developerName").orElse("Pavel Votyakov")

    if (slug != null) {
        publications.withType<MavenPublication>().configureEach {
            pom {
                name.set(project.name)
                summary?.let { description.set(it) }
                inception?.let { inceptionYear.set(it) }
                url.set("https://github.com/$slug")

                licenses {
                    license {
                        name.set(licenceName)
                        url.set("https://github.com/$slug/blob/main/LICENSE")
                        distribution.set("repo")
                    }
                }

                developers {
                    developer {
                        id.set(developerId)
                        name.set(developerName)
                        // `.map` rather than string interpolation: `developerId` is a Provider, and a
                        // Provider in a template stringifies to its own description. That published
                        // `https://github.com/or(provider(?), fixed(youndie))` in every pom this
                        // convention has written so far, and nothing looked at a pom until bochka.
                        url.set(developerId.map { "https://github.com/$it" })
                    }
                }

                scm {
                    url.set("https://github.com/$slug")
                    connection.set("scm:git:git://github.com/$slug.git")
                    developerConnection.set("scm:git:ssh://git@github.com/$slug.git")
                }
            }
        }
    }
}
