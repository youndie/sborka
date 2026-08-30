package ru.workinprogress.sborka

import org.gradle.api.attributes.Category
import org.gradle.api.attributes.Usage
import org.gradle.api.attributes.java.TargetJvmVersion
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

// THE FLOOR, SAID OUT LOUD IN THE METADATA.
//
// A plain `kotlin("jvm")` module gets this for free — the java plugin derives `org.gradle.jvm.version`
// from the toolchain — but a Kotlin Multiplatform module publishes its jvm variants with no such
// attribute at all, and those are the ones consumers actually take. Gradle then has nothing to refuse
// a too-old consumer with: resolution succeeds, compilation succeeds, and the failure arrives at class
// loading as UnsupportedClassVersionError, naming a bytecode version rather than this library.
//
// Declared from a property rather than left to the toolchain because the toolchain moving is precisely
// the event this exists to catch: with the attribute, a consumer gets "requires JVM runtime 17, you
// are on 11" at resolution time; without it, the floor moves silently with whatever JDK the build
// machine has. It cost one repository every consumer on 21, and cost it the only check that can see an
// `implementation` which should have been `api` — the tool that runs that check compiles a consumer on
// 21 and could not resolve the library at 25.
plugins.withId("org.jetbrains.kotlin.multiplatform") {
    val floor = SborkaSettings.jvmFloor(project)
    afterEvaluate {
        // FOUND BY WHAT A CONFIGURATION IS RATHER THAN BY WHAT IT IS CALLED. The obvious version of
        // this named `jvmApiElements` and `jvmRuntimeElements`, which covers a `jvm()` target and
        // misses `jvm("desktop")` entirely — six Compose modules, the ones a client application
        // actually depends on. A configuration's name comes from its target, so a name is not a
        // property of the thing being looked for; the java-api/java-runtime usage is, and only a jvm
        // target carries it — every other target of a multiplatform module publishes kotlin-api.
        configurations
            .filter { configuration ->
                configuration.isCanBeConsumed &&
                    configuration.attributes.getAttribute(Category.CATEGORY_ATTRIBUTE)?.name == Category.LIBRARY &&
                    configuration.attributes
                        .getAttribute(Usage.USAGE_ATTRIBUTE)
                        ?.name in setOf(Usage.JAVA_API, Usage.JAVA_RUNTIME)
            }.forEach { configuration ->
                configuration.attributes.attribute(TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE, floor)
            }
    }
}

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
                        url.set("https://github.com/$developerId")
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
