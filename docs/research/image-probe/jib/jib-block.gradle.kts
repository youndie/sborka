// The Jib configuration every Gradle arm of B-29 appends, identical across arms so that the arms
// differ only in where it is applied. `native-image/` is what sborka's `stageNativeImage` fills.
// The base is cc-debian13 by the digest in ../bases.lock.
jib {
    from { image = "gcr.io/distroless/cc-debian13@sha256:4594d59540d1948417f6ca2829ddd9294493a7c68b7528f4dd459de7f203a750" }
    to { image = "image-probe/keel-jib" }
    container {
        entrypoint = listOf("/app/keel")
        environment = mapOf("MALLOC_ARENA_MAX" to "2")
        ports = listOf("8080")
    }
    extraDirectories {
        paths { path { setFrom(NATIVE_IMAGE_DIR); into = "/app" } }
        permissions = mapOf("/app/keel" to "755")
    }
}
