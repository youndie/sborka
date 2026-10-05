package io.github.youndie.sborka.image

import java.io.File
import kotlin.system.exitProcess

/*
 * The check as a command, for the corpus scorer. Two ways to name the image:
 *
 *   load-check --binary <file> --at /app/keel --base <registry/name@sha256:…|scratch>
 *       the base pulled from its registry by digest, no daemon, and the binary put at --at — what a
 *       build knows before it pushes
 *   load-check --image-tar <docker save output> [--at /app/keel]
 *       a whole image as built; --at defaults to the config's entrypoint
 *
 * Exit: 0 loads, 1 does not, 2 the check itself could not run. Never 0 for "could not tell".
 */
public fun main(args: Array<String>) {
    val opts = args.toList().chunked(2).associate { it[0] to it.getOrNull(1) }
    val code =
        try {
            run(opts)
        } catch (e: Exception) {
            System.err.println("load-check: could not run — ${e.message ?: e}")
            2
        }
    exitProcess(code)
}

private fun run(opts: Map<String, String?>): Int {
    val fs = ImageFs()
    val image: Image
    val at: String
    val tar = opts["--image-tar"]
    val binary = opts["--binary"]
    when {
        tar != null -> {
            image = SavedImage.read(File(tar))
            image.layers.forEach(fs::apply)
            at = opts["--at"] ?: image.entrypoint ?: error("no --at and no entrypoint in the image config")
        }

        binary != null -> {
            val base = opts["--base"] ?: error("--base is required with --binary")
            image = if (base == "scratch") Image.scratch() else Registry.pull(base)
            image.layers.forEach(fs::apply)
            at = opts["--at"] ?: error("--at is required with --binary")
            fs.put(at, File(binary).readBytes())
        }

        else -> {
            error("--image-tar <file>, or --binary <file> --at <path> --base <ref>")
        }
    }

    println("load-check: $at in ${image.description} (${image.layers.size} layers)")
    val r = LoadCheck(fs, image.environment).run(at)
    r.resolved.forEach { println("  ok      $it") }
    r.notes.forEach { println("  note    $it") }
    r.problems.forEach { println("  FAIL    ${it.line}") }
    println("VERDICT ${r.verdict}")
    println(LoadCheck.NOT_CHECKED)
    return if (r.loads) 0 else 1
}
