plugins {
    `version-catalog`
    `maven-publish`
    alias(libs.plugins.ktlint)
}

ktlint {
    version.set(libs.versions.ktlintTool)
}

group = "ru.workinprogress.sborka"
version = providers.gradleProperty("VERSION").orElse(providers.gradleProperty("sborka.version")).get()

catalog {
    versionCatalog {
        from(files("sborka.versions.toml"))
    }
}

publishing {
    publications.create<MavenPublication>("catalog") {
        artifactId = "catalog"
        from(components["versionCatalog"])
    }

    repositories {
        // A directory, so CI can publish the catalog and then resolve it from a second invocation of
        // the stand. That second run is the only place the coordinate in `sborka.settings` is checked
        // against a catalog that actually exists.
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
