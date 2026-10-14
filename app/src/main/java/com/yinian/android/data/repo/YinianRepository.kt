package com.yinian.android.data.repo

import com.yinian.android.audio.AudioRecorder
import com.yinian.android.data.dao.FocusDao
import com.yinian.android.data.dao.IdeaDao
import com.yinian.android.data.dao.RelationDao
import com.yinian.android.data.dao.TaskDao
import com.yinian.android.data.dao.TimeBlockDao
import com.yinian.android.data.entity.FocusSessionEntity
import com.yinian.android.data.entity.IdeaEntity
import com.yinian.android.data.entity.RelationEntity
import com.yinian.android.data.entity.TaskEntity
import com.yinian.android.data.entity.TimeBlockEntity
import com.yinian.android.data.prefs.PrefsRepository
import kotlinx.coroutines.flow.Flow
import java.io.File
import java.util.UUID

class YinianRepository(
    private val ideaDao: IdeaDao,
    private val taskDao: TaskDao,
    private val relationDao: RelationDao,
    private val timeBlockDao: TimeBlockDao,
    private val focusDao: FocusDao,
    private val prefs: PrefsRepository,
    val audioRecorder: AudioRecorder
) {
    // ---------- Ideas ----------
    fun observeIdeas(): Flow<List<IdeaEntity>> = ideaDao.observeActive()
    fun observeIdea(id: String): Flow<IdeaEntity?> = ideaDao.observeById(id)

    suspend fun createIdea(
        title: String,
        body: String,
        source: String,
        recordingPath: String? = null,
        transcriptStatus: String = "none"
    ): IdeaEntity {
        val now = System.currentTimeMillis()
        val idea = IdeaEntity(
            id = UUID.randomUUID().toString(),
            title = title.ifBlank { body.take(40).ifBlank { "未命名闪念" } },
            body = body,
            source = source,
            createdAt = now,
            updatedAt = now,
            recordingPath = recordingPath,
            transcriptStatus = transcriptStatus
        )
        ideaDao.upsert(idea)
        return idea
    }

    suspend fun archiveIdea(id: String, archived: Boolean = true) {
        ideaDao.setArchived(id, archived, System.currentTimeMillis())
    }

    suspend fun deleteIdea(idea: IdeaEntity) {
        idea.recordingPath?.let { runCatching { File(it).delete() } }
        relationDao.deleteAllFor(idea.id)
        ideaDao.delete(idea)
    }

    // ---------- Tasks ----------
    fun observeTasks(): Flow<List<TaskEntity>> = taskDao.observeAll()
    fun observeActiveTasks(): Flow<List<TaskEntity>> = taskDao.observeActive()
    fun observeTask(id: String): Flow<TaskEntity?> = taskDao.observeById(id)

    suspend fun createTask(
        title: String,
        notes: String = "",
        priority: Int = 3,
        estimateMinutes: Int = 30,
        derivedFromIdeaId: String? = null,
        domain: String = "general",
        dueAt: Long? = null
    ): TaskEntity {
        val now = System.currentTimeMillis()
        val task = TaskEntity(
            id = UUID.randomUUID().toString(),
            title = title,
            notes = notes,
            status = "open",
            priority = priority.coerceIn(1, 5),
            estimateMinutes = estimateMinutes.coerceAtLeast(0),
            dueAt = dueAt,
            createdAt = now,
            updatedAt = now,
            derivedFromIdeaId = derivedFromIdeaId,
            domain = domain
        )
        taskDao.upsert(task)
        if (derivedFromIdeaId != null) {
            relationDao.upsert(
                RelationEntity(derivedFromIdeaId, task.id, "derived_from", now)
            )
        }
        return task
    }

    suspend fun updateTask(task: TaskEntity) {
        taskDao.update(task.copy(updatedAt = System.currentTimeMillis()))
    }

    suspend fun completeTask(id: String) {
        taskDao.setStatus(id, "done", System.currentTimeMillis())
    }

    suspend fun reopenTask(id: String) {
        taskDao.setStatus(id, "open", System.currentTimeMillis())
    }

    suspend fun archiveTask(id: String) {
        taskDao.setStatus(id, "archived", System.currentTimeMillis())
        timeBlockDao.deleteForTask(id)
    }

    suspend fun deleteTask(id: String) {
        relationDao.deleteAllFor(id)
        timeBlockDao.deleteForTask(id)
        taskDao.deleteById(id)
    }

    /** Convert an idea to a task, preserving provenance via a derived_from relation. */
    suspend fun convertIdeaToTask(ideaId: String, title: String? = null): TaskEntity? {
        val snap = ideaDao.getById(ideaId) ?: return null
        val task = createTask(
            title = title?.takeIf { it.isNotBlank() } ?: snap.title,
            notes = snap.body,
            derivedFromIdeaId = snap.id
        )
        return task
    }

    // ---------- Dependencies ----------
    fun observeRelations(): Flow<List<RelationEntity>> = relationDao.observeAll()

    /** Validates the proposed dependency: rejects self-link and cycles. */
    suspend fun canAddDependency(fromTask: String, toTask: String): DependencyCheck {
        if (fromTask == toTask) return DependencyCheck.SelfLink
        val all = relationDao.getAll()
        // Build adjacency (depends_on direction: from -> to means to blocks from)
        val adj = mutableMapOf<String, MutableSet<String>>()
        all.filter { it.kind == "depends_on" }.forEach { r ->
            adj.getOrPut(r.fromId) { mutableSetOf() }.add(r.toId)
        }
        // BFS from toTask: if we reach fromTask, adding (fromTask -> toTask) would form a cycle.
        val queue = ArrayDeque<String>()
        val visited = mutableSetOf<String>()
        queue.add(toTask)
        while (queue.isNotEmpty()) {
            val cur = queue.removeFirst()
            if (!visited.add(cur)) continue
            if (cur == fromTask) return DependencyCheck.Cycle
            adj[cur]?.forEach { queue.addLast(it) }
        }
        return DependencyCheck.Ok
    }

    suspend fun addDependency(fromTask: String, toTask: String): DependencyCheck {
        val check = canAddDependency(fromTask, toTask)
        if (check != DependencyCheck.Ok) return check
        relationDao.upsert(
            RelationEntity(fromTask, toTask, "depends_on", System.currentTimeMillis())
        )
        return DependencyCheck.Ok
    }

    suspend fun removeDependency(fromTask: String, toTask: String) {
        relationDao.delete(fromTask, toTask, "depends_on")
    }

    /** Returns set of task IDs that block this one (incoming depends_on targets). */
    fun blockingFor(taskId: String, relations: List<RelationEntity>): Set<String> =
        relations.filter { it.kind == "depends_on" && it.fromId == taskId }.map { it.toId }.toSet()

    fun blockedByFor(taskId: String, relations: List<RelationEntity>): Set<String> =
        relations.filter { it.kind == "depends_on" && it.toId == taskId }.map { it.fromId }.toSet()

    // ---------- Time blocks ----------
    fun observeAllTimeBlocks(): Flow<List<TimeBlockEntity>> = timeBlockDao.observeAll()
    fun observeTimeBlocksBetween(start: Long, end: Long) = timeBlockDao.observeBetween(start, end)
    fun observeTimeBlocksForTask(taskId: String) = timeBlockDao.observeForTask(taskId)

    suspend fun addManualTimeBlock(
        taskId: String?,
        title: String,
        startAt: Long,
        endAt: Long,
        fixed: Boolean = false
    ): AddBlockResult {
        if (endAt <= startAt) return AddBlockResult.InvalidRange
        // getBetween uses the half-open overlap predicate
        // (existing.startAt < proposedEnd && existing.endAt > proposedStart),
        // so any fixed block whose interval touches [startAt, endAt) is returned —
        // including those that started before startAt and extend into it.
        val conflict = timeBlockDao.getBetween(startAt, endAt).firstOrNull { it.fixed }
        if (conflict != null) return AddBlockResult.ConflictsWithFixed(conflict)
        val block = TimeBlockEntity(
            id = UUID.randomUUID().toString(),
            taskId = taskId,
            title = title,
            startAt = startAt,
            endAt = endAt,
            fixed = fixed
        )
        timeBlockDao.upsert(block)
        return AddBlockResult.Ok(block)
    }

    suspend fun removeTimeBlock(id: String) = timeBlockDao.deleteById(id)

    // ---------- Focus sessions ----------
    fun observeActiveFocus(): Flow<FocusSessionEntity?> = focusDao.observeActive()
    fun observeAllFocus(): Flow<List<FocusSessionEntity>> = focusDao.observeAll()

    suspend fun startFocus(taskId: String?): FocusSessionEntity {
        val now = System.currentTimeMillis()
        val session = FocusSessionEntity(
            id = UUID.randomUUID().toString(),
            taskId = taskId,
            startedAt = now,
            endedAt = null,
            pausedMs = 0L,
            activeStartedAt = now,
            status = "active"
        )
        focusDao.upsert(session)
        return session
    }

    suspend fun pauseFocus(id: String) {
        val now = System.currentTimeMillis()
        val current = currentFocus(id) ?: return
        val accumulated = current.pausedMs + activeSegmentMs(current, now)
        focusDao.applyPause(id, "paused", accumulated, null)
    }

    suspend fun resumeFocus(id: String) {
        focusDao.applyResume(id, "active", System.currentTimeMillis())
    }

    suspend fun endFocus(id: String) {
        val now = System.currentTimeMillis()
        val current = currentFocus(id)
        val accumulated = if (current != null) current.pausedMs + activeSegmentMs(current, now) else 0L
        focusDao.applyPause(id, "completed", accumulated, now)
    }

    suspend fun cancelFocus(id: String) {
        focusDao.applyPause(id, "cancelled", 0L, System.currentTimeMillis())
    }

    private suspend fun currentFocus(id: String): FocusSessionEntity? =
        focusDao.all().firstOrNull { it.id == id }

    /**
     * Wall-clock ms elapsed in the current active segment. Safe when the
     * session is paused/completed/cancelled (returns 0).
     */
    private fun activeSegmentMs(s: FocusSessionEntity, now: Long): Long {
        val start = s.activeStartedAt ?: return 0L
        val delta = now - start
        return if (delta > 0) delta else 0L
    }

    // ---------- Preferences ----------
    val preferences: PrefsRepository get() = prefs
}

enum class DependencyCheck { Ok, SelfLink, Cycle }
sealed class AddBlockResult {
    data class Ok(val block: TimeBlockEntity) : AddBlockResult()
    data class ConflictsWithFixed(val block: TimeBlockEntity) : AddBlockResult()
    data object InvalidRange : AddBlockResult()
}
