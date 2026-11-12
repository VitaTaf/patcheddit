package app.morphe.patches.reddit.customclients.sync.syncforreddit.fix.commentloading

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.all.misc.PatchCategory
import app.morphe.patches.reddit.customclients.AppCompatibility
import app.morphe.patches.reddit.customclients.sync.syncforreddit.fix.morecomments.fixMoreComments
import app.morphe.util.indexOfFirstInstructionOrThrow
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference

private const val SETTINGS =
    "Lcom/laurencedawson/reddit_sync/singleton/SettingsSingleton;"
private const val SETTINGS_VALUES =
    "Lcom/laurencedawson/reddit_sync/singleton/SettingsSingleton${'$'}Settings;"

/**
 * Sync stores "All" as commentsCount == 0, but translates that to a single limit=500 request.
 * Reddit can still return MoreComments placeholders inside that response, so "All" stops there.
 *
 * Make "All" progressive instead: request Reddit's maximum initial tree, then let
 * CommentsPresenterImpl serially expand the remaining MoreComments rows using Sync's normal
 * load-more request path. Only one expansion is started per cursor refresh, so the existing
 * request lifecycle remains the serialization primitive.
 */
@Suppress("unused")
val improveCommentLoadingPatch = bytecodePatch(
    name = "Improve loading all comments",
    description = "Makes the All comment-count option load remaining comments automatically and show the first comments sooner.",
    default = true,
) {
    category(PatchCategory.COMMENTS)
    dependsOn(fixMoreComments)
    compatibleWith(*AppCompatibility.SyncForReddit)

    execute {
        // Keep Reddit's maximum initial batch, but explicitly request the full normal tree depth.
        // Remaining MoreComments are drained automatically below.
        InitialCommentsUrlFingerprint.method.apply {
            val allLimitIndex = indexOfFirstInstructionOrThrow {
                (this as? ReferenceInstruction)?.reference.let { reference ->
                    reference is StringReference && reference.string == "500"
                }
            }
            val register = (getInstruction(allLimitIndex) as OneRegisterInstruction).registerA
            replaceInstruction(
                allLimitIndex,
                "const-string v$register, \"500&depth=10\"",
            )
        }

        // Mark Sync's load-more requests with depth=10 only while commentsCount == 0. The shared
        // /api/morechildren shim recognizes this and requests a larger focused subtree from Reddit.
        MoreCommentsRequestConstructorFingerprint.method.apply {
            val mapField = instructions
                .asSequence()
                .filter { it.opcode == Opcode.IGET_OBJECT }
                .mapNotNull { (it as? ReferenceInstruction)?.reference as? FieldReference }
                .first { it.type == "Ljava/util/Map;" }

            val apiTypeIndex = indexOfFirstInstructionOrThrow {
                (this as? ReferenceInstruction)?.reference.let { reference ->
                    reference is StringReference && reference.string == "api_type"
                }
            }
            val putIndex = instructions.withIndex()
                .drop(apiTypeIndex)
                .first { (_, instruction) ->
                    ((instruction as? ReferenceInstruction)?.reference as? MethodReference)?.let {
                        it.definingClass == "Ljava/util/Map;" && it.name == "put"
                    } == true
                }
                .index

            addInstructionsWithLabels(
                putIndex + 1,
                """
                    invoke-static {}, $SETTINGS->x()$SETTINGS_VALUES
                    move-result-object v0
                    iget v0, v0, $SETTINGS_VALUES->commentsCount:I
                    if-nez v0, :morphe_comment_loading_not_all
                    iget-object v0, p0, $mapField
                    const-string v1, "depth"
                    const-string v4, "10"
                    invoke-interface {v0, v1, v4}, Ljava/util/Map;->put(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;
                    :morphe_comment_loading_not_all
                    nop
                """,
            )
        }

        // PostParserHelper compiles the Giphy regex once per comment. Most comments cannot match it,
        // so skip the regex/matcher block unless the raw body contains Sync's Giphy marker.
        CommentPostParserFingerprint.method.apply {
            val patternIndex = indexOfFirstInstructionOrThrow {
                ((this as? ReferenceInstruction)?.reference as? StringReference)?.string
                    ?.let { it.startsWith("\\[gif") && it.contains("giphy") && it.contains("(.*?)") } == true
            }
            val patternRegister =
                (getInstruction(patternIndex) as OneRegisterInstruction).registerA

            val matcherInstruction = instructions.withIndex()
                .drop(patternIndex)
                .first { (_, instruction) ->
                    ((instruction as? ReferenceInstruction)?.reference as? MethodReference)?.let {
                        it.definingClass == "Ljava/util/regex/Pattern;" && it.name == "matcher"
                    } == true
                }
                .value as FiveRegisterInstruction
            val bodyRegister = matcherInstruction.registerD

            val renderIndex = instructions.withIndex()
                .drop(patternIndex)
                .first { (_, instruction) ->
                    if (instruction.opcode != Opcode.INVOKE_STATIC) return@first false
                    ((instruction as? ReferenceInstruction)?.reference as? MethodReference)?.let {
                        it.returnType == "Ljava/lang/String;" &&
                            it.parameterTypes.map { type -> type.toString() } ==
                            listOf("Ljava/lang/String;", "Ljava/lang/String;")
                    } == true
                }
                .index

            addInstructionsWithLabels(
                patternIndex,
                """
                    const-string v$patternRegister, "[gif](giphy|"
                    invoke-virtual {v$bodyRegister, v$patternRegister}, Ljava/lang/String;->contains(Ljava/lang/CharSequence;)Z
                    move-result v$patternRegister
                    if-nez v$patternRegister, :morphe_comment_loading_giphy
                    goto/16 :morphe_comment_loading_render
                """,
                ExternalLabel("morphe_comment_loading_giphy", getInstruction(patternIndex)),
                ExternalLabel("morphe_comment_loading_render", getInstruction(renderIndex)),
            )
        }

        val loadMoreMethod = LoadMoreCommentsFingerprint.method
        val presenterType = loadMoreMethod.definingClass
        val commentType = loadMoreMethod.parameterTypes.single().toString()

        val activeMoreRequestField = loadMoreMethod.instructions
            .asSequence()
            .filter { it.opcode == Opcode.IPUT_OBJECT }
            .mapNotNull { (it as? ReferenceInstruction)?.reference as? FieldReference }
            .single { it.definingClass == presenterType }
        val presenterContextField = loadMoreMethod.instructions
            .asSequence()
            .filter { it.opcode == Opcode.IGET_OBJECT }
            .mapNotNull { (it as? ReferenceInstruction)?.reference as? FieldReference }
            .first {
                it.definingClass == presenterType && it.type == "Landroid/content/Context;"
            }

        val loadMoreReference =
            "$presenterType->${loadMoreMethod.name}($commentType)V"

        // OAuthCommentsMoreRequest notifies the database from Volley's parsing thread, before
        // Volley delivers its success callback on the main thread. A Cursor reload can therefore
        // arrive while Sync still considers the previous MoreComments request active. Re-notify
        // after that request field is cleared so progressive All reliably advances to the next
        // placeholder instead of depending on thread timing.
        val moreResponseMethod = MoreCommentsResponseFingerprint.method
        val notifyChangeIndex = moreResponseMethod.indexOfFirstInstructionOrThrow {
            ((this as? ReferenceInstruction)?.reference as? MethodReference)?.let {
                it.definingClass == "Landroid/content/ContentResolver;" &&
                    it.name == "notifyChange"
            } == true
        }
        val commentsNotifyUriField = moreResponseMethod.instructions
            .subList(0, notifyChangeIndex)
            .asReversed()
            .asSequence()
            .filter { it.opcode == Opcode.SGET_OBJECT }
            .mapNotNull { (it as? ReferenceInstruction)?.reference as? FieldReference }
            .first { it.type == "Landroid/net/Uri;" }

        MoreCommentsSuccessFingerprint.method.apply {
            val outerPresenterField = instructions
                .asSequence()
                .filter { it.opcode == Opcode.IGET_OBJECT }
                .mapNotNull { (it as? ReferenceInstruction)?.reference as? FieldReference }
                .first { it.type == presenterType }
            val clearRequestIndex = indexOfFirstInstructionOrThrow {
                opcode == Opcode.IPUT_OBJECT &&
                    (this as? ReferenceInstruction)?.reference == activeMoreRequestField
            }

            addInstructionsWithLabels(
                clearRequestIndex + 1,
                """
                    invoke-static {}, $SETTINGS->x()$SETTINGS_VALUES
                    move-result-object v0
                    iget v0, v0, $SETTINGS_VALUES->commentsCount:I
                    if-nez v0, :morphe_comment_loading_success_done
                    iget-object v0, p0, $outerPresenterField
                    iget-object v0, v0, $presenterContextField
                    invoke-virtual {v0}, Landroid/content/Context;->getContentResolver()Landroid/content/ContentResolver;
                    move-result-object v0
                    sget-object v2, $commentsNotifyUriField
                    const/4 v1, 0x0
                    invoke-virtual {v0, v2, v1}, Landroid/content/ContentResolver;->notifyChange(Landroid/net/Uri;Landroid/database/ContentObserver;)V
                    :morphe_comment_loading_success_done
                    nop
                """,
            )
        }

        // After a fresh comments Cursor is delivered, start exactly one MoreComments expansion.
        // The resulting database notification reloads the Cursor, which starts the next one, and
        // so on. The active request field prevents overlapping expansions.
        CommentsCursorLoadedFingerprint.method.apply {
            val commentFactory = instructions
                .asSequence()
                .filter { it.opcode == Opcode.INVOKE_STATIC }
                .mapNotNull { (it as? ReferenceInstruction)?.reference as? MethodReference }
                .first {
                    it.returnType == commentType &&
                        it.parameterTypes.map { type -> type.toString() } ==
                        listOf("Landroid/database/Cursor;", "I")
                }

            // a1(Cursor, boolean) is immediately followed by e1(boolean). Inject after e1 so the
            // first Cursor has already been handed to the UI before background expansion starts.
            val publishCursorIndex = instructions.withIndex().first { (_, instruction) ->
                ((instruction as? ReferenceInstruction)?.reference as? MethodReference)?.let {
                    it.definingClass == presenterType &&
                        it.returnType == "V" &&
                        it.parameterTypes.map { type -> type.toString() } ==
                        listOf("Landroid/database/Cursor;", "Z")
                } == true
            }.index
            val loadingDoneIndex = instructions.withIndex()
                .drop(publishCursorIndex + 1)
                .first { (_, instruction) ->
                    ((instruction as? ReferenceInstruction)?.reference as? MethodReference)?.let {
                        it.definingClass == presenterType &&
                            it.returnType == "V" &&
                            it.parameterTypes.map { type -> type.toString() } == listOf("Z")
                    } == true
                }
                .index

            addInstructionsWithLabels(
                loadingDoneIndex + 1,
                """
                    if-eqz p1, :morphe_comment_loading_done
                    invoke-interface {p1}, Landroid/database/Cursor;->isClosed()Z
                    move-result v3
                    if-nez v3, :morphe_comment_loading_done

                    invoke-static {}, $SETTINGS->x()$SETTINGS_VALUES
                    move-result-object v3
                    iget v3, v3, $SETTINGS_VALUES->commentsCount:I
                    if-nez v3, :morphe_comment_loading_done

                    iget-object v3, p0, $activeMoreRequestField
                    if-nez v3, :morphe_comment_loading_done

                    invoke-interface {p1}, Landroid/database/Cursor;->getPosition()I
                    move-result v5
                    const-string v3, "sync_type"
                    invoke-interface {p1, v3}, Landroid/database/Cursor;->getColumnIndex(Ljava/lang/String;)I
                    move-result v3
                    if-ltz v3, :morphe_comment_loading_restore

                    const/4 v4, 0x1
                    const/16 v0, 0x9
                    :morphe_comment_loading_scan
                    invoke-interface {p1}, Landroid/database/Cursor;->getCount()I
                    move-result v1
                    if-ge v4, v1, :morphe_comment_loading_restore
                    invoke-interface {p1, v4}, Landroid/database/Cursor;->moveToPosition(I)Z
                    invoke-interface {p1, v3}, Landroid/database/Cursor;->getInt(I)I
                    move-result v1
                    if-ne v1, v0, :morphe_comment_loading_next

                    # A zero-count MoreComments row is Sync's "Continue thread..." sentinel,
                    # which navigates to a focused thread instead of using /api/morechildren.
                    # Do not auto-trigger those; only drain actual "View more" rows.
                    const-string v1, "commentMoreCount"
                    invoke-interface {p1, v1}, Landroid/database/Cursor;->getColumnIndex(Ljava/lang/String;)I
                    move-result v1
                    invoke-interface {p1, v1}, Landroid/database/Cursor;->getInt(I)I
                    move-result v1
                    if-lez v1, :morphe_comment_loading_next

                    invoke-static {p1, v4}, $commentFactory
                    move-result-object v3
                    invoke-interface {p1, v5}, Landroid/database/Cursor;->moveToPosition(I)Z
                    invoke-virtual {p0, v3}, $loadMoreReference
                    const/4 v0, 0x1
                    goto :morphe_comment_loading_done

                    :morphe_comment_loading_next
                    add-int/lit8 v4, v4, 0x1
                    goto :morphe_comment_loading_scan

                    :morphe_comment_loading_restore
                    invoke-interface {p1, v5}, Landroid/database/Cursor;->moveToPosition(I)Z
                    const/4 v0, 0x1
                    :morphe_comment_loading_done
                    nop
                """,
            )
        }
    }
}
