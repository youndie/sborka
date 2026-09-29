package check

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.system.exitProcess

/*
 * B-30's prototype. Two ways to name the image:
 *
 *   load-check --binary <file> --at /app/keel --base <registry/name@sha256:…|scratch>
 *       the base pulled from its registry by digest, no daemon, and the binary put at --at —
 *       what a build knows before it pushes
 *   load-check --image-tar <docker save output> [--at /app/keel]
 *       a whole image as built, for corpus rows made by a Dockerfile; --at defaults to the
 *       config's entrypoint
 *
 * Exit: 0 loads, 1 does not, 2 the check itself could not run. Never 0 for "could not tell".
 */
fun main(args: Array<String>) {
    val opts = args.toList().chunked(2).associate { it[0] to it.getOrNull(1) }
    val result = runCatching { check(opts) }
    result.exceptionOrNull()?.let {
        System.err.println("load-check: could not run — ${it.message ?: it}")
        exitProcess(2)
    }
    exitProcess(result.getOrThrow())
}

private fun check(opts: Map<String, String?>): Int {
    val fs = ImageFs()
    val image: Image
    val at: String
    when {
        opts["--image-tar"] != null -> {
            image = SavedImage.read(File(opts["--image-tar"]!!))
            image.layers.forEach(fs::apply)
            at = opts["--at"] ?: entrypoint(image.config) ?: error("no --at and no entrypoint in the image config")
        }
        opts["--binary"] != null -> {
            val base = opts["--base"] ?: error("--base is required with --binary")
            image = if (base == "scratch") Image(emptyList(), null, "scratch") else Registry.pull(base)
            image.layers.forEach(fs::apply)
            at = opts["--at"] ?: error("--at is required with --binary")
            fs.put(at, File(opts["--binary"]!!).readBytes())
        }
        else -> error("--image-tar <file> or --binary <file> --at <path> --base <ref>")
    }
    val env = env(image.config)

    println("load-check: $at in ${image.description} (${image.layers.size} layers)")
    val r = LoadCheck(fs, env).run(at)
    r.resolved.forEach { println("  ok      $it") }
    r.warnings.forEach { println("  note    $it") }
    r.problems.forEach { println("  FAIL    ${it.line}") }
    println(
        "VERDICT ${
            if (r.problems.isEmpty()) {
                "loads"
            } else {
                r.problems.joinToString("; ") { it.line.substringBefore(" —") }
            }
        }",
    )
    println("not checked: dlopen targets (gconv, NSS), CA certificates, time zones — no ELF entry names them")
    return if (r.problems.isEmpty()) 0 else 1
}

private fun entrypoint(config: JsonObject?): String? =
    config?.get("config")?.jsonObject?.get("Entrypoint")?.jsonArray?.firstOrNull()?.jsonPrimitive?.content

private fun env(config: JsonObject?): Map<String, String> =
    config?.get("config")?.jsonObject?.get("Env")?.jsonArray?.associate {
        val s = it.jsonPrimitive.content
        s.substringBefore('=') to s.substringAfter('=', "")
    } ?: emptyMap()
