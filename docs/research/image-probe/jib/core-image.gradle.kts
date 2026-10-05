// B-29 arm 3: no Jib plugin, no `java` — jib-core, the library under the plugin, called from one
// ordinary task. This is the shape a `publishImage` plugin would have, so what it costs here is what
// writing one would cost. Applied as the whole of a new `:image` module's build file.
import com.google.cloud.tools.jib.api.Containerizer
import com.google.cloud.tools.jib.api.Jib
import com.google.cloud.tools.jib.api.TarImage
import com.google.cloud.tools.jib.api.buildplan.AbsoluteUnixPath
import com.google.cloud.tools.jib.api.buildplan.FileEntriesLayer
import com.google.cloud.tools.jib.api.buildplan.FilePermissions
import com.google.cloud.tools.jib.api.buildplan.Port

buildscript {
    repositories { mavenCentral() }
    dependencies { classpath("com.google.cloud.tools:jib-core:0.28.2") }
}

tasks.register("imageTar") {
    dependsOn(":server:stageNativeImage")
    // EVERYTHING THE ACTION READS IS A LOCAL OF THIS BLOCK. The first run had `base` at script level
    // and the configuration cache refused the task — "cannot serialize Gradle script object
    // references" — the same defect sborka's stageNativeImage met in #76.
    val base = "gcr.io/distroless/cc-debian13@sha256:4594d59540d1948417f6ca2829ddd9294493a7c68b7528f4dd459de7f203a750"
    val binary = rootProject.file("server/build/native-image/keel")
    val tar = layout.buildDirectory.file("image.tar").get().asFile
    val digestFile = layout.buildDirectory.file("image.digest").get().asFile
    inputs.file(binary)
    inputs.property("base", base)
    outputs.files(tar, digestFile)
    doLast {
        val layer =
            FileEntriesLayer.builder()
                .setName("binary")
                .addEntry(binary.toPath(), AbsoluteUnixPath.get("/app/keel"), FilePermissions.fromOctalString("755"))
                .build()
        val container =
            Jib.from(base)
                .addFileEntriesLayer(layer)
                .setEntrypoint("/app/keel")
                .addEnvironmentVariable("MALLOC_ARENA_MAX", "2")
                .addExposedPort(Port.tcp(8080))
                .containerize(Containerizer.to(TarImage.at(tar.toPath()).named("image-probe/keel-core")))
        digestFile.writeText(container.digest.toString())
        println("imageTar: ${container.digest} (id ${container.imageId})")
    }
}
