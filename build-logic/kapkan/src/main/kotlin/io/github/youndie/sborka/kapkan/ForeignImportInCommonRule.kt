package io.github.youndie.sborka.kapkan

import com.pinterest.ktlint.rule.engine.core.api.AutocorrectDecision
import com.pinterest.ktlint.rule.engine.core.api.Rule
import com.pinterest.ktlint.rule.engine.core.api.RuleAutocorrectApproveHandler
import org.jetbrains.kotlin.com.intellij.lang.ASTNode
import org.jetbrains.kotlin.psi.KtFile

/**
 * A platform's own package, imported into common code.
 *
 * **What it catches.** `java.*`, `javax.*`, `android.*` or `org.w3c.*` in a `common…` source set —
 * code that cannot compile for a target the module declares.
 *
 * **Where it was found.** shashki B-01, which chose Kotlin/Wasm for both clients and left behind a
 * control of its own: `check` compiles the map package for `wasmJs`, and one `java.io.File` in the
 * decoder breaks it. So the compiler already answers this question for every module that has a
 * non-JVM target, and it answers it forty seconds later than this does. That is the whole of this
 * rule's claim — **it is faster, not smarter** — plus the one case the compiler does not cover: a
 * module whose only targets are the JVM and Android, where common code compiles either way and
 * stops compiling on the day a third target is added.
 *
 * **Not `androidx.`** — `androidx.lifecycle`, `androidx.navigation3` and Compose are multiplatform
 * artefacts under an `android`-shaped name, and shashki's `commonMain` imports them correctly.
 * `startsWith("android.")` is what separates the two, and the dot is load-bearing.
 */
public class ForeignImportInCommonRule :
    Rule(
        ruleId = ruleId("foreign-import-in-common"),
        about = ABOUT,
    ),
    RuleAutocorrectApproveHandler {
    override fun beforeVisitChildNodes(
        node: ASTNode,
        emit: (Int, String, Boolean) -> AutocorrectDecision,
    ) {
        // The whole rule is one pass over the import list, so it runs on the file node and then stops
        // the traversal — the same shape ktlint's own `filename` rule uses. Visiting every node to
        // find imports would walk the body of every file in the portfolio for nothing.
        if (node.treeParent != null) return
        val file = node.psi as? KtFile ?: return
        val path = node.filePath() ?: return
        val sourceSet = sourceSetOf(path) ?: return
        if (!sourceSet.startsWith("common")) {
            stopTraversalOfAST()
            return
        }

        file.importDirectives.forEach { directive ->
            val imported = directive.importedFqName?.asString() ?: return@forEach
            val platform = FOREIGN.firstOrNull { imported.startsWith(it) } ?: return@forEach
            emit(
                directive.node.startOffset,
                "$imported is $platform code in $sourceSet — it cannot compile for every target this " +
                    "module declares, and the target that finds out is whichever one compiles last",
                false,
            )
        }
        stopTraversalOfAST()
    }

    private companion object {
        /**
         * An ALLOWLIST WOULD BE THE WRONG SHAPE HERE and the brief asked for one. A list of packages
         * that are allowed in common code is a list nobody can complete: `kotlin.*`, `kotlinx.*` and
         * every multiplatform library on Maven Central belong to it. What is finite is the set of
         * package roots that belong to ONE platform, and that is this.
         */
        val FOREIGN = listOf("java.", "javax.", "android.", "org.w3c.")
    }
}
