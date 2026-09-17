// The same convention, with TWO native targets — the case that used to fail (#80).
//
// A separate module rather than a second target on `:native-service`, because the layout the
// convention produces depends on how many targets there are and both halves have to be exercised:
// one target stages `build/native-image/<baseName>`, which is what every service in the portfolio
// has today, and two stage `build/native-image/<konanTarget>/<baseName>`, which is what the next one
// to add an architecture gets. A stand with only the second would let the first rot.
//
// It costs two release links, measured at 9 s for the pair on an apple machine — which is what makes
// a whole module affordable for one branch of one `if`.

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    id("io.github.youndie.sborka.native-service")
    // The budget property in `stand/gradle.properties` is repository-wide, and the convention
    // refuses a module that sets one with nothing to enforce it. razves 0.1.0.31 is also the version
    // that stopped assuming a single native target — the same defect as this one, one layer up.
    alias(libs.plugins.razves)
}

nativeService {
    entryPoint = "stand.multi.main"
    baseName = "stand-multi"
}

// TWO TARGETS THE HOST CAN LINK. On an apple machine that is both macOS architectures; on Linux,
// both Linux ones — a mac cannot produce an ELF and a Linux runner cannot produce a Mach-O.
kotlin {
    val mac = System.getProperty("os.name").startsWith("Mac")
    if (mac) {
        macosArm64()
        macosX64()
    } else {
        linuxX64()
        linuxArm64()
    }
}

// ONE STAGED BINARY PER TARGET, EACH UNDER ITS OWN KONAN NAME.
//
// The failure this module exists for was silent in the worst way available: with both binaries
// renamed into one directory Gradle refused outright (`Entry stand-multi is a duplicate`), and the
// one-line fix — a duplicates strategy — would have staged one of the two under a name that says
// nothing about which. So the check counts them and reads the directory names.
val verifyStagedImages =
    tasks.register("verifyStagedImages") {
        group = "verification"
        description = "Checks that two native targets stage two binaries, each under its konan target"
        dependsOn("stageNativeImage")
        outputs.upToDateWhen { false }

        val imageDir = layout.buildDirectory.dir("native-image")
        val name = nativeService.baseName
        val expected = if (System.getProperty("os.name").startsWith("Mac")) {
            setOf("macos_arm64", "macos_x64")
        } else {
            setOf("linux_x64", "linux_arm64")
        }
        doLast {
            val root = imageDir.get().asFile
            val staged =
                root
                    .walkTopDown()
                    .maxDepth(2)
                    .filter { it.isFile && it.name == name.get() }
                    .toList()

            check(staged.size == expected.size) {
                "expected ${expected.size} staged binaries, found ${staged.size}: " +
                    staged.joinToString { it.relativeTo(root).path }
            }
            val directories = staged.map { it.parentFile.name }.toSet()
            check(directories == expected) {
                "staged under $directories, expected $expected — the segment must be the konan target, " +
                    "not the Kotlin target's directory under `bin/`"
            }
            staged.forEach { check(it.length() > 1024) { "${it.path} is ${it.length()} bytes" } }

            logger.lifecycle("verifyStagedImages: ${staged.size} binaries under ${directories.sorted()}")
        }
    }

tasks.named("check") { dependsOn(verifyStagedImages) }
