package app.morphe.patches.reddit.customclients.sync.syncforreddit.fix.commentcollapse

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.util.getReference
import com.android.tools.smali.dexlib2.iface.reference.FieldReference

/**
 * CommentsPresenterImpl's collapse-tree helper.
 *
 * The class and model descriptors are obfuscated, so match the retained source file plus the
 * distinctive collapseParent setting instead of pinning the obfuscated class name.
 */
internal object CollapseCommentTreeFingerprint : Fingerprint(
    returnType = "V",
    strings = listOf("_id"),
    custom = { method, classDef ->
        classDef.sourceFile == "CommentsPresenterImpl.java" &&
            method.parameterTypes.size == 2 &&
            method.parameterTypes[1].toString() == "Z" &&
            method.instructions.any {
                it.getReference<FieldReference>()?.let { field ->
                    field.name == "collapseParent" && field.type == "Z"
                } == true
            }
    },
)
