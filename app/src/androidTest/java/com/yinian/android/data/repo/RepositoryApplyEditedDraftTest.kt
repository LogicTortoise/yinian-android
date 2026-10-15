package com.yinian.android.data.repo

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yinian.android.data.entity.WorkPolicyEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

/**
 * V2-CAL-REVIEW-FIX coverage for [YinianRepository.applyEditedScheduleDraft]:
 *  - empty proposal → InvalidProposal without writing
 *  - inverted range per row → InvalidProposal
 *  - out-of-window per row → InvalidProposal
 *  - overlap among edited rows → InvalidProposal
 *  - overlap against a previously-applied draft block → Conflict, no partial writes
 *  - idempotent re-apply (edited fingerprint) → NoOp
 *  - successful apply → Applied, blocks tagged with the (new) draft id
 *
 * The test relies on the production repository's existing constructor signature,
 * which no longer takes `calendarBusyReader` and now requires `focusSegmentDao`.
 */
@RunWith(AndroidJUnit4::class)
class RepositoryApplyEditedDraftTest {

    private fun enableMonFri() = (1..5).map {
        WorkPolicyEntity(it, true, 9 * 60, 18 * 60, bufferMinutes = 0, dailyCapMinutes = 0)
    }

    @Test
    fun applyEditedScheduleDraft_returns_InvalidProposal_when_every_row_is_removed() = runTest {
        val repo = makeRepository()
        repo.saveWorkPolicies(enableMonFri())
        val today = LocalDate.now()
        repo.createTask("T", estimateMinutes = 30, priority = 3)
        val original = repo.buildScheduleDraft(today, today.plusDays(1))
        val request = EditedDraftApplyRequest(
            original = original,
            rows = emptyList() // all removed
        )
        val res = repo.applyEditedScheduleDraft(request)
        assertTrue("Empty proposal must surface InvalidProposal", res is ApplyResult.InvalidProposal)
        // No writes happen.
        val blocksAfter = repo.observeAllTimeBlocks().first().size
        assertEquals(0, blocksAfter)
    }

    @Test
    fun applyEditedScheduleDraft_rejects_inverted_per_row_range() = runTest {
        val repo = makeRepository()
        repo.saveWorkPolicies(enableMonFri())
        val today = LocalDate.now()
        repo.createTask("T", estimateMinutes = 30, priority = 3)
        val original = repo.buildScheduleDraft(today, today.plusDays(1))
        val good = original.result.placements.firstOrNull() ?: return@runTest
        val request = EditedDraftApplyRequest(
            original = original,
            rows = listOf(
                EditedDraftPlacement(
                    itemId = good.itemId, title = good.itemId,
                    startMs = good.startMs, endMs = good.startMs // zero-length
                )
            )
        )
        val res = repo.applyEditedScheduleDraft(request)
        assertTrue(res is ApplyResult.InvalidProposal)
    }

    @Test
    fun applyEditedScheduleDraft_rejects_row_outside_the_planning_window() = runTest {
        val repo = makeRepository()
        repo.saveWorkPolicies(enableMonFri())
        val today = LocalDate.now()
        repo.createTask("T", estimateMinutes = 30, priority = 3)
        val original = repo.buildScheduleDraft(today, today.plusDays(1))
        val good = original.result.placements.firstOrNull() ?: return@runTest
        // Place the row one day before the draft window starts.
        val beforeWindow = original.let {
            val wsMs = java.time.LocalDate.parse(it.windowStartDay)
                .atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
            wsMs - 24L * 3600_000L
        }
        val request = EditedDraftApplyRequest(
            original = original,
            rows = listOf(
                EditedDraftPlacement(
                    itemId = good.itemId, title = good.itemId,
                    startMs = beforeWindow, endMs = beforeWindow + 30 * 60_000L
                )
            )
        )
        val res = repo.applyEditedScheduleDraft(request)
        assertTrue("Out-of-window row must surface InvalidProposal", res is ApplyResult.InvalidProposal)
    }

    @Test
    fun applyEditedScheduleDraft_rejects_overlap_among_the_proposed_rows_atomically() = runTest {
        val repo = makeRepository()
        repo.saveWorkPolicies(enableMonFri())
        val today = LocalDate.now()
        // Two short tasks at the same priority and the same window so the planner
        // gives us two placements on the same day.
        repo.createTask("T1", estimateMinutes = 60, priority = 3)
        repo.createTask("T2", estimateMinutes = 60, priority = 3)
        val original = repo.buildScheduleDraft(today, today.plusDays(2))
        val placements = original.result.placements.take(2)
        assertEquals(2, placements.size)
        // Force an overlap: same start, same end.
        val overlapStart = placements[0].startMs
        val overlapEnd = placements[0].endMs
        val request = EditedDraftApplyRequest(
            original = original,
            rows = listOf(
                EditedDraftPlacement(
                    itemId = placements[0].itemId, title = placements[0].itemId,
                    startMs = overlapStart, endMs = overlapEnd
                ),
                EditedDraftPlacement(
                    itemId = placements[1].itemId, title = placements[1].itemId,
                    startMs = overlapStart, endMs = overlapEnd
                )
            )
        )
        val blocksBefore = repo.observeAllTimeBlocks().first().size
        val res = repo.applyEditedScheduleDraft(request)
        val blocksAfter = repo.observeAllTimeBlocks().first().size
        assertTrue("Overlap among proposed rows must surface InvalidProposal",
            res is ApplyResult.InvalidProposal)
        assertEquals("No partial writes on overlap", blocksBefore, blocksAfter)
    }

    @Test
    fun applyEditedScheduleDraft_rejects_collision_against_an_existing_block_atomically() = runTest {
        val repo = makeRepository()
        repo.saveWorkPolicies(enableMonFri())
        val today = LocalDate.now()
        repo.createTask("T", estimateMinutes = 30, priority = 3)
        val original = repo.buildScheduleDraft(today, today.plusDays(1))
        val good = original.result.placements.firstOrNull() ?: return@runTest
        // Pre-existing non-fixed block at the placement spot.
        repo.addManualTimeBlock(
            taskId = null, title = "占用",
            startAt = good.startMs, endAt = good.endMs, fixed = false
        )
        val blocksBefore = repo.observeAllTimeBlocks().first().size
        val request = EditedDraftApplyRequest(
            original = original,
            rows = listOf(
                EditedDraftPlacement(
                    itemId = good.itemId, title = good.itemId,
                    startMs = good.startMs, endMs = good.endMs
                )
            )
        )
        val res = repo.applyEditedScheduleDraft(request)
        val blocksAfter = repo.observeAllTimeBlocks().first()
        assertTrue("Live-block collision must surface Conflict", res is ApplyResult.Conflict)
        // Exactly the existing block survives — the apply must not write any
        // draft block. "blocksBefore" already counted the manual block.
        assertEquals("No partial writes on collision", blocksBefore, blocksAfter.size)
    }

    @Test
    fun applyEditedScheduleDraft_writes_blocks_tagged_with_a_new_draft_id_and_changed_fingerprint() = runTest {
        val repo = makeRepository()
        repo.saveWorkPolicies(enableMonFri())
        val today = LocalDate.now()
        repo.createTask("T", estimateMinutes = 30, priority = 3)
        val original = repo.buildScheduleDraft(today, today.plusDays(1))
        val good = original.result.placements.firstOrNull() ?: return@runTest
        // Shift the row by 30 minutes — same day, just later — so the edited
        // fingerprint differs from the original.
        val shiftedStart = good.startMs + 30 * 60_000L
        val shiftedEnd = good.endMs + 30 * 60_000L
        val request = EditedDraftApplyRequest(
            original = original,
            rows = listOf(
                EditedDraftPlacement(
                    itemId = good.itemId, title = good.itemId,
                    startMs = shiftedStart, endMs = shiftedEnd
                )
            )
        )
        val blocksBefore = repo.observeAllTimeBlocks().first().size
        val res = repo.applyEditedScheduleDraft(request)
        val blocksAfter = repo.observeAllTimeBlocks().first()
        assertTrue("Edited apply must succeed", res is ApplyResult.Applied)
        val placedDraftIds = blocksAfter.mapNotNull { it.draftId }.toSet()
        // Newly written blocks must reference the edited apply's new draft id,
        // not the original draft id.
        assertTrue("Edited blocks carry a different draft id",
            placedDraftIds.isNotEmpty() && (res as ApplyResult.Applied).draftId in placedDraftIds)
        // Original draft id must NOT appear on any block.
        assertTrue(
            "Original draft id must not leak into the edited write",
            original.id !in placedDraftIds
        )
        assertEquals(blocksBefore + 1, blocksAfter.size)
    }

    @Test
    fun applyEditedScheduleDraft_is_idempotent_on_consecutive_identical_calls() = runTest {
        val repo = makeRepository()
        repo.saveWorkPolicies(enableMonFri())
        val today = LocalDate.now()
        repo.createTask("T", estimateMinutes = 30, priority = 3)
        val original = repo.buildScheduleDraft(today, today.plusDays(1))
        val good = original.result.placements.firstOrNull() ?: return@runTest
        val shiftedStart = good.startMs + 15 * 60_000L
        val shiftedEnd = good.endMs + 15 * 60_000L
        val request = EditedDraftApplyRequest(
            original = original,
            rows = listOf(
                EditedDraftPlacement(
                    itemId = good.itemId, title = good.itemId,
                    startMs = shiftedStart, endMs = shiftedEnd
                )
            )
        )
        val blocksBefore = repo.observeAllTimeBlocks().first().size
        val draftsBefore = repo.observeScheduleDrafts().first().size
        // First apply must write the edited blocks and persist exactly one
        // schedule_draft row tagged with `appliedAt != null`.
        val first = repo.applyEditedScheduleDraft(request)
        assertTrue("First edited apply must write blocks", first is ApplyResult.Applied)
        val blocksAfterFirst = repo.observeAllTimeBlocks().first().size
        val draftsAfterFirst = repo.observeScheduleDrafts().first()
        assertEquals(blocksBefore + 1, blocksAfterFirst)
        assertEquals(draftsBefore + 1, draftsAfterFirst.size)
        assertNotNull("Persisted draft row must be marked applied",
            draftsAfterFirst.firstOrNull { it.fingerprint.length > 0 }?.appliedAt)
        // Second apply must be a NoOp WITHOUT being preceded by revertScheduleDraft.
        // The duplicate guard runs before the live-block conflict check, so the
        // already-written block does NOT trip Conflict on the second pass.
        val second = repo.applyEditedScheduleDraft(request)
        assertTrue(
            "Re-applying the same edited fingerprint must surface a NoOp, not Conflict",
            second is ApplyResult.NoOp
        )
        val blocksAfterSecond = repo.observeAllTimeBlocks().first().size
        val draftsAfterSecond = repo.observeScheduleDrafts().first().size
        assertEquals(
            "NoOp must not write more time blocks than the first apply",
            blocksAfterFirst, blocksAfterSecond
        )
        assertEquals(
            "NoOp must not persist a new schedule_draft row",
            draftsAfterFirst.size, draftsAfterSecond
        )
    }

    @Test
    fun buildScheduleDraft_does_not_persist_a_draft_entity_with_applyEdited_at_first() = runTest {
        val repo = makeRepository()
        repo.saveWorkPolicies(enableMonFri())
        val today = LocalDate.now()
        repo.createTask("T", estimateMinutes = 30, priority = 3)
        val draft = repo.buildScheduleDraft(today, today.plusDays(1))
        // After build, NO draft row exists in the schedule_drafts table —
        // only on apply do we write it (with appliedAt set).
        val draftsBefore = repo.observeScheduleDrafts().first()
        assertEquals("buildScheduleDraft must not persist", 0, draftsBefore.size)
        // Now apply once.
        val good = draft.result.placements.firstOrNull() ?: return@runTest
        val shiftedStart = good.startMs + 5 * 60_000L
        val shiftedEnd = good.endMs + 5 * 60_000L
        val request = EditedDraftApplyRequest(
            original = draft,
            rows = listOf(
                EditedDraftPlacement(
                    itemId = good.itemId, title = good.itemId,
                    startMs = shiftedStart, endMs = shiftedEnd
                )
            )
        )
        val res = repo.applyEditedScheduleDraft(request)
        assertTrue(res is ApplyResult.Applied)
        val draftsAfter = repo.observeScheduleDrafts().first()
        // We must have exactly one persisted draft row, with appliedAt != null.
        assertEquals(1, draftsAfter.size)
        assertNotNull("Persisted draft must be marked applied", draftsAfter[0].appliedAt)
    }
}
