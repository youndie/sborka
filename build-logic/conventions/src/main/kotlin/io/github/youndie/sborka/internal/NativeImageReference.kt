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

        # `distroless/cc` and not `distroless/base`, and the reason is not glibc: Kotlin/Native's
        # exception handling imports thirteen `_Unwind_*` symbols from `libgcc_s`, which `base` does not
        # carry. `base` fails at exec with `cannot open shared object file`.
        FROM gcr.io/distroless/cc-debian13
        # `ca-certificates` is not a library, so `ldd` on the binary will never name it. Without it
        # every outbound TLS call fails with a message about a certificate path and nothing about this
        # line. distroless/cc carries them already — kept as a comment because the first thing anyone
        # does with this file is swap the base image.
        #
        # NOTHING ELSE IS COPIED, and that is load-bearing. A Kotlin/Native binary declares
        # `libcrypt.so.1`, which this base image does not have, and imports nothing from it; older
        # versions of this file copied it out of the builder and carried a rule that the builder's glibc
        # must be no newer than the runtime's, because that copied file is glibc-coupled. `sborka.kmp`
        # links Linux executables with `--as-needed`, the declaration goes away, and so does the rule.
        # If this image ever fails with `cannot open shared object file`, the answer is that the
        # convention did not apply — not another COPY line.
        # THE SECOND ALLOCATOR, one floor below the one `sborka.native-service` caps in the binary.
        # Underneath Kotlin/Native's allocator sits glibc's malloc, and it hands a thread an arena of
        # its own whenever the one it wants is busy — up to eight per HOST core. It does not see the
        # container's quota, so `--cpus=1` on a twenty-core runner still allows 160 arenas, each
        # returning pages only from its top: resident memory becomes the sum of every arena's
        # high-water mark. In tracy that was 120 MB of 130, visible in `smaps` as 6-12 MB anonymous
        # mappings on 64 MB boundaries.
        #
        # 2 IS A MEASURED NUMBER AND IT DOES NOT TRANSFER BY ITSELF. On katcher it took the peak from
        # 65.3 MB to 62.8 MB; on a service without a database it went the other way. Measure on the
        # service that will ship it — with a positive control, since a harness that cannot detect a
        # regression reports its absence — and change the line here if the measurement says so. What
        # is not worth doing is leaving it unset because nobody measured: the default is the number
        # that gets a service killed under a limit.
        ENV MALLOC_ARENA_MAX=2
        COPY --from=build /app/$module/build/native-image/$binary /app/$binary
        ENTRYPOINT ["/app/$binary"]
        """.trimIndent() + "\n"
}
