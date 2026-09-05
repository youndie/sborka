package io.github.youndie.sborka.internal

/**
 * The reference Dockerfile for a Kotlin/Native service.
 *
 * A template with two holes rather than a resource copied verbatim: the module path and the binary
 * name are the only two things that differ between the repositories that already ship one, and the
 * rest — builder image, runtime image, the library and certificate lines — is the part worth being
 * identical.
 */
object NativeImageReference {
    fun dockerfile(
        module: String,
        binary: String,
    ): String =
        """
        # Two stages, and the pair is chosen together: a binary linked against the builder's glibc will
        # not start on a runtime with an older one, and the failure is the container exiting before any
        # of the application's own logging has run.
        FROM --platform=linux/amd64 gradle:9.7.1-jdk25-noble AS build
        WORKDIR /app
        COPY . .
        # The Kotlin/Native toolchain is ~1 GB and is downloaded on a cold build. Cached across image
        # builds rather than fetched every time; `sharing=locked` because two concurrent builds writing
        # the same cache corrupt it.
        RUN --mount=type=cache,target=/root/.konan,sharing=locked \
            ./gradlew :$module:stageNativeImage --no-daemon

        FROM gcr.io/distroless/cc-debian13
        # `ca-certificates` is not a library, so `ldd` on the binary will never name it. Without it
        # every outbound TLS call fails with a message about a certificate path and nothing about this
        # line. distroless/cc carries them already — kept as a comment because the first thing anyone
        # does with this file is swap the base image.
        COPY --from=build /app/$module/build/native-image/$binary /app/$binary
        ENTRYPOINT ["/app/$binary"]
        """.trimIndent() + "\n"
}
