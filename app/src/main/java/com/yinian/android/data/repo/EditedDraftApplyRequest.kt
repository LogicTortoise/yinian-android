package com.yinian.android.data.repo

/**
 * Immutable, UI- and Compose-independent payload sent to
 * [YinianRepository.applyEditedScheduleDraft] when the user confirms an
 * edited draft. UI code snapshots its mutable state into this structure at
 * apply time so the data layer never has to depend on `SnapshotStateList`
 * or anything else from `androidx.compose.runtime`.
 *
 * The `rows` list carries only the placements that are still active (the
 * UI is responsible for filtering out rows the user removed before
 * snapshotting). Each row is a fully-resolved [EditedDraftPlacement]; no
 * further normalisation happens in the data layer.
 */
data class EditedDraftApplyRequest(
    val original: ScheduleDraft,
    val rows: List<EditedDraftPlacement>
)

/**
 * One immutable placement inside an [EditedDraftApplyRequest]. Times are in
 * the local zone's wall-clock millisecond timeline and must satisfy
 * `endMs > startMs`. The data layer re-checks invariants and rejects the
 * apply if any row falls outside the draft's `[windowStartDay,
 * windowEndDay]` window or conflicts with saved blocks.
 */
data class EditedDraftPlacement(
    val itemId: String,
    val title: String,
    val startMs: Long,
    val endMs: Long
)
