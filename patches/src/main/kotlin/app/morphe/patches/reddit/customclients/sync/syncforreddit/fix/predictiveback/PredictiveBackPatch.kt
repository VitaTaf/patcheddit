package app.morphe.patches.reddit.customclients.sync.syncforreddit.fix.predictiveback

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.all.misc.PatchCategory
import app.morphe.patches.reddit.customclients.AppCompatibility
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import org.w3c.dom.Element

private const val COMMENTS_ACTIVITY =
    "com.laurencedawson.reddit_sync.ui.activities.CommentsActivity"
private const val PREDICTIVE_BACK_API = 33

/**
 * Opt only CommentsActivity into the modern back dispatcher. Sync bundles an old AndroidX Activity
 * release, so enabling this application-wide can change back handling in unrelated screens.
 */
private val enableCommentsPredictiveBackPatch = resourcePatch(default = false) {
    compatibleWith(*AppCompatibility.SyncForReddit)

    execute {
        document("AndroidManifest.xml").use { document ->
            val activityNodes = document.getElementsByTagName("activity")
            val matches = (0 until activityNodes.length)
                .map { activityNodes.item(it) as Element }
                .filter { it.getAttribute("android:name") == COMMENTS_ACTIVITY }

            require(matches.size == 1) {
                "Expected exactly one Sync CommentsActivity manifest entry, found ${matches.size}"
            }

            matches.single().setAttribute("android:enableOnBackInvokedCallback", "true")
        }
    }
}

private fun modernBackGuard(register: String = "v0") = """
    sget $register, Landroid/os/Build${'$'}VERSION;->SDK_INT:I
    add-int/lit8 $register, $register, -$PREDICTIVE_BACK_API
    if-ltz $register, :morphe_legacy_swipe_back
""".trimIndent()

/**
 * Sync's legacy Swipe to return implementation wraps CommentsActivity in a custom drag view and
 * dynamically converts the activity between opaque and translucent using hidden Activity APIs.
 * On current Android releases that leaves the gesture without a reliable previous-activity surface.
 *
 * Android 13+ has a system back dispatcher and predictive cross-activity animation. On those
 * versions, bypass the custom drag wrapper and its lifecycle hooks and let WindowManager own the
 * gesture. Android 12 and older keep Sync's original swipe implementation unchanged.
 */
@Suppress("unused")
val predictiveBackPatch = bytecodePatch(
    name = "Use predictive back for comments",
    description = "Replaces Sync's broken Swipe to return implementation with Android's predictive back gesture on Android 13 and newer.",
    default = true,
) {
    category(PatchCategory.NAVIGATION)
    dependsOn(enableCommentsPredictiveBackPatch)
    compatibleWith(*AppCompatibility.SyncForReddit)

    execute {
        // BaseMaterialDragActivity.s0() normally creates the custom drag FrameLayout. On Android
        // 13+, set the activity's real layout directly so the system owns the edge-back gesture.
        DragActivitySetContentViewFingerprint.method.apply {
            val layoutMethod = implementation!!.instructions
                .asSequence()
                .filter { it.opcode == Opcode.INVOKE_INTERFACE }
                .mapNotNull { (it as? ReferenceInstruction)?.reference as? MethodReference }
                .single { it.returnType == "I" && it.parameterTypes.isEmpty() }

            addInstructionsWithLabels(
                0,
                """
                    ${modernBackGuard()}
                    invoke-interface {p0}, $layoutMethod
                    move-result v0
                    invoke-virtual {p0, v0}, Landroid/app/Activity;->setContentView(I)V
                    return-void
                """,
                ExternalLabel("morphe_legacy_swipe_back", getInstruction(0)),
            )
        }

        // With no drag controller allocated on Android 13+, skip lifecycle calls that would touch
        // it or schedule the old hidden-API translucency conversion. Each method already performs
        // its required superclass callback as instruction 0; branch after that callback.
        listOf(
            DragActivityOnResumeFingerprint.method,
            DragActivityOnPostResumeFingerprint.method,
            DragActivityOnDestroyFingerprint.method,
        ).forEach { method ->
            method.addInstructionsWithLabels(
                1,
                """
                    ${modernBackGuard()}
                    return-void
                """,
                ExternalLabel("morphe_legacy_swipe_back", method.getInstruction(1)),
            )
        }

        // onStop() calls this helper after super.onStop(). Skip the helper's custom swipe cleanup
        // on modern Android while retaining it unchanged for older releases.
        DragActivityStopSwipeFingerprint.method.apply {
            addInstructionsWithLabels(
                0,
                """
                    ${modernBackGuard()}
                    return-void
                """,
                ExternalLabel("morphe_legacy_swipe_back", getInstruction(0)),
            )
        }

        // The legacy finish() path can convert the activity back to opaque and calls
        // overridePendingTransition(), both of which prevent the system cross-activity preview.
        // On Android 13+, finish normally and let the system select the back transition.
        DragActivityFinishFingerprint.method.apply {
            val superFinish = implementation!!.instructions
                .asSequence()
                .filter { it.opcode == Opcode.INVOKE_SUPER }
                .mapNotNull { (it as? ReferenceInstruction)?.reference as? MethodReference }
                .single { it.name == "finish" && it.returnType == "V" && it.parameterTypes.isEmpty() }

            addInstructionsWithLabels(
                0,
                """
                    ${modernBackGuard()}
                    invoke-super {p0}, $superFinish
                    return-void
                """,
                ExternalLabel("morphe_legacy_swipe_back", getInstruction(0)),
            )
        }
    }
}
