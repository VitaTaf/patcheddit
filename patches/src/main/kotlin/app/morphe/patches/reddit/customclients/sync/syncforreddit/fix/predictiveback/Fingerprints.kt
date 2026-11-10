package app.morphe.patches.reddit.customclients.sync.syncforreddit.fix.predictiveback

import app.morphe.patcher.Fingerprint

internal const val BASE_MATERIAL_DRAG_ACTIVITY =
    "Lcom/laurencedawson/reddit_sync/ui/activities/BaseMaterialDragActivity;"

internal object DragActivitySetContentViewFingerprint : Fingerprint(
    definingClass = BASE_MATERIAL_DRAG_ACTIVITY,
    name = "s0",
    returnType = "V",
    parameters = listOf(),
)

internal object DragActivityOnResumeFingerprint : Fingerprint(
    definingClass = BASE_MATERIAL_DRAG_ACTIVITY,
    name = "onResume",
    returnType = "V",
    parameters = listOf(),
)

internal object DragActivityOnPostResumeFingerprint : Fingerprint(
    definingClass = BASE_MATERIAL_DRAG_ACTIVITY,
    name = "onPostResume",
    returnType = "V",
    parameters = listOf(),
)

internal object DragActivityOnDestroyFingerprint : Fingerprint(
    definingClass = BASE_MATERIAL_DRAG_ACTIVITY,
    name = "onDestroy",
    returnType = "V",
    parameters = listOf(),
)

internal object DragActivityStopSwipeFingerprint : Fingerprint(
    definingClass = BASE_MATERIAL_DRAG_ACTIVITY,
    name = "I0",
    returnType = "V",
    parameters = listOf(),
)

internal object DragActivityFinishFingerprint : Fingerprint(
    definingClass = BASE_MATERIAL_DRAG_ACTIVITY,
    name = "finish",
    returnType = "V",
    parameters = listOf(),
)
