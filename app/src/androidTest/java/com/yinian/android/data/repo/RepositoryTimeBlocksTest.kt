package com.yinian.android.data.repo

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RepositoryTimeBlocksTest {

    @Test
    fun addManualTimeBlock_rejects_inverted_ranges_with_InvalidRange() = runTest {
        val repo = makeRepository()
        val start = 1_700_000_000_000L
        val result = repo.addManualTimeBlock(
            taskId = null,
            title = "块",
            startAt = start,
            endAt = start, // equal, invalid
            fixed = false
        )
        assertEquals(AddBlockResult.InvalidRange, result)
    }

    @Test
    fun addManualTimeBlock_persists_a_non_overlapping_block() = runTest {
        val repo = makeRepository()
        val start = 1_700_000_000_000L
        val end = start + 3_600_000L
        val result = repo.addManualTimeBlock(taskId = null, title = "会议", startAt = start, endAt = end, fixed = false)
        assertTrue(result is AddBlockResult.Ok)
        val block = (result as AddBlockResult.Ok).block
        assertEquals("会议", block.title)
        assertEquals(false, block.fixed)

        val stored = repo.observeAllTimeBlocks().first()
        assertEquals(1, stored.size)
        assertEquals(block.id, stored[0].id)
    }

    @Test
    fun addManualTimeBlock_rejects_an_overlap_on_a_fixed_block_and_leaves_it_intact() = runTest {
        val repo = makeRepository()
        val fixedStart = 1_700_000_000_000L
        val fixedEnd = fixedStart + 60 * 60_000L
        val first = repo.addManualTimeBlock(
            taskId = null, title = "固定块",
            startAt = fixedStart, endAt = fixedEnd, fixed = true
        )
        assertTrue(first is AddBlockResult.Ok)

        // Manual block that begins after the fixed block's start but before its end —
        // overlaps the fixed half-open interval [fixedStart, fixedEnd).
        val overlapStart = fixedStart + 10 * 60_000L
        val overlapEnd = overlapStart + 60 * 60_000L
        val second = repo.addManualTimeBlock(
            taskId = null, title = "新块",
            startAt = overlapStart, endAt = overlapEnd, fixed = false
        )
        assertTrue(second is AddBlockResult.ConflictsWithFixed)
        val conflict = (second as AddBlockResult.ConflictsWithFixed).block
        assertEquals("固定块", conflict.title)
        assertTrue(conflict.fixed)

        // The fixed block is still there and no second block was written.
        val all = repo.observeAllTimeBlocks().first()
        assertEquals(1, all.size)
        assertEquals("固定块", all[0].title)
    }

    @Test
    fun addManualTimeBlock_rejects_an_overlap_when_proposed_window_starts_before_the_fixed_block() = runTest {
        // Regression shape from the original bug report: the proposed window begins
        // *before* the fixed block, and the half-open predicate `existing.startAt < end && existing.endAt > start`
        // must still surface the conflict — not miss it because the fixed block's
        // startAt is older than the proposed startAt.
        val repo = makeRepository()
        val fixedStart = 1_700_000_000_000L
        val fixedEnd = fixedStart + 60 * 60_000L
        repo.addManualTimeBlock(
            taskId = null, title = "固定块",
            startAt = fixedStart, endAt = fixedEnd, fixed = true
        )

        val proposedStart = fixedStart - 10 * 60_000L
        val proposedEnd = fixedStart + 5 * 60_000L
        val result = repo.addManualTimeBlock(
            taskId = null, title = "前置块",
            startAt = proposedStart, endAt = proposedEnd, fixed = false
        )
        assertTrue(result is AddBlockResult.ConflictsWithFixed)
        val all = repo.observeAllTimeBlocks().first()
        assertEquals(1, all.size)
        assertEquals("固定块", all[0].title)
    }

    @Test
    fun addManualTimeBlock_allows_a_boundary_adjacent_block_under_half_open_intervals() = runTest {
        // Half-open intervals: [a, b) and [b, c) do NOT overlap. A proposed
        // block starting exactly when the existing block ends must be allowed.
        val repo = makeRepository()
        val firstStart = 1_700_000_000_000L
        val firstEnd = firstStart + 60 * 60_000L
        repo.addManualTimeBlock(
            taskId = null, title = "第一块",
            startAt = firstStart, endAt = firstEnd, fixed = true
        )

        // Touches the fixed block at the right edge — should be accepted.
        val after = repo.addManualTimeBlock(
            taskId = null, title = "右邻接",
            startAt = firstEnd, endAt = firstEnd + 30 * 60_000L, fixed = false
        )
        assertTrue(after is AddBlockResult.Ok)

        // Touches the fixed block at the left edge — should be accepted.
        val before = repo.addManualTimeBlock(
            taskId = null, title = "左邻接",
            startAt = firstStart - 30 * 60_000L, endAt = firstStart, fixed = false
        )
        assertTrue(before is AddBlockResult.Ok)

        val all = repo.observeAllTimeBlocks().first()
        assertEquals(3, all.size)
    }

    @Test
    fun addManualTimeBlock_allows_a_non_fixed_block_to_overlap_an_existing_non_fixed_block() = runTest {
        val repo = makeRepository()
        val base = 1_700_000_000_000L
        repo.addManualTimeBlock(
            taskId = null, title = "A",
            startAt = base, endAt = base + 60 * 60_000L, fixed = false
        )
        val result = repo.addManualTimeBlock(
            taskId = null, title = "B",
            startAt = base + 10 * 60_000L, endAt = base + 30 * 60_000L, fixed = false
        )
        assertTrue(result is AddBlockResult.Ok)
        assertEquals(2, repo.observeAllTimeBlocks().first().size)
    }

    @Test
    fun removeTimeBlock_deletes_by_id() = runTest {
        val repo = makeRepository()
        val start = 1_700_000_000_000L
        val res = repo.addManualTimeBlock(
            taskId = null, title = "块",
            startAt = start, endAt = start + 3_600_000L, fixed = false
        )
        val block = (res as AddBlockResult.Ok).block

        repo.removeTimeBlock(block.id)
        assertEquals(0, repo.observeAllTimeBlocks().first().size)
    }
}
