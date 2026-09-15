package io.github.youndie.sborka.internal

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The reference image, held to the things in it that were measured rather than chosen.
 *
 * `writeNativeDockerfile` puts this text on disk once and then it belongs to the repository, so this
 * does not guard anybody's file — it guards the text every new service starts from. Two lines in it
 * are the result of a measurement somebody paid for, and both are of the kind that gets tidied away
 * by a reader who does not know why they are there: they cost nothing visible and protect against a
 * failure that happens under a container limit, weeks later, in somebody else's afternoon.
 */
class NativeImageReferenceTest {
    private val dockerfile = NativeImageReference.dockerfile(module = "server", binary = "svc")

    @Test
    fun `the runtime stage caps glibc's arenas`() {
        // The number is deliberately asserted, not just the variable: MALLOC_ARENA_MAX=8 would look
        // like the setting is there while leaving the ceiling this exists to remove.
        assertTrue(
            dockerfile.lines().any { it.trim() == "ENV MALLOC_ARENA_MAX=2" },
            "the reference image no longer caps glibc's arenas:\n$dockerfile",
        )
    }

    @Test
    fun `nothing is copied beside the binary`() {
        // `--as-needed` in `sborka.kmp` is what made this possible. If a COPY of a shared library
        // comes back, the pairing rule comes back with it — the builder's glibc must then be no newer
        // than the runtime's — and that rule is the one this file used to carry a paragraph about.
        val copies = dockerfile.lines().filter { it.trimStart().startsWith("COPY") }
        assertTrue(
            copies.size == 2 && copies.any { it.contains("native-image") },
            "the reference image copies something beside the binary: $copies",
        )
    }
}
