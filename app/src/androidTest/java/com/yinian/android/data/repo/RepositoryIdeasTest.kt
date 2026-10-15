package com.yinian.android.data.repo

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yinian.android.audio.AudioRecorder
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class RepositoryIdeasTest {

    /**
     * A repository that routes AudioRecorder at the real app filesDir so the
     * audio-file deletion path operates on a file the helper actually wrote.
     */
    private fun makeRepoWithFilesDir(): Pair<YinianRepository, File> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = context.filesDir
        val repo = YinianRepository(
            ideaDao = FakeIdeaDao(),
            taskDao = FakeTaskDao(),
            relationDao = FakeRelationDao(),
            timeBlockDao = FakeTimeBlockDao(),
            focusDao = FakeFocusDao(),
            focusSegmentDao = FakeFocusSessionSegmentDao(),
            workPolicyDao = FakeWorkPolicyDao(),
            recurringRuleDao = FakeRecurringRuleDao(),
            recurringInstanceDao = FakeRecurringInstanceDao(),
            scheduleDraftDao = FakeScheduleDraftDao(),
            prefs = com.yinian.android.data.prefs.PrefsRepository(context),
            audioRecorder = AudioRecorder(context)
        )
        return repo to dir
    }

    @Test
    fun createIdea_stores_an_idea_and_surfaces_it_through_observe() = runTest {
        val repo = makeRepository()
        val idea = repo.createIdea(title = "晚饭", body = "晚上想吃点清淡的", source = "text")
        assertEquals("晚饭", idea.title)
        assertEquals("晚上想吃点清淡的", idea.body)
        assertEquals("text", idea.source)
        assertNull(idea.recordingPath)
        assertEquals("none", idea.transcriptStatus)

        val active = repo.observeIdeas().first()
        assertEquals(1, active.size)
        assertEquals(idea.id, active[0].id)
    }

    @Test
    fun createIdea_falls_back_to_a_body_derived_title_when_title_is_blank() = runTest {
        val repo = makeRepository()
        val idea = repo.createIdea(title = "", body = "刚刚想到的灵感内容", source = "text")
        assertEquals("刚刚想到的灵感内容", idea.title)
    }

    @Test
    fun createIdea_falls_back_to_a_placeholder_when_both_title_and_body_are_blank() = runTest {
        val repo = makeRepository()
        val idea = repo.createIdea(title = "  ", body = "", source = "text")
        assertEquals("未命名闪念", idea.title)
    }

    @Test
    fun archiveIdea_hides_the_idea_from_observeIdeas_but_keeps_the_row_for_getById() = runTest {
        val repo = makeRepository()
        val idea = repo.createIdea(title = "T", body = "B", source = "text")
        repo.archiveIdea(idea.id, archived = true)
        assertEquals(0, repo.observeIdeas().first().size)
        // The row is still queryable by id for un-archive flows.
        assertNotNull(repo.observeIdea(idea.id).first())
    }

    @Test
    fun deleteIdea_removes_the_row_and_the_recorded_audio_file() = runTest {
        val (repo, dir) = makeRepoWithFilesDir()

        val recordingsDir = File(dir, "recordings").apply { mkdirs() }
        val audio = File(recordingsDir, "stub-${System.nanoTime()}.m4a").apply {
            writeBytes(byteArrayOf(0, 1, 2, 3))
        }
        assertTrue(audio.exists())

        val idea = repo.createIdea(
            title = "T", body = "B", source = "voice",
            recordingPath = audio.absolutePath, transcriptStatus = "disabled"
        )
        repo.deleteIdea(idea)
        assertEquals(0, repo.observeIdeas().first().size)
        assertTrue(!audio.exists())
    }

    @Test
    fun convertIdeaToTask_creates_a_task_and_a_derived_from_relation() = runTest {
        val repo = makeRepository()
        val idea = repo.createIdea(title = "读一下清单", body = "挑三件该做的事", source = "text")
        val task = repo.convertIdeaToTask(ideaId = idea.id, title = null)
        assertNotNull(task)
        assertEquals("读一下清单", task!!.title)
        assertEquals(idea.body, task.notes)
        assertEquals(idea.id, task.derivedFromIdeaId)

        val relations = repo.observeRelations().first()
        val derived = relations.filter { it.kind == "derived_from" }
        assertEquals(1, derived.size)
        assertEquals(idea.id, derived[0].fromId)
        assertEquals(task.id, derived[0].toId)
    }

    @Test
    fun convertIdeaToTask_returns_null_for_unknown_idea_id() = runTest {
        val repo = makeRepository()
        val result = repo.convertIdeaToTask(ideaId = "nope")
        assertNull(result)
    }

    @Test
    fun convertIdeaToTask_honours_an_explicit_title_override() = runTest {
        val repo = makeRepository()
        val idea = repo.createIdea(title = "原标题", body = "内容", source = "text")
        // Production code does not trim; verify the explicit value is used verbatim
        // when not blank (the editor already trims before invoking the repository).
        val task = repo.convertIdeaToTask(ideaId = idea.id, title = "改造后的任务标题")
        assertEquals("改造后的任务标题", task!!.title)
    }
}
