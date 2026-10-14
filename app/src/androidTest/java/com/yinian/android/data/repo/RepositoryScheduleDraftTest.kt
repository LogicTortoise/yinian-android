package com.yinian.android.data.repo

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yinian.android.data.entity.WorkPolicyEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

/**
 * V2 coverage for the schedule-draft preview/apply path:
 *   - building a draft MUST NOT write any time blocks
 *   - applying a draft persists blocks tagged with the draft's id
 *   - re-applying the same draft fingerprint is a NoOp (idempotence)
 *   - a newly-fixed block at apply time aborts with Conflict and writes nothing
 */
@RunWith(AndroidJUnit4::class)
class RepositoryScheduleDraftTest {

    private fun enableMonFri() = (1..5).map { WorkPolicyEntity(it, true, 9 * 60, 18 * 60) }

    @Test
    fun buildScheduleDraft_writes_no_time_blocks() = runTest {
        val repo = makeRepository()
        repo.saveWorkPolicies(enableMonFri())
        val today = LocalDate.now()
        repo.createTask("T", estimateMinutes = 60, priority = 3)
        val beforeBlocks = repo.observeAllTimeBlocks().first().size
        val draft = repo.buildScheduleDraft(today, today.plusDays(2))
        val afterBlocks = repo.observeAllTimeBlocks().first().size
        assertEquals("Draft must be in-memory only — no block writes", beforeBlocks, afterBlocks)
        // The draft has an id, but no TimeBlock in the DAO references it.
        val persistedDraft = repo.observeScheduleDrafts().first()
        assertTrue(persistedDraft.isEmpty())
        assertNotNull(draft.id)
    }

    @Test
    fun applyScheduleDraft_writes_time_blocks_after_explicit_apply() = runTest {
        val repo = makeRepository()
        repo.saveWorkPolicies(enableMonFri())
        val today = LocalDate.now()
        val task = repo.createTask("T", estimateMinutes = 30, priority = 3)
        val draft = repo.buildScheduleDraft(today, today.plusDays(1))
        val beforeBlocks = repo.observeAllTimeBlocks().first().size
        val res = repo.applyScheduleDraft(draft)
        val afterBlocks = repo.observeAllTimeBlocks().first()
        assertTrue("Apply must write the draft's blocks", res is ApplyResult.Applied)
        assertEquals(beforeBlocks + (res as ApplyResult.Applied).placedBlocks, afterBlocks.size)
        // Every newly-written block references this draft id.
        for (b in afterBlocks) {
            if (b.taskId == task.id) {
                assertEquals(draft.id, b.draftId)
            }
        }
    }

    @Test
    fun applyScheduleDraft_reapplying_the_same_fingerprint_is_a_NoOp() = runTest {
        val repo = makeRepository()
        repo.saveWorkPolicies(enableMonFri())
        val today = LocalDate.now()
        repo.createTask("T", estimateMinutes = 30, priority = 3)
        val draft = repo.buildScheduleDraft(today, today.plusDays(1))
        val first = repo.applyScheduleDraft(draft)
        val blocksAfterFirst = repo.observeAllTimeBlocks().first().size
        val second = repo.applyScheduleDraft(draft)
        val blocksAfterSecond = repo.observeAllTimeBlocks().first().size
        assertTrue(first is ApplyResult.Applied)
        assertTrue(second is ApplyResult.NoOp)
        assertEquals("NoOp must not write more blocks", blocksAfterFirst, blocksAfterSecond)
    }

    @Test
    fun applyScheduleDraft_new_fixed_block_at_apply_time_yields_no_partial_writes() = runTest {
        val repo = makeRepository()
        repo.saveWorkPolicies(enableMonFri())
        val today = LocalDate.now()
        val task = repo.createTask("T", estimateMinutes = 30, priority = 3)
        val draft = repo.buildScheduleDraft(today, today.plusDays(1))
        // Race: a fixed block lands on the slot the planner picked.
        val placement = draft.result.placements.firstOrNull()
        assertNotNull(placement)
        val p = placement!!
        val beforeBlocks = repo.observeAllTimeBlocks().first().size
        repo.addManualTimeBlock(
            taskId = null,
            title = "新固定",
            startAt = p.startMs,
            endAt = p.endMs,
            fixed = true
        )
        val res = repo.applyScheduleDraft(draft)
        val afterBlocks = repo.observeAllTimeBlocks().first()
        assertTrue("A conflicting fix at apply time must abort", res is ApplyResult.Conflict)
        // Exactly one new block (the manual fixed) should exist; no draft block leaked.
        assertEquals("No partial writes on conflict", beforeBlocks + 1, afterBlocks.size)
        assertTrue(afterBlocks.none { it.draftId == draft.id })
    }

    @Test
    fun applyScheduleDraft_preserves_existing_blocks_and_does_not_overwrite_them() = runTest {
        val repo = makeRepository()
        repo.saveWorkPolicies(enableMonFri())
        val today = LocalDate.now()
        val task = repo.createTask("T", estimateMinutes = 30, priority = 3)
        // Pre-existing fixed block at 10am.
        val dayStart = today.atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        val fixedStart = dayStart + 10 * 3600_000L
        val fixedEnd = fixedStart + 60 * 60_000L
        repo.addManualTimeBlock(
            taskId = null,
            title = "提前固定",
            startAt = fixedStart,
            endAt = fixedEnd,
            fixed = true
        )
        val blocksBeforeDraft = repo.observeAllTimeBlocks().first()
        val draft = repo.buildScheduleDraft(today, today.plusDays(1))
        // The pre-existing fixed block is also surfaced as `existing` in the planner,
        // so a lock is granted to the existing block; the new task must avoid the slot.
        for (placement in draft.result.placements) {
            val overlaps = placement.startMs < fixedEnd && placement.endMs > fixedStart
            assertFalse("Planner must not overlap the fixed block", overlaps)
        }
        // Apply. No block should land on top of the fixed block.
        val res = repo.applyScheduleDraft(draft)
        val afterBlocks = repo.observeAllTimeBlocks().first()
        if (res is ApplyResult.Applied) {
            for (b in afterBlocks) {
                if (b.draftId == draft.id) {
                    val overlaps = b.startAt < fixedEnd && b.endAt > fixedStart
                    assertFalse("Applied block overlaps fixed block", overlaps)
                }
            }
        }
        // The existing fixed block is still there with the same title.
        assertTrue(afterBlocks.any { it.title == "提前固定" && it.fixed })
        assertEquals(blocksBeforeDraft.size, afterBlocks.count { it.title == "提前固定" })
    }

    @Test
    fun revertScheduleDraft_removes_only_the_blocks_tagged_with_the_given_draftId() = runTest {
        val repo = makeRepository()
        repo.saveWorkPolicies(enableMonFri())
        val today = LocalDate.now()
        repo.createTask("T1", estimateMinutes = 30, priority = 3)
        val draft = repo.buildScheduleDraft(today, today.plusDays(1))
        val applied = repo.applyScheduleDraft(draft)
        assertTrue(applied is ApplyResult.Applied)
        val beforeRevert = repo.observeAllTimeBlocks().first()
        val draftBlocksBefore = beforeRevert.count { it.draftId == draft.id }
        assertTrue(draftBlocksBefore > 0)
        repo.revertScheduleDraft(draft.id)
        val afterRevert = repo.observeAllTimeBlocks().first()
        // Same total minus the draft-tagged blocks.
        assertEquals(beforeRevert.size - draftBlocksBefore, afterRevert.size)
        assertTrue(afterRevert.none { it.draftId == draft.id })
    }
}
