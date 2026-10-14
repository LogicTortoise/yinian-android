package com.yinian.android.data.repo

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yinian.android.data.entity.TaskEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RepositoryTasksTest {

    private fun makeTask(
        id: String = "t1",
        title: String = "任务",
        status: String = "open",
        priority: Int = 3,
        estimateMinutes: Int = 30,
        domain: String = "general",
        dueAt: Long? = null,
        derivedFromIdeaId: String? = null
    ): TaskEntity = TaskEntity(
        id = id,
        title = title,
        notes = "",
        status = status,
        priority = priority,
        estimateMinutes = estimateMinutes,
        dueAt = dueAt,
        createdAt = 0L,
        updatedAt = 0L,
        derivedFromIdeaId = derivedFromIdeaId,
        domain = domain
    )

    @Test
    fun createTask_clamps_priority_into_the_one_to_five_range() = runTest {
        val repo = makeRepository()

        val tooHigh = repo.createTask(title = "高优先级", priority = 99)
        val tooLow = repo.createTask(title = "低优先级", priority = -7)

        assertEquals(5, tooHigh.priority)
        assertEquals(1, tooLow.priority)
    }

    @Test
    fun createTask_clamps_negative_estimateMinutes_to_zero() = runTest {
        val repo = makeRepository()
        val t = repo.createTask(title = "T", estimateMinutes = -10)
        assertEquals(0, t.estimateMinutes)
    }

    @Test
    fun createTask_inserts_a_derived_from_relation_when_given_an_idea_id() = runTest {
        val repo = makeRepository()
        val task = repo.createTask(title = "T", derivedFromIdeaId = "idea-1")

        val relations = repo.observeRelations().first()
        val derived = relations.filter { it.kind == "derived_from" }
        assertEquals(1, derived.size)
        assertEquals("idea-1", derived[0].fromId)
        assertEquals(task.id, derived[0].toId)
    }

    @Test
    fun createTask_without_idea_id_does_not_write_any_relation() = runTest {
        val repo = makeRepository()
        repo.createTask(title = "T")
        assertEquals(0, repo.observeRelations().first().size)
    }

    @Test
    fun completeTask_marks_the_task_as_done_and_reopenTask_restores_it() = runTest {
        val repo = makeRepository()
        val task = repo.createTask(title = "T")

        repo.completeTask(task.id)
        val afterComplete = repo.observeActiveTasks().first()
        // observeActive filters out archived only; status "done" stays visible.
        val doneTask = afterComplete.first { it.id == task.id }
        assertEquals("done", doneTask.status)

        repo.reopenTask(task.id)
        val afterReopen = repo.observeActiveTasks().first()
        assertEquals("open", afterReopen.first { it.id == task.id }.status)
    }

    @Test
    fun archiveTask_moves_the_task_out_of_observeActiveTasks() = runTest {
        val repo = makeRepository()
        val task = repo.createTask(title = "T")

        repo.archiveTask(task.id)

        val active = repo.observeActiveTasks().first()
        assertTrue(active.none { it.id == task.id })
    }

    @Test
    fun archiveTask_deletes_any_associated_time_blocks() = runTest {
        val repo = makeRepository()
        val task = repo.createTask(title = "T")
        // Add a time block linked to the task
        val start = 1_700_000_000_000L
        repo.addManualTimeBlock(
            taskId = task.id,
            title = "块",
            startAt = start,
            endAt = start + 3_600_000L
        )

        repo.archiveTask(task.id)

        val remaining = repo.observeTimeBlocksForTask(task.id).first()
        assertTrue(remaining.isEmpty())
    }

    @Test
    fun deleteTask_removes_the_row_plus_any_relation_rows_for_it() = runTest {
        val repo = makeRepository()
        val taskA = repo.createTask(title = "A")
        val taskB = repo.createTask(title = "B")

        repo.addDependency(fromTask = taskB.id, toTask = taskA.id)
        assertEquals(1, repo.observeRelations().first().size)

        repo.deleteTask(taskA.id)

        assertEquals(0, repo.observeRelations().first().size)
        assertTrue(repo.observeActiveTasks().first().none { it.id == taskA.id })
    }
}
