package io.github.youndie.sborka

import com.google.cloud.tools.jib.api.Containerizer
import com.google.cloud.tools.jib.api.Jib
import com.google.cloud.tools.jib.api.TarImage
import com.google.cloud.tools.jib.api.buildplan.AbsoluteUnixPath
import com.google.cloud.tools.jib.api.buildplan.FileEntriesLayer
import com.google.cloud.tools.jib.api.buildplan.FilePermissions
import com.google.cloud.tools.jib.api.buildplan.Port
import io.github.youndie.sborka.image.ImageFs
import io.github.youndie.sborka.image.LoadCheck
import io.github.youndie.sborka.image.Registry
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import org.gradle.workers.WorkAction
import org.gradle.workers.WorkParameters
import org.gradle.workers.WorkerExecutor
import javax.inject.Inject

private const val NOT_CACHED =
    "It reaches a registry, and its output is an image tens of megabytes large that jib-core rebuilds in " +
        "seconds from the base layers it caches already — a build-cache entry would cost more than it saves."

/** What `sborka.native-service` builds an image from: `nativeImage { base = "…@sha256:…" }`. */
public interface NativeImageExtension {
    /**
     * The base image, BY DIGEST — `gcr.io/distroless/cc-debian13@sha256:…`. A tag is refused: a load
     * check against a tag is a verdict about whatever the tag pointed at that minute.
     */
    public val base: Property<String>

    /** The name the tarball's image carries. Defaults to the project name and version. */
    public val imageName: Property<String>

    /**
     * The environment. Carries `MALLOC_ARENA_MAX=2` by default, the line sborka's reference
     * Dockerfile carries and explains; a service that changes allocator re-measures it.
     */
    public val environment: MapProperty<String, String>

    /** Ports the image declares. Documentation for a runtime, not a firewall. */
    public val ports: ListProperty<Int>

    /** OCI labels. The convention sets version and revision from the build and git. */
    public val labels: MapProperty<String, String>
}

/**
 * A Kotlin/Native service's image, built with no Docker daemon and refused before it is written when
 * the base cannot load the binary (B-33).
 *
 * In order: the base must be pinned by digest; the base is pulled and the load check (`:image`, B-37)
 * resolves the binary's interpreter, libraries and symbol versions against it; only then does jib-core
 * write the tarball. The check's report — always ending with what it did not check — is written
 * either way.
 *
 * The work runs in a worker with a classloader of its own ([workerClasspath]): jib-core and the check
 * are not on the buildscript classpath this task is loaded from.
 */
@DisableCachingByDefault(because = NOT_CACHED)
public abstract class NativeImage : DefaultTask() {
    /** The staged ELF binary. */
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    public abstract val binary: RegularFileProperty

    @get:Input
    public abstract val base: Property<String>

    /** Where the binary lands in the image; also the entrypoint, in exec form. */
    @get:Input
    public abstract val binaryPath: Property<String>

    @get:Input
    public abstract val imageName: Property<String>

    @get:Input
    public abstract val environment: MapProperty<String, String>

    @get:Input
    public abstract val ports: ListProperty<Int>

    @get:Input
    public abstract val labels: MapProperty<String, String>

    /** False: check only, write no image. */
    @get:Input
    public abstract val writeImage: Property<Boolean>

    /** False: record a failing verdict in [report] instead of failing the build. For verifying the gate. */
    @get:Input
    public abstract val failOnLoadProblem: Property<Boolean>

    @get:OutputFile
    public abstract val tarball: RegularFileProperty

    @get:OutputFile
    public abstract val report: RegularFileProperty

    @get:Classpath
    public abstract val workerClasspath: ConfigurableFileCollection

    @get:Inject
    protected abstract val workers: WorkerExecutor

    init {
        writeImage.convention(true)
        failOnLoadProblem.convention(true)
    }

    @TaskAction
    public fun build() {
        val reference = base.get()
        if (!reference.contains("@sha256:")) {
            throw GradleException(
                "$path: base '$reference' is not pinned by digest. Name it as <image>@sha256:<digest> — " +
                    "a load check against a tag is about whatever the tag pointed at this minute.",
            )
        }
        workers.classLoaderIsolation { classpath.from(workerClasspath) }.submit(NativeImageWork::class.java) {
            binary.set(this@NativeImage.binary)
            base.set(reference)
            binaryPath.set(this@NativeImage.binaryPath)
            imageName.set(this@NativeImage.imageName)
            environment.set(this@NativeImage.environment)
            ports.set(this@NativeImage.ports)
            labels.set(this@NativeImage.labels)
            writeImage.set(this@NativeImage.writeImage)
            failOnLoadProblem.set(this@NativeImage.failOnLoadProblem)
            tarball.set(this@NativeImage.tarball)
            report.set(this@NativeImage.report)
            taskPath.set(path)
        }
    }
}

/** The parameters of [NativeImageWork]; public only because Gradle instantiates them. */
public interface NativeImageParameters : WorkParameters {
    public val binary: RegularFileProperty
    public val base: Property<String>
    public val binaryPath: Property<String>
    public val imageName: Property<String>
    public val environment: MapProperty<String, String>
    public val ports: ListProperty<Int>
    public val labels: MapProperty<String, String>
    public val writeImage: Property<Boolean>
    public val failOnLoadProblem: Property<Boolean>
    public val tarball: RegularFileProperty
    public val report: RegularFileProperty
    public val taskPath: Property<String>
}

/**
 * The work, loaded in the isolated classloader. It is the only class here whose code touches jib-core or
 * the check, so loading [NativeImage] on a buildscript classpath never needs either.
 */
public abstract class NativeImageWork : WorkAction<NativeImageParameters> {
    override fun execute() {
        val p = parameters
        val at = p.binaryPath.get()
        val binaryFile = p.binary.get().asFile
        val reportFile = p.report.get().asFile
        val tarFile = p.tarball.get().asFile
        reportFile.parentFile.mkdirs()

        val base = Registry.pull(p.base.get())
        val fs = ImageFs()
        base.layers.forEach(fs::apply)
        fs.put(at, binaryFile.readBytes())
        val environment = base.environment + p.environment.get()
        val result = LoadCheck(fs, environment).run(at)

        val report =
            buildString {
                appendLine("load check: $at on ${p.base.get()}")
                result.resolved.forEach { appendLine("  ok      $it") }
                result.notes.forEach { appendLine("  note    $it") }
                result.problems.forEach { appendLine("  FAIL    ${it.line}") }
                appendLine("VERDICT ${result.verdict}")
                appendLine(LoadCheck.NOT_CHECKED)
            }
        reportFile.writeText(report)

        if (!result.loads) {
            // No image is written for a base that cannot load the binary: a stale tarball beside a red
            // verdict is a tarball somebody pushes.
            tarFile.delete()
            if (p.failOnLoadProblem.get()) {
                throw GradleException(
                    "${p.taskPath.get()}: the base cannot load $at — ${result.verdict}\n" +
                        "base ${p.base.get()}\n" +
                        "${LoadCheck.NOT_CHECKED}\nreport: $reportFile",
                )
            }
            return
        }
        if (!p.writeImage.get()) {
            tarFile.delete()
            return
        }

        val layer =
            FileEntriesLayer
                .builder()
                .setName("binary")
                .addEntry(
                    binaryFile.toPath(),
                    AbsoluteUnixPath
                        .get(at),
                    FilePermissions
                        .fromOctalString("755"),
                ).build()
        var container =
            Jib
                .from(p.base.get())
                .addFileEntriesLayer(layer)
                .setEntrypoint(at)
        p.environment.get().forEach { (k, v) -> container = container.addEnvironmentVariable(k, v) }
        p.ports.get().forEach { container = container.addExposedPort(Port.tcp(it)) }
        p.labels.get().forEach { (k, v) -> container = container.addLabel(k, v) }
        tarFile.parentFile.mkdirs()
        val written =
            container.containerize(
                Containerizer
                    .to(
                        TarImage
                            .at(tarFile.toPath())
                            .named(p.imageName.get()),
                    ).setToolName("sborka"),
            )
        reportFile.appendText("image: ${written.digest} written to $tarFile\n")
    }
}
