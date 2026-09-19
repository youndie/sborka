package stand.ksp

import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.processing.SymbolProcessorProvider
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSClassDeclaration

/**
 * The smallest processor that can prove the wiring: it lists what carries `@Marked` and writes one
 * file into `commonMain`.
 *
 * It is here because the thing being checked is not a processor at all — it is whether
 * `sborka.kmp` makes the generated file compile, and makes everything that reads the directory wait
 * for it. Any processor would do; this one has no dependencies to age and nothing to debug when the
 * wiring is what broke.
 */
class StandProcessorProvider : SymbolProcessorProvider {
    override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor = StandProcessor(environment.codeGenerator)
}

class StandProcessor(
    private val codeGenerator: CodeGenerator,
) : SymbolProcessor {
    private var written = false

    override fun process(resolver: Resolver): List<KSAnnotated> {
        if (written) return emptyList()

        val marked =
            resolver
                .getSymbolsWithAnnotation("stand.ksp.Marked")
                .filterIsInstance<KSClassDeclaration>()
                .map { it.simpleName.asString() }
                .toList()
                .sorted()
        if (marked.isEmpty()) return emptyList()

        written = true
        // `public`, spelled out: the module takes `explicitApi()` from the convention, and generated
        // code is compiled by the same compiler as everything else. A processor that forgets it fails
        // the module it is wired into and not itself.
        codeGenerator
            .createNewFile(Dependencies(aggregating = true), "stand.ksp", "StandRegistry")
            .use { out ->
                out.write(
                    (
                        "package stand.ksp\n\n" +
                            "public val standRegistry: List<String> = listOf(" +
                            marked.joinToString { "\"$it\"" } +
                            ")\n"
                    ).toByteArray(),
                )
            }
        return emptyList()
    }
}
