package com.yinian.android.ui.plan

import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.snapshots.SnapshotStateList
import com.yinian.android.data.repo.EditedDraftApplyRequest
import com.yinian.android.data.repo.EditedDraftPlacement
import com.yinian.android.data.repo.ScheduleDraft
import com.yinian.android.planner.Placement

/**
 * One row in the editable schedule-draft preview. Editing time fields
 * mutates [startMs] / [endMs] in place; setting [removed] drops the row
 * from the apply call without deleting it from the UI list (so the user can
 * undo the removal).
 */
@Stable
class EditablePlacement(
    val itemId: String,
    val title: String,
    val reason: String,
    val initialStartMs: Long,
    val initialEndMs: Long
) {
    var startMs: Long = initialStartMs
        private set
    var endMs: Long = initialEndMs
        private set
    var removed: Boolean = false
        private set

    val durationMinutes: Int get() = ((endMs - startMs) / 60_000L).toInt().coerceAtLeast(0)
    val stableKey: String get() = "$itemId|$initialStartMs|$initialEndMs"

    fun setTime(newStartMs: Long, newEndMs: Long) {
        require(newEndMs > newStartMs) { "endMs must be > startMs" }
        startMs = newStartMs
        endMs = newEndMs
    }

    fun setRemoved(value: Boolean) {
        removed = value
    }
}

/**
 * In-memory editable view of a [ScheduleDraft]. The UI mutates the rows
 * here during preview; nothing is persisted until [YinianRepository.applyEditedScheduleDraft]
 * is called.
 */
@Stable
class EditableDraft(
    val original: ScheduleDraft,
    val rows: SnapshotStateList<EditablePlacement>,
    val unscheduled: List<Placement.Unscheduled>
) {
    companion object {
        fun from(draft: ScheduleDraft, titleFor: (String) -> String): EditableDraft {
            val rows = mutableStateListOf<EditablePlacement>().apply {
                draft.result.placements.forEach { p ->
                    add(
                        EditablePlacement(
                            itemId = p.itemId,
                            title = titleFor(p.itemId),
                            reason = p.reason,
                            initialStartMs = p.startMs,
                            initialEndMs = p.endMs
                        )
                    )
                }
            }
            return EditableDraft(
                original = draft,
                rows = rows,
                unscheduled = draft.result.unscheduled
            )
        }
    }

    /** Rows that would actually be applied (i.e. not removed). */
    val activeRows: List<EditablePlacement>
        get() = rows.filter { !it.removed }

    /**
     * Snapshot the currently-active editable rows into an immutable,
     * UI-independent apply request. Called by the dialog at apply time so
     * the data layer never has to receive a Compose `SnapshotStateList` or
     * any other type that depends on `androidx.compose.runtime`.
     */
    fun toApplyRequest(): EditedDraftApplyRequest = EditedDraftApplyRequest(
        original = original,
        rows = activeRows.map { row ->
            EditedDraftPlacement(
                itemId = row.itemId,
                title = row.title,
                startMs = row.startMs,
                endMs = row.endMs
            )
        }
    )
}

/** Reason text used in the unscheduled list. Mirrors the previous in-place helper. */
internal fun unscheduledReasonText(reason: com.yinian.android.planner.UnscheduledReason): String =
    when (reason) {
        com.yinian.android.planner.UnscheduledReason.BlockedByDependencies -> "依赖未完成"
        com.yinian.android.planner.UnscheduledReason.OutsideAvailableHours -> "没有合适的工作时段"
        com.yinian.android.planner.UnscheduledReason.DailyCapacityReached -> "本日容量已满"
        com.yinian.android.planner.UnscheduledReason.EstimateTooLong -> "估时超过可用时段"
        is com.yinian.android.planner.UnscheduledReason.ConflictsWith -> "与已有安排冲突"
    }