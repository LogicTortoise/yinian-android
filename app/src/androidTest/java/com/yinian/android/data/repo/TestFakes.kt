package com.yinian.android.data.repo

import com.yinian.android.audio.AudioRecorder
import com.yinian.android.data.dao.FocusDao
import com.yinian.android.data.dao.FocusSessionSegmentDao
import com.yinian.android.data.dao.IdeaDao
import com.yinian.android.data.dao.RecurringInstanceDao
import com.yinian.android.data.dao.RecurringRuleDao
import com.yinian.android.data.dao.RelationDao
import com.yinian.android.data.dao.ScheduleDraftDao
import com.yinian.android.data.dao.TaskDao
import com.yinian.android.data.dao.TimeBlockDao
import com.yinian.android.data.dao.WorkPolicyDao
import com.yinian.android.data.entity.FocusSessionEntity
import com.yinian.android.data.entity.FocusSessionSegmentEntity
import com.yinian.android.data.entity.IdeaEntity
import com.yinian.android.data.entity.RecurringInstanceEntity
import com.yinian.android.data.entity.RecurringRuleEntity
import com.yinian.android.data.entity.RelationEntity
import com.yinian.android.data.entity.ScheduleDraftEntity
import com.yinian.android.data.entity.TaskEntity
import com.yinian.android.data.entity.TimeBlockEntity
import com.yinian.android.data.entity.WorkPolicyEntity
import com.yinian.android.data.prefs.PrefsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/** Simple in-memory DAO fakes — no Robolectric, no Room overhead. Same shape as the JVM fakes. */

internal class FakeIdeaDao : IdeaDao {
    private val items = MutableStateFlow<List<IdeaEntity>>(emptyList())
    private val all = MutableStateFlow<List<IdeaEntity>>(emptyList())
    private val byId = mutableMapOf<String, IdeaEntity>()
    override fun observeActive(): Flow<List<IdeaEntity>> = items
    override fun observeById(id: String): Flow<IdeaEntity?> =
        all.map { list -> list.firstOrNull { it.id == id } }
    override suspend fun upsert(idea: IdeaEntity) {
        byId[idea.id] = idea
        items.value = byId.values.filter { !it.archived }.sortedByDescending { it.createdAt }
        all.value = byId.values.toList()
    }
    override suspend fun update(idea: IdeaEntity) = upsert(idea)
    override suspend fun delete(idea: IdeaEntity) {
        byId.remove(idea.id)
        items.value = byId.values.filter { !it.archived }.sortedByDescending { it.createdAt }
        all.value = byId.values.toList()
    }
    override suspend fun setArchived(id: String, archived: Boolean, now: Long) {
        val existing = byId[id] ?: return
        byId[id] = existing.copy(archived = archived, updatedAt = now)
        items.value = byId.values.filter { !it.archived }.sortedByDescending { it.createdAt }
        all.value = byId.values.toList()
    }
    override suspend fun getById(id: String): IdeaEntity? = byId[id]
    override suspend fun count(): Int = byId.size
}

internal class FakeTaskDao : TaskDao {
    private val items = MutableStateFlow<List<TaskEntity>>(emptyList())
    private val byId = mutableMapOf<String, TaskEntity>()
    override fun observeAll(): Flow<List<TaskEntity>> = items
    override fun observeActive(): Flow<List<TaskEntity>> =
        items.map { list -> list.filter { it.status != "archived" } }
    override fun observeById(id: String): Flow<TaskEntity?> = items.map { list -> list.firstOrNull { it.id == id } }
    override suspend fun upsert(task: TaskEntity) {
        byId[task.id] = task
        items.value = byId.values.sortedWith(compareBy({ it.status }, { it.priority }, { -it.createdAt }))
    }
    override suspend fun update(task: TaskEntity) = upsert(task)
    override suspend fun setStatus(id: String, status: String, now: Long) {
        val existing = byId[id] ?: return
        byId[id] = existing.copy(status = status, updatedAt = now)
        items.value = byId.values.sortedWith(compareBy({ it.status }, { it.priority }, { -it.createdAt }))
    }
    override suspend fun deleteById(id: String) {
        byId.remove(id)
        items.value = byId.values.sortedWith(compareBy({ it.status }, { it.priority }, { -it.createdAt }))
    }
    override suspend fun listByRecurringRule(ruleId: String): List<TaskEntity> =
        byId.values.filter { it.recurringRuleId == ruleId }
    override suspend fun getById(id: String): TaskEntity? = byId[id]
    override suspend fun getAllSnapshot(): List<TaskEntity> = byId.values.toList()
    override suspend fun getAllOpenSnapshot(): List<TaskEntity> =
        byId.values.filter { it.status != "archived" }
}

internal class FakeRelationDao : RelationDao {
    private val items = MutableStateFlow<List<RelationEntity>>(emptyList())
    private val all = mutableListOf<RelationEntity>()
    override fun observeFor(id: String): Flow<List<RelationEntity>> =
        items.map { list -> list.filter { it.fromId == id || it.toId == id } }
    override fun observeAll(): Flow<List<RelationEntity>> = items
    override suspend fun upsert(relation: RelationEntity) {
        all.removeAll { it.fromId == relation.fromId && it.toId == relation.toId && it.kind == relation.kind }
        all.add(relation)
        items.value = all.toList()
    }
    override suspend fun delete(from: String, to: String, kind: String) {
        all.removeAll { it.fromId == from && it.toId == to && it.kind == kind }
        items.value = all.toList()
    }
    override suspend fun deleteAllFor(id: String) {
        all.removeAll { it.fromId == id || it.toId == id }
        items.value = all.toList()
    }
    override suspend fun getAll(): List<RelationEntity> = all.toList()
}

internal class FakeTimeBlockDao : TimeBlockDao {
    private val items = MutableStateFlow<List<TimeBlockEntity>>(emptyList())
    private val byId = mutableMapOf<String, TimeBlockEntity>()
    override fun observeBetween(start: Long, end: Long): Flow<List<TimeBlockEntity>> =
        items.map { list -> list.filter { it.startAt < end && it.endAt > start } }
    override suspend fun getBetween(start: Long, end: Long): List<TimeBlockEntity> =
        // Half-open overlap predicate, matching the DAO's SQL `startAt < :end AND endAt > :start`.
        byId.values.filter { it.startAt < end && it.endAt > start }.sortedBy { it.startAt }
    override fun observeForTask(taskId: String): Flow<List<TimeBlockEntity>> =
        items.map { list -> list.filter { it.taskId == taskId } }
    override fun observeAll(): Flow<List<TimeBlockEntity>> = items
    override suspend fun upsert(block: TimeBlockEntity) {
        byId[block.id] = block
        items.value = byId.values.sortedBy { it.startAt }
    }
    override suspend fun deleteById(id: String) {
        byId.remove(id)
        items.value = byId.values.sortedBy { it.startAt }
    }
    override suspend fun deleteForTask(taskId: String) {
        val toRemove = byId.values.filter { it.taskId == taskId }.map { it.id }
        toRemove.forEach { byId.remove(it) }
        items.value = byId.values.sortedBy { it.startAt }
    }
    override suspend fun listByRecurringRule(ruleId: String): List<TimeBlockEntity> =
        byId.values.filter { it.recurringRuleId == ruleId }
    override suspend fun listByDraft(draftId: String): List<TimeBlockEntity> =
        byId.values.filter { it.draftId == draftId }
    override suspend fun listInWindow(start: Long, end: Long): List<TimeBlockEntity> =
        byId.values.filter { it.startAt < end && it.endAt > start }.sortedBy { it.startAt }
    override suspend fun listByTask(taskId: String): List<TimeBlockEntity> =
        byId.values.filter { it.taskId == taskId }.sortedBy { it.startAt }
}

internal class FakeFocusDao : FocusDao {
    private val items = MutableStateFlow<List<FocusSessionEntity>>(emptyList())
    private val byId = mutableMapOf<String, FocusSessionEntity>()
    override fun observeAll(): Flow<List<FocusSessionEntity>> = items
    override suspend fun all(): List<FocusSessionEntity> = byId.values.toList()
    override fun observeActive(): Flow<FocusSessionEntity?> =
        items.map { list -> list.firstOrNull { it.status == "active" || it.status == "paused" } }
    override suspend fun upsert(session: FocusSessionEntity) {
        byId[session.id] = session
        items.value = byId.values.sortedByDescending { it.startedAt }
    }
    override suspend fun applyPause(id: String, status: String, pausedMs: Long, endedAt: Long?) {
        val existing = byId[id] ?: return
        byId[id] = existing.copy(
            status = status,
            activeStartedAt = null,
            pausedMs = pausedMs,
            endedAt = endedAt
        )
        items.value = byId.values.sortedByDescending { it.startedAt }
    }
    override suspend fun applyResume(id: String, status: String, activeStartedAt: Long) {
        val existing = byId[id] ?: return
        byId[id] = existing.copy(
            status = status,
            activeStartedAt = activeStartedAt,
            endedAt = null
        )
        items.value = byId.values.sortedByDescending { it.startedAt }
    }
}

/**
 * V2-ME in-memory fake for `FocusSessionSegmentDao`. Mirrors the production
 * DAO interface so the repository's segment lifecycle assertions can run
 * without a Room instance.
 */
internal class FakeFocusSessionSegmentDao : FocusSessionSegmentDao {
    private val rows = MutableStateFlow<List<FocusSessionSegmentEntity>>(emptyList())
    private val byId = mutableMapOf<String, FocusSessionSegmentEntity>()

    private fun publish() {
        rows.value = byId.values.sortedBy { it.startedAt }
    }

    override suspend fun upsert(segment: FocusSessionSegmentEntity) {
        byId[segment.id] = segment
        publish()
    }

    override suspend fun close(id: String, endedAt: Long) {
        val existing = byId[id] ?: return
        byId[id] = existing.copy(endedAt = endedAt)
        publish()
    }

    override suspend fun listForSession(sessionId: String): List<FocusSessionSegmentEntity> =
        rows.value.filter { it.sessionId == sessionId }.sortedBy { it.startedAt }

    override suspend fun all(): List<FocusSessionSegmentEntity> = rows.value

    override suspend fun listInWindow(startMs: Long, endMs: Long): List<FocusSessionSegmentEntity> =
        rows.value.filter { seg ->
            val segEnd = seg.endedAt ?: Long.MAX_VALUE
            seg.startedAt < endMs && segEnd > startMs
        }.sortedBy { it.startedAt }
}

internal class FakeWorkPolicyDao : WorkPolicyDao {
    private val items = MutableStateFlow<List<WorkPolicyEntity>>(emptyList())
    private val byDow = linkedMapOf<Int, WorkPolicyEntity>()

    override fun observeAll(): Flow<List<WorkPolicyEntity>> = items
    override suspend fun all(): List<WorkPolicyEntity> = items.value
    override suspend fun upsert(policy: WorkPolicyEntity) {
        byDow[policy.dayOfWeek] = policy
        items.value = byDow.values.sortedBy { it.dayOfWeek }
    }
    override suspend fun deleteAll() {
        byDow.clear()
        items.value = emptyList()
    }
}

internal class FakeRecurringRuleDao : RecurringRuleDao {
    private val items = MutableStateFlow<List<RecurringRuleEntity>>(emptyList())
    private val byId = mutableMapOf<String, RecurringRuleEntity>()

    override fun observeActive(): Flow<List<RecurringRuleEntity>> =
        items.map { list -> list.filter { !it.archived }.sortedBy { it.createdAt } }
    override fun observeAll(): Flow<List<RecurringRuleEntity>> = items
    override suspend fun all(): List<RecurringRuleEntity> = byId.values.toList()
    override suspend fun getById(id: String): RecurringRuleEntity? = byId[id]
    override suspend fun upsert(rule: RecurringRuleEntity) {
        byId[rule.id] = rule
        items.value = byId.values.sortedBy { it.createdAt }
    }
    override suspend fun setArchived(id: String, archived: Boolean, now: Long) {
        val existing = byId[id] ?: return
        byId[id] = existing.copy(archived = archived, updatedAt = now)
        items.value = byId.values.sortedBy { it.createdAt }
    }
}

internal class FakeRecurringInstanceDao : RecurringInstanceDao {
    private val map = mutableMapOf<Pair<String, String>, RecurringInstanceEntity>()

    override suspend fun get(ruleId: String, dayKey: String): RecurringInstanceEntity? =
        map[ruleId to dayKey]
    override suspend fun listForRule(ruleId: String): List<RecurringInstanceEntity> =
        map.entries.filter { it.key.first == ruleId }.map { it.value }
    override suspend fun upsert(instance: RecurringInstanceEntity) {
        map[instance.ruleId to instance.dayKey] = instance
    }
    override suspend fun delete(ruleId: String, dayKey: String) {
        map.remove(ruleId to dayKey)
    }
}

internal class FakeScheduleDraftDao : ScheduleDraftDao {
    private val items = MutableStateFlow<List<ScheduleDraftEntity>>(emptyList())
    private val byId = mutableMapOf<String, ScheduleDraftEntity>()

    override fun observeAll(): Flow<List<ScheduleDraftEntity>> =
        items.map { list -> list.sortedByDescending { it.createdAt } }
    override suspend fun getById(id: String): ScheduleDraftEntity? = byId[id]
    override suspend fun findByFingerprint(fp: String): ScheduleDraftEntity? =
        byId.values.firstOrNull { it.fingerprint == fp }
    override suspend fun upsert(draft: ScheduleDraftEntity) {
        byId[draft.id] = draft
        items.value = byId.values.toList()
    }
    override suspend fun markApplied(id: String, appliedAt: Long) {
        val existing = byId[id] ?: return
        byId[id] = existing.copy(appliedAt = appliedAt)
        items.value = byId.values.toList()
    }
}

// Repository factory lives in `RepoFactory.kt`; keep only the fake DAO
// implementations here.
