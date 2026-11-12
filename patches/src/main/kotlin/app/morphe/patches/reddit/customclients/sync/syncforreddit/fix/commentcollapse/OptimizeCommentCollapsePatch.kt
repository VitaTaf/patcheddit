package app.morphe.patches.reddit.customclients.sync.syncforreddit.fix.commentcollapse

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.extensions.InstructionExtensions.removeInstructions
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.all.misc.PatchCategory
import app.morphe.patches.reddit.customclients.AppCompatibility
import app.morphe.util.indexOfFirstInstructionOrThrow
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

/**
 * Sync collapses a comment tree by walking the comments Cursor. For every child row it calls the
 * full Post cursor parser merely to read the row's nesting level and id. That parser reads roughly
 * one hundred columns and constructs a complete Post object, all on the UI thread.
 *
 * Read the two required Cursor columns directly instead. The surrounding traversal, provider
 * update, collapse animation, and notification behavior remain unchanged.
 */
@Suppress("unused")
val optimizeCommentCollapsePatch = bytecodePatch(
    name = "Optimize comment collapsing",
    description = "Makes collapsing comment chains respond faster by avoiding full comment parsing for every hidden reply.",
    default = true,
) {
    category(PatchCategory.COMMENTS)
    compatibleWith(*AppCompatibility.SyncForReddit)

    execute {
        CollapseCommentTreeFingerprint.method.apply {
            val commentType = parameterTypes[0].toString()

            val parseCommentIndex = indexOfFirstInstructionOrThrow {
                if (opcode != Opcode.INVOKE_STATIC) return@indexOfFirstInstructionOrThrow false
                val reference = (this as? ReferenceInstruction)?.reference as? MethodReference
                    ?: return@indexOfFirstInstructionOrThrow false

                reference.definingClass == commentType &&
                    reference.returnType == commentType &&
                    reference.parameterTypes.map { it.toString() } ==
                        listOf("Landroid/database/Cursor;", "I")
            }

            // Original sequence:
            //   invoke-static {v4, v2}, Comment.fromCursor(Cursor, int)
            //   move-result-object v4
            //   invoke-virtual {v4}, Comment.level()
            //   move-result v5
            //
            // v4 already contains the Cursor immediately before this block. The original
            // Comment.fromCursor(Cursor, position) call also moves that shared Cursor to v2;
            // preserve that side effect before reading the two fields directly.
            removeInstructions(parseCommentIndex, 4)
            addInstructions(
                parseCommentIndex,
                """
                    invoke-interface {v4, v2}, Landroid/database/Cursor;->moveToPosition(I)Z
                    const-string v5, "level"
                    invoke-interface {v4, v5}, Landroid/database/Cursor;->getColumnIndex(Ljava/lang/String;)I
                    move-result v5
                    invoke-interface {v4, v5}, Landroid/database/Cursor;->getInt(I)I
                    move-result v5
                """,
            )

            // Find the subsequent no-argument String getter on the comment model (the child id getter),
            // then read _id directly from the Cursor still held in v4.
            val childIdIndex = instructions.withIndex()
                .drop(parseCommentIndex + 6)
                .first { (_, instruction) ->
                    if (instruction.opcode != Opcode.INVOKE_VIRTUAL) return@first false
                    val reference = (instruction as? ReferenceInstruction)?.reference as? MethodReference
                        ?: return@first false

                    reference.definingClass == commentType &&
                        reference.returnType == "Ljava/lang/String;" &&
                        reference.parameterTypes.isEmpty()
                }
                .index

            removeInstructions(childIdIndex, 2)
            addInstructions(
                childIdIndex,
                """
                    const-string v5, "_id"
                    invoke-interface {v4, v5}, Landroid/database/Cursor;->getColumnIndex(Ljava/lang/String;)I
                    move-result v5
                    invoke-interface {v4, v5}, Landroid/database/Cursor;->getString(I)Ljava/lang/String;
                    move-result-object v4
                """,
            )
        }
    }
}
