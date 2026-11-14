package app.morphe.patches.reddit.customclients.sync.syncforreddit.fix.commentloading

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import com.android.tools.smali.dexlib2.Opcode

internal object InitialCommentsUrlFingerprint : Fingerprint(
    returnType = "Ljava/lang/String;",
    strings = listOf("?always_show_media=1&profile_img=true", "&limit=", "500"),
    custom = { _, classDef -> classDef.sourceFile == "OAuthCommentsRequest.java" },
)

internal object MoreCommentsRequestConstructorFingerprint : Fingerprint(
    name = "<init>",
    returnType = "V",
    strings = listOf("link_id", "children", "api_type", "json"),
    custom = { _, classDef -> classDef.sourceFile == "OAuthCommentsMoreRequest.java" },
)


internal object MoreCommentsResponseFingerprint : Fingerprint(
    returnType = "Lcom/android/volley/Response;",
    parameters = listOf("Lcom/android/volley/NetworkResponse;"),
    strings = listOf("Inserted: "),
    custom = { _, classDef -> classDef.sourceFile == "OAuthCommentsMoreRequest.java" },
)

internal object MoreCommentsSuccessFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf("Ljava/lang/Void;"),
    custom = { method, classDef ->
        classDef.sourceFile == "CommentsPresenterImpl.java" &&
            method.instructions.size == 4 &&
            method.instructions.any { it.opcode == Opcode.IPUT_OBJECT }
    },
)

internal object CommentsCursorLoadedFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf("Landroid/database/Cursor;"),
    strings = listOf("Cursor loaded: ", "new_comment"),
    custom = { _, classDef -> classDef.sourceFile == "CommentsPresenterImpl.java" },
)

internal object LoadMoreCommentsFingerprint : Fingerprint(
    returnType = "V",
    strings = listOf("Requesting more: "),
    custom = { method, classDef ->
        classDef.sourceFile == "CommentsPresenterImpl.java" && method.parameterTypes.size == 1
    },
)

internal object CommentPostParserFingerprint : Fingerprint(
    returnType = "V",
    strings = listOf("body", "media_metadata", "body_processed", "Found giphy ID:  "),
    custom = { _, classDef -> classDef.sourceFile == "PostParserHelper.java" },
)
