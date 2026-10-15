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
import com.yinian.android.planner.BusyInterval
import com.yinian.android.planner.Placement
import com.yinian.android.planner.PlanItem
import com.yinian.android.planner.PlanResult
import com.yinian.android.planner.PlannerConfig
import com.yinian.android.planner.PlannerEngine
import com.yinian.android.planner.PlannedBlock
import com.yinian.android.planner.PlannerTime
import com.yinian.android.planner.RecurringGenerator
import com.yinian.android.planner.Zone
import kotlinx.coroutines.flow.Flow
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

class YinianRepository(
    private val ideaDao: IdeaDao,
    private val taskDao: TaskDao,
    private val relationDao: RelationDao,
    private val timeBlockDao: TimeBlockDao,
    private val focusDao: FocusDao,
    private val focusSegmentDao: FocusSessionSegmentDao,
    private val workPolicyDao: WorkPolicyDao,
    private val recurringRuleDao: RecurringRuleDao,
    private val recurringInstanceDao: RecurringInstanceDao,
    private val scheduleDraftDao: ScheduleDraftDao,
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
        dueAt: Long? = null,
        recurringRuleId: String? = null,
        instanceDate: String? = null
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
            domain = domain,
            recurringRuleId = recurringRuleId,
            instanceDate = instanceDate
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
        val adj = mutableMapOf<String, MutableSet<String>>()
        all.filter { it.kind == "depends_on" }.forEach { r ->
            adj.getOrPut(r.fromId) { mutableSetOf() }.add(r.toId)
        }
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

    /** V2-ME: one-shot pull of every focus session segment. */
    suspend fun focusSegmentsSnapshot(): List<FocusSessionSegmentEntity> = focusSegmentDao.all()

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
        focusSegmentDao.upsert(
            FocusSessionSegmentEntity(
                id = UUID.randomUUID().toString(),
                sessionId = session.id,
                kind = "active",
                startedAt = now,
                endedAt = null
            )
        )
        return session
    }

    suspend fun pauseFocus(id: String) {
        val now = System.currentTimeMillis()
        val current = currentFocus(id) ?: return
        val accumulated = current.pausedMs + activeSegmentMs(current, now)
        focusDao.applyPause(id, "paused", accumulated, null)
        // Close the still-open active segment, then open a paused segment so
        // the segment history mirrors the session row's status field.
        closeOpenSegment(id, "active", now)
        focusSegmentDao.upsert(
            FocusSessionSegmentEntity(
                id = UUID.randomUUID().toString(),
                sessionId = id,
                kind = "paused",
                startedAt = now,
                endedAt = null
            )
        )
    }

    suspend fun resumeFocus(id: String) {
        val now = System.currentTimeMillis()
        focusDao.applyResume(id, "active", now)
        closeOpenSegment(id, "paused", now)
        focusSegmentDao.upsert(
            FocusSessionSegmentEntity(
                id = UUID.randomUUID().toString(),
                sessionId = id,
                kind = "active",
                startedAt = now,
                endedAt = null
            )
        )
    }

    suspend fun endFocus(id: String) {
        val now = System.currentTimeMillis()
        val current = currentFocus(id)
        val accumulated = if (current != null) current.pausedMs + activeSegmentMs(current, now) else 0L
        focusDao.applyPause(id, "completed", accumulated, now)
        // Closing the active segment is enough; completed sessions have no
        // following paused segment.
        closeOpenSegment(id, "active", now)
    }

    suspend fun cancelFocus(id: String) {
        val now = System.currentTimeMillis()
        focusDao.applyPause(id, "cancelled", 0L, now)
        // Cancelled sessions are excluded from "实际投入" anyway. We still
        // close any open segment so the segment history doesn't carry a row
        // that's still flagged endedAt = NULL.
        closeOpenSegment(id, "active", now)
        closeOpenSegment(id, "paused", now)
    }

    private suspend fun closeOpenSegment(sessionId: String, kind: String, endedAt: Long) {
        focusSegmentDao.listForSession(sessionId).forEach { seg ->
            if (seg.kind == kind && seg.endedAt == null) {
                focusSegmentDao.close(seg.id, endedAt)
            }
        }
    }

    private suspend fun currentFocus(id: String): FocusSessionEntity? =
        focusDao.all().firstOrNull { it.id == id }

    private fun activeSegmentMs(s: FocusSessionEntity, now: Long): Long {
        val start = s.activeStartedAt ?: return 0L
        val delta = now - start
        return if (delta > 0) delta else 0L
    }

    // ---------- Preferences ----------
    val preferences: PrefsRepository get() = prefs

    // ============================================================================
    // V2 — Work policy / capacity estimation (V2-02)
    // ============================================================================

    fun observeWorkPolicies(): Flow<List<WorkPolicyEntity>> = workPolicyDao.observeAll()

    /**
     * Persist the per-weekday work policy rows. The list is treated as the
     * full state; rows not present are reset to "not a working day".
     */
    suspend fun saveWorkPolicies(policies: List<WorkPolicyEntity>) {
        workPolicyDao.deleteAll()
        policies.forEach { workPolicyDao.upsert(it) }
    }

    /**
     * One-shot capacity summary for the current week. Used by the plan
     * screen to render "本周可用 / 已安排 / 剩余".
     */
    suspend fun computeWeekCapacity(reference: Long = System.currentTimeMillis()): CapacitySnapshot {
        val zone = Zone.systemDefault()
        val policies = workPolicyDao.all()
        val (weekStart, weekEnd) = weekRange(reference, zone)
        val windowStart = LocalDate.ofInstant(Instant.ofEpochMilli(weekStart), zone)
        val policiesByDow = PlannerTime.policyWindows(policies, zone)
        var totalAvailable = 0
        var totalScheduled = 0
        var totalCap = 0
        val days = mutableListOf<DayCapacityRow>()
        for (dow in 1..7) {
            val date = windowStart.plusDays((dow - windowStart.dayOfWeek.value).toLong())
            val ws = PlannerTime.startOfDayMs(date, zone)
            val we = PlannerTime.endOfDayMs(date, zone)
            val windows = policiesByDow[dow].orEmpty()
            if (windows.isEmpty()) {
                days += DayCapacityRow(date.toString(), 0, 0, 0)
                continue
            }
            val avail = windows.sumOf { it.endMin - it.startMin }
            val cap = windows.first().dailyCapMinutes.coerceAtLeast(avail)
            val scheduled = timeBlockDao.listInWindow(ws, we)
                .filter { !it.fixed || it.source == "recurring" }
                .sumOf { ((it.endAt - it.startAt) / 60_000L).toInt() }
            totalAvailable += avail
            totalScheduled += scheduled
            totalCap += cap
            days += DayCapacityRow(date.toString(), avail, scheduled, cap)
        }
        return CapacitySnapshot(
            weekStartMs = weekStart,
            weekEndMs = weekEnd,
            days = days,
            totalAvailable = totalAvailable,
            totalScheduled = totalScheduled,
            totalCap = totalCap
        )
    }

    private fun weekRange(reference: Long, zone: ZoneId): Pair<Long, Long> {
        val cal = java.util.Calendar.getInstance().apply {
            timeZone = java.util.TimeZone.getTimeZone(zone)
            firstDayOfWeek = java.util.Calendar.MONDAY
            timeInMillis = reference
            set(java.util.Calendar.DAY_OF_WEEK, java.util.Calendar.MONDAY)
            set(java.util.Calendar.HOUR_OF_DAY, 0)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }
        val start = cal.timeInMillis
        val end = start + 7L * 24L * 3600_000L
        return start to end
    }

    // ============================================================================
    // V2 — Recurring rules (V2-03)
    // ============================================================================

    fun observeRecurringRules(): Flow<List<RecurringRuleEntity>> = recurringRuleDao.observeAll()
    fun observeActiveRecurringRules(): Flow<List<RecurringRuleEntity>> = recurringRuleDao.observeActive()

    suspend fun listAllRecurringRules(): List<RecurringRuleEntity> = recurringRuleDao.all()

    suspend fun createRecurringRule(
        title: String,
        estimateMinutes: Int,
        priority: Int,
        domain: String,
        kind: String,
        byDayMask: Int,
        startDayKey: String,
        horizonDays: Int,
        anchorTaskId: String? = null
    ): RecurringRuleEntity {
        val now = System.currentTimeMillis()
        val rule = RecurringRuleEntity(
            id = UUID.randomUUID().toString(),
            title = title,
            estimateMinutes = estimateMinutes,
            priority = priority,
            domain = domain,
            kind = kind,
            byDayMask = byDayMask,
            startDayKey = startDayKey,
            horizonDays = horizonDays,
            anchorTaskId = anchorTaskId,
            archived = false,
            createdAt = now,
            updatedAt = now
        )
        recurringRuleDao.upsert(rule)
        return rule
    }

    suspend fun archiveRecurringRule(id: String) {
        val now = System.currentTimeMillis()
        recurringRuleDao.setArchived(id, true, now)
        // Drop pending FUTURE instances only; keep today / past instances
        // intact so the user's history is preserved. Future eligibility is
        // driven by the local `instanceDate` day key — not `dueAt`, because
        // generated instances commonly have no due date and we do not want
        // to archive open historical instances by accident.
        val zone = Zone.systemDefault()
        val today = LocalDate.now(zone)
        val tasks = taskDao.listByRecurringRule(id)
        for (t in tasks) {
            val dayKey = t.instanceDate ?: continue // not a generated instance
            val instanceDate = runCatching { LocalDate.parse(dayKey) }.getOrNull() ?: continue
            if (!instanceDate.isAfter(today)) continue // today or past — preserve
            if (t.status != "open") continue // completed / already-archived — preserve
            // Manually overridden or cancelled instances honour the override
            // row as the source of truth; the rule archive must not touch
            // them.
            val override = recurringInstanceDao.get(id, dayKey)
            if (override != null) continue
            archiveTask(t.id)
        }
        // Same rule for blocks: only delete FUTURE recurring blocks. Blocks
        // without an `instanceDate` (older V1/V2 rows that pre-date the V2
        // block schema) are also dropped if they sit in the future.
        timeBlockDao.listByRecurringRule(id).forEach { block ->
            val blockDate = block.instanceDate
                ?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            val isFuture = blockDate != null && blockDate.isAfter(today)
            if (isFuture || (blockDate == null && block.startAt > now)) {
                timeBlockDao.deleteById(block.id)
            }
        }
    }

    /**
     * V2-ME: edit a recurring rule's title / estimate / priority / kind /
     * byDayMask / horizon. The caller chooses whether to:
     *
     *  - **applyToFutureOpenInstances = true**: re-derive open instances on
     *    days strictly after "now" so the new title / estimate / weekday mask
     *    take effect for future unstarted work. The instance row's
     *    `(ruleId, dayKey)` PK is preserved so the call is idempotent.
     *    Completed / manually-overridden / one-off-cancelled instances are
     *    never touched — the user's history stays intact.
     *  - **applyToFutureOpenInstances = false**: only update the rule row
     *    itself. Open future instances keep their old title / estimate until
     *    the user materialises again (or until the planner rebuilds).
     *
     * Returns the updated rule.
     */
    suspend fun updateRecurringRule(
        ruleId: String,
        title: String,
        estimateMinutes: Int,
        priority: Int,
        kind: String,
        byDayMask: Int,
        horizonDays: Int,
        applyToFutureOpenInstances: Boolean
    ): RecurringRuleEntity? {
        val existing = recurringRuleDao.getById(ruleId) ?: return null
        val now = System.currentTimeMillis()
        val updated = existing.copy(
            title = title.trim(),
            estimateMinutes = estimateMinutes.coerceAtLeast(0),
            priority = priority.coerceIn(1, 5),
            kind = kind,
            byDayMask = byDayMask,
            horizonDays = horizonDays.coerceAtLeast(1),
            updatedAt = now
        )
        recurringRuleDao.upsert(updated)
        if (applyToFutureOpenInstances) {
            synchronizeFutureOpenInstances(updated)
        }
        return updated
    }

    /**
     * V2-ME: regenerate open future tasks for a rule so they match the rule's
     * current title / estimate / weekday mask. Completed, cancelled,
     * archived and manually-overridden instances are preserved.
     *
     * Implementation:
     *  1. Walk every open task tied to this rule.
     *  2. Skip any task whose `instanceDate` has a `RecurringInstanceEntity`
     *     override (the user explicitly edited or cancelled it).
     *  3. If the task's `instanceDate` no longer matches the rule's
     *     frequency, the task is dropped from the future plan.
     *  4. Otherwise the task's title / estimate is updated.
     *
     * Past instances (instanceDate < today) are never touched. The call is
     * idempotent.
     */
    suspend fun synchronizeFutureOpenInstances(rule: RecurringRuleEntity) {
        val zone = Zone.systemDefault()
        val today = LocalDate.now(zone)
        val generator = RecurringGenerator(zone)
        val horizonEnd = today.plusDays(rule.horizonDays.toLong())
        val expected = generator
            .generate(rule, today, horizonEnd.plusDays(1), recurringInstanceDao.listForRule(rule.id))
            .associateBy { it.dayKey }

        val openTasks = taskDao.listByRecurringRule(rule.id).filter { it.status == "open" }
        val now = System.currentTimeMillis()
        for (task in openTasks) {
            val dayKey = task.instanceDate ?: continue
            // Past (or today-but-already-started) instance dates are not in
            // the future open set; leave them alone.
            val dayDate = runCatching { LocalDate.parse(dayKey) }.getOrNull() ?: continue
            if (!dayDate.isAfter(today)) continue
            // If the user has explicitly overridden or cancelled this instance,
            // honour that override — it is the source of truth.
            val override = recurringInstanceDao.get(rule.id, dayKey)
            if (override != null) continue
            val match = expected[dayKey]
            if (match == null) {
                // The rule's frequency no longer emits this day. Drop the
                // future task and any future block it produced.
                archiveTask(task.id)
            } else if (task.title != match.title || task.estimateMinutes != match.estimateMinutes) {
                taskDao.update(task.copy(title = match.title, estimateMinutes = match.estimateMinutes, updatedAt = now))
            }
        }
    }

    /**
     * Generate and persist all recurring instances whose `dayKey` lies inside
     * the half-open window `[windowStart, windowEndExclusive)`. The call is
     * idempotent: re-running it never produces duplicate `(ruleId, dayKey)`
     * pairs.
     */
    suspend fun materialiseRecurringInstances(
        windowStart: LocalDate,
        windowEndExclusive: LocalDate
    ): MaterialiseResult {
        val rules = recurringRuleDao.all().filter { !it.archived }
        val generator = RecurringGenerator(Zone.systemDefault())
        val created = mutableListOf<TaskEntity>()
        for (rule in rules) {
            val horizonEnd = rule.startDayKey.let {
                LocalDate.parse(it).plusDays(rule.horizonDays.toLong())
            }
            val effectiveEnd = if (windowEndExclusive.isAfter(horizonEnd)) horizonEnd else windowEndExclusive
            val existingInstances = recurringInstanceDao.listForRule(rule.id)
            val generated = generator.generate(rule, windowStart, effectiveEnd, existingInstances)
            for (g in generated) {
                val existingTask = taskDao.listByRecurringRule(rule.id)
                    .firstOrNull { it.instanceDate == g.dayKey }
                if (existingTask != null) continue
                val task = createTask(
                    title = g.title,
                    estimateMinutes = g.estimateMinutes,
                    priority = g.priority,
                    domain = g.domain,
                    recurringRuleId = g.ruleId,
                    instanceDate = g.dayKey
                )
                created += task
                // Drop a placeholder recurring block at the day's working window start
                // (only when the user later runs the planner; we keep the rule
                // and instance but never write a TimeBlockEntity here so the
                // user's manual blocks remain untouched).
            }
        }
        return MaterialiseResult(created.size, created)
    }

    suspend fun setInstanceOverride(
        ruleId: String,
        dayKey: String,
        title: String? = null,
        estimateMinutes: Int? = null,
        cancelled: Boolean = false
    ) {
        val now = System.currentTimeMillis()
        recurringInstanceDao.upsert(
            RecurringInstanceEntity(
                ruleId = ruleId,
                dayKey = dayKey,
                title = title,
                estimateMinutes = estimateMinutes,
                cancelled = cancelled,
                updatedAt = now
            )
        )
        // If the override updated the title/estimate, mirror it onto the
        // generated task row so the planner and the UI both see it.
        val task = taskDao.listByRecurringRule(ruleId)
            .firstOrNull { it.instanceDate == dayKey }
        if (task != null) {
            val updated = task.copy(
                title = title ?: task.title,
                estimateMinutes = estimateMinutes ?: task.estimateMinutes,
                updatedAt = now
            )
            taskDao.update(updated)
        }
    }

    // ============================================================================
    // V2 — Planner / schedule engine (V2-04 / V2-06)
    // ============================================================================

    /**
     * Build a schedule draft for the half-open window `[windowStart, windowEndExclusive)`
     * by walking every open task, generating recurring instances, collecting
     * fixed blocks + (when enabled) read-only calendar busy intervals, and
     * running the deterministic planner.
     *
     * Drafts are NOT persisted at this stage. They live only in memory until
     * the user explicitly applies them.
     */
    suspend fun buildScheduleDraft(
        windowStart: LocalDate,
        windowEndExclusive: LocalDate
    ): ScheduleDraft {
        val zone = Zone.systemDefault()
        val wsMs = PlannerTime.startOfDayMs(windowStart, zone)
        val weMs = PlannerTime.startOfDayMs(windowEndExclusive, zone)
        val policies = workPolicyDao.all()
        val items = collectPlanItems()
        val busy = collectBusyIntervals(wsMs, weMs)
        val existing = timeBlockDao.listInWindow(wsMs, weMs).map {
            PlannedBlock(
                taskId = it.taskId.orEmpty(),
                title = it.title,
                startMs = it.startAt,
                endMs = it.endAt,
                fixed = it.fixed
            )
        }
        val config = PlannerConfig()
        val planner = PlannerEngine(zone)
        val result = planner.plan(
            windowStart = windowStart,
            windowEnd = windowEndExclusive,
            policies = policies,
            items = items,
            busy = busy,
            existingBlocks = existing,
            config = config
        )
        val fingerprint = computeFingerprint(windowStart, windowEndExclusive, policies, items, busy, existing)
        return ScheduleDraft(
            id = UUID.randomUUID().toString(),
            fingerprint = fingerprint,
            windowStartDay = windowStart.toString(),
            windowEndDay = windowEndExclusive.minusDays(1).toString(),
            createdAt = System.currentTimeMillis(),
            result = result
        )
    }

    /**
     * Apply a draft the user accepted. The call is idempotent: applying the
     * same fingerprint twice (or re-applying after a no-op window refresh)
     * leaves the database untouched and returns an `ApplyResult.NoOp` with
     * a clear reason. Conflicts with newly-fixed blocks at apply time are
     * reported and prevent any partial write.
     */
    suspend fun applyScheduleDraft(draft: ScheduleDraft): ApplyResult {
        // Idempotence: if a draft with the same fingerprint already exists
        // and has been applied, return NoOp without writing.
        val prior = scheduleDraftDao.findByFingerprint(draft.fingerprint)
        if (prior != null && prior.appliedAt != null) {
            return ApplyResult.NoOp("此草案之前已应用，未重复写入。")
        }
        val zone = Zone.systemDefault()
        val wsMs = PlannerTime.startOfDayMs(LocalDate.parse(draft.windowStartDay), zone)
        val weMs = PlannerTime.endOfDayMs(LocalDate.parse(draft.windowEndDay), zone)
        val now = System.currentTimeMillis()
        // Persist the draft row first so a later crash leaves an auditable trail.
        scheduleDraftDao.upsert(
            ScheduleDraftEntity(
                id = draft.id,
                fingerprint = draft.fingerprint,
                createdAt = draft.createdAt,
                appliedAt = null,
                windowStartDay = draft.windowStartDay,
                windowEndDay = draft.windowEndDay,
                note = "applied via UI"
            )
        )
        // Revalidate against the live fixed-block state. If any newly-fixed
        // block now intersects a proposed placement, abort without writing.
        val liveFixed = timeBlockDao.listInWindow(wsMs, weMs).filter { it.fixed }
        val proposedBlocks = draft.result.placements.map { p ->
            PlannedBlock(
                taskId = p.itemId,
                title = tasksById().firstOrNull { it.id == p.itemId }?.title ?: "安排",
                startMs = p.startMs,
                endMs = p.endMs,
                fixed = false,
                draftId = draft.id
            )
        }
        for (block in proposedBlocks) {
            val conflict = liveFixed.firstOrNull { f ->
                f.startAt < block.endMs && f.endAt > block.startMs
            }
            if (conflict != null) {
                return ApplyResult.Conflict(conflict.title, draft)
            }
        }
        // Write each non-conflicting placement.
        val written = mutableListOf<TimeBlockEntity>()
        for (block in proposedBlocks) {
            val entity = TimeBlockEntity(
                id = UUID.randomUUID().toString(),
                taskId = block.taskId.takeIf { it.isNotEmpty() },
                title = block.title,
                startAt = block.startMs,
                endAt = block.endMs,
                fixed = false,
                source = "plan",
                draftId = draft.id
            )
            timeBlockDao.upsert(entity)
            written += entity
        }
        scheduleDraftDao.markApplied(draft.id, now)
        return ApplyResult.Applied(draft.id, written.size)
    }

    /** Remove blocks produced by a draft. Used by "撤销应用". */
    suspend fun revertScheduleDraft(draftId: String) {
        timeBlockDao.listByDraft(draftId).forEach { timeBlockDao.deleteById(it.id) }
    }

    fun observeScheduleDrafts(): Flow<List<ScheduleDraftEntity>> = scheduleDraftDao.observeAll()

    /**
     * V2-06: Apply an [EditedDraftApplyRequest] (UI snapshot of per-placement
     * edits / removals) to the database. The caller's flow is:
     *
     *  - User opens the draft edit dialog (a Compose-only preview).
     *  - UI collects changes into `EditablePlacement` rows.
     *  - On "apply", the UI snapshots those rows into
     *    [EditedDraftApplyRequest] (an immutable value type, no Compose
     *    state) and calls this method.
     *
     * The semantics are:
     *
     *  - Writes happen only after every validation check passes. A failure
     *    of any check leaves the existing plan unchanged; partial writes
     *    are never produced.
     *  - Each row is re-validated for `endMs > startMs`.
     *  - Each row is re-validated to lie inside the draft's inclusive
     *    `[windowStartDay, windowEndDay]` window.
     *  - Overlaps among the proposed edited rows are rejected.
     *  - Overlaps against currently-saved application-owned blocks in the
     *    window — fixed OR non-fixed — are rejected. (Previously the check
     *    skipped non-fixed blocks, which let edited proposals silently
     *    collide with previously-applied draft blocks and with manually
     *    added plan blocks.)
     *  - Duplicate-application protection is preserved by hashing the
     *    edited result into the fingerprint so an edited proposal cannot be
     *    mistaken for the unedited fingerprint previously applied.
     */
    suspend fun applyEditedScheduleDraft(
        request: EditedDraftApplyRequest
    ): ApplyResult {
        val active = request.rows
        if (active.isEmpty()) {
            return ApplyResult.InvalidProposal("所有建议都已被移除，没有可写入的内容。")
        }
        val zone = Zone.systemDefault()
        val windowStartDate = runCatching { LocalDate.parse(request.original.windowStartDay) }
            .getOrElse { return ApplyResult.InvalidProposal("草案窗口起始日期无法解析。") }
        val windowEndDate = runCatching { LocalDate.parse(request.original.windowEndDay) }
            .getOrElse { return ApplyResult.InvalidProposal("草案窗口结束日期无法解析。") }
        val wsMs = PlannerTime.startOfDayMs(windowStartDate, zone)
        // Inclusive last day → exclusive upper bound = start of (endDay + 1).
        val weMs = PlannerTime.endOfDayMs(windowEndDate, zone)
        for (row in active) {
            if (row.endMs <= row.startMs) {
                return ApplyResult.InvalidProposal("「${row.title}」的时间范围不合法。")
            }
            if (row.startMs < wsMs || row.endMs > weMs) {
                return ApplyResult.InvalidProposal(
                    "「${row.title}」的时间 (${row.startMs} – ${row.endMs}) " +
                        "超出草案窗口 [${windowStartDate}, ${windowEndDate}]。"
                )
            }
        }
        // Proposed rows must not overlap each other.
        val sorted = active.sortedBy { it.startMs }
        for (i in 1 until sorted.size) {
            val prev = sorted[i - 1]
            val cur = sorted[i]
            if (cur.startMs < prev.endMs) {
                return ApplyResult.InvalidProposal("「${cur.title}」与上一条建议时间重叠。")
            }
        }
        // Deterministic edited fingerprint must be computed once the proposal
        // is structurally valid, and the duplicate-application check must run
        // BEFORE the live saved-block conflict check. Previously the live-
        // block check ran first; that meant re-applying the same edited
        // proposal (or re-applying after the first apply left the variant
        // fingerprint's blocks in place) tripped a Conflict against its own
        // previously-written blocks instead of taking the NoOp path.
        // Duplicate guard also keys off `appliedAt != null`, so a first
        // successful apply followed by an identical second apply is a clean
        // NoOp — even without a preceding revertScheduleDraft.
        val variantFingerprint = computeEditedFingerprint(
            request.original.fingerprint,
            sorted.map { Triple(it.itemId, it.startMs, it.endMs) }
        )
        val prior = scheduleDraftDao.findByFingerprint(variantFingerprint)
        if (prior != null && prior.appliedAt != null) {
            return ApplyResult.NoOp("此草案（编辑后）之前已应用，未重复写入。")
        }
        // Re-check conflicts against every saved application-owned block
        // inside the window (fixed and non-fixed). Previously this filtered
        // to fixed-only, which let edited rows silently collide with
        // previously applied draft blocks.
        val liveBlocks = timeBlockDao.listInWindow(wsMs, weMs)
        for (row in sorted) {
            val conflict = liveBlocks.firstOrNull { b ->
                b.startAt < row.endMs && b.endAt > row.startMs
            }
            if (conflict != null) {
                return ApplyResult.Conflict(conflict.title, request.original)
            }
        }
        val draftEntity = ScheduleDraftEntity(
            id = UUID.randomUUID().toString(),
            fingerprint = variantFingerprint,
            createdAt = request.original.createdAt,
            appliedAt = null,
            windowStartDay = request.original.windowStartDay,
            windowEndDay = request.original.windowEndDay,
            note = "edited via UI"
        )
        scheduleDraftDao.upsert(draftEntity)
        val now = System.currentTimeMillis()
        val written = mutableListOf<TimeBlockEntity>()
        val titleLookup = tasksById().associate { it.id to it.title }
        for (row in sorted) {
            val entity = TimeBlockEntity(
                id = UUID.randomUUID().toString(),
                taskId = row.itemId.takeIf { it.isNotEmpty() },
                title = titleLookup[row.itemId] ?: row.title,
                startAt = row.startMs,
                endAt = row.endMs,
                fixed = false,
                source = "plan",
                draftId = draftEntity.id
            )
            timeBlockDao.upsert(entity)
            written += entity
        }
        scheduleDraftDao.markApplied(draftEntity.id, now)
        return ApplyResult.Applied(draftEntity.id, written.size)
    }

    private fun computeEditedFingerprint(
        originalFingerprint: String,
        editedRows: List<Triple<String, Long, Long>>
    ): String {
        val md = MessageDigest.getInstance("SHA-256")
        md.update("EDITED|".toByteArray())
        md.update(originalFingerprint.toByteArray())
        editedRows.sortedBy { it.third }.forEach { (id, startMs, endMs) ->
            md.update("$id|$startMs|$endMs\n".toByteArray())
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun computeFingerprint(
        windowStart: LocalDate,
        windowEndExclusive: LocalDate,
        policies: List<WorkPolicyEntity>,
        items: List<PlanItem>,
        busy: List<BusyInterval>,
        existing: List<PlannedBlock>
    ): String {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(windowStart.toString().toByteArray())
        md.update(windowEndExclusive.toString().toByteArray())
        policies.sortedBy { it.dayOfWeek }.forEach {
            md.update("${it.dayOfWeek}|${it.enabled}|${it.startMin}|${it.endMin}|${it.bufferMinutes}|${it.dailyCapMinutes}".toByteArray())
        }
        items.sortedBy { it.id }.forEach {
            md.update("${it.id}|${it.priority}|${it.estimateMinutes}|${it.dueAt}|${it.blocked}|${it.dependsOn.sorted().joinToString(",")}".toByteArray())
        }
        busy.sortedBy { it.startMs }.forEach {
            md.update("${it.startMs}|${it.endMs}|${it.source}".toByteArray())
        }
        existing.sortedBy { it.startMs }.forEach {
            md.update("${it.taskId}|${it.startMs}|${it.endMs}".toByteArray())
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private suspend fun listOpenTasksSnapshot(): List<TaskEntity> {
        return taskDao.getAllOpenSnapshot()
    }

    private suspend fun tasksById(): List<TaskEntity> = taskDao.getAllSnapshot()

    private suspend fun collectPlanItems(): List<PlanItem> {
        val zone = Zone.systemDefault()
        val now = LocalDate.now(zone)
        val horizonEnd = now.plusDays(14)
        materialiseRecurringInstances(now, horizonEnd.plusDays(1))
        val openTasks = taskDao.getAllSnapshot().filter { it.status == "open" }
        val relations = relationDao.getAll()
        val taskById = openTasks.associateBy { it.id }
        val blockedBy = mutableMapOf<String, Set<String>>()
        relations.filter { it.kind == "depends_on" }.forEach { r ->
            blockedBy[r.fromId] = (blockedBy[r.fromId] ?: emptySet()) + r.toId
        }
        val blockedByDone = blockedBy.filterValues { ids ->
            ids.any { id -> taskById[id]?.status != "done" }
        }.keys
        return openTasks.map { t ->
            val blocked = t.id in blockedByDone
            val deps = blockedBy[t.id].orEmpty()
            // Locked if the task already has a fixed block in the next 14 days
            val fixedBlock = timeBlockDao.listByTask(t.id).firstOrNull { it.fixed }
            PlanItem(
                id = t.id,
                title = t.title,
                estimateMinutes = t.estimateMinutes,
                priority = t.priority,
                dueAt = t.dueAt,
                dependsOn = deps,
                blocked = blocked,
                locked = fixedBlock != null && fixedBlock.startAt >= PlannerTime.startOfDayMs(now, zone) &&
                    fixedBlock.startAt < PlannerTime.startOfDayMs(horizonEnd.plusDays(1), zone)
            )
        }
    }

    private suspend fun collectBusyIntervals(startMs: Long, endMs: Long): List<BusyInterval> {
        // V2-ME: system calendar integration is gone. The planner only sees
        // busy intervals that the user added as fixed blocks inside the app.
        return timeBlockDao.listInWindow(startMs, endMs)
            .filter { it.fixed }
            .map { BusyInterval(it.startAt, it.endAt, com.yinian.android.planner.BusySource.FixedBlock) }
    }
}

// ============================================================================
// Public V2 model types
// ============================================================================

data class ScheduleDraft(
    val id: String,
    val fingerprint: String,
    val windowStartDay: String,
    val windowEndDay: String,
    val createdAt: Long,
    val result: PlanResult
)

sealed class ApplyResult {
    data class Applied(val draftId: String, val placedBlocks: Int) : ApplyResult()
    data class NoOp(val reason: String) : ApplyResult()
    data class Conflict(
        val fixedBlockTitle: String,
        val draft: ScheduleDraft
    ) : ApplyResult()
    data class InvalidProposal(val reason: String) : ApplyResult()
}

data class MaterialiseResult(
    val created: Int,
    val tasks: List<TaskEntity>
)

data class DayCapacityRow(
    val dayKey: String,
    val availableMinutes: Int,
    val scheduledMinutes: Int,
    val capMinutes: Int
)

data class CapacitySnapshot(
    val weekStartMs: Long,
    val weekEndMs: Long,
    val days: List<DayCapacityRow>,
    val totalAvailable: Int,
    val totalScheduled: Int,
    val totalCap: Int
)

// ============================================================================
// V2-ME — Focus attribution
// ============================================================================

/** Per-day focus total returned by [FocusAttribution.totalsByDay]. */
data class FocusDayTotal(
    /** ISO local day key (yyyy-MM-dd). */
    val dayKey: String,
    /** Sum of active-segment ms that fell on this local day. */
    val activeMs: Long,
    /** Count of focus sessions whose active segments touched this day. */
    val sessionCount: Int
)

/**
 * Attribution rules that turn a list of `FocusSessionEntity` rows plus a
 * (possibly empty) list of `FocusSessionSegmentEntity` rows into per-day
 * and per-task actual focus minutes.
 *
 * **V4 sessions (have segments)**: walk every `kind = "active"` segment
 * whose `[startedAt, endedAt]` intersects the window, split it across
 * local-day boundaries using the supplied zone, and add the duration to
 * each touched day. Paused segments and `endedAt = NULL` segments ending
 * at "now" are both supported.
 *
 * **V3 fallback (session has no segments in the segments table)**:
 *   1. Skip the session entirely if its status is `cancelled` — cancelled
 *      sessions are excluded from "实际投入".
 *   2. Compute total active ms from `pausedMs + max(0, now - activeStartedAt)`
 *      (only when status is `active`, `paused`, or `completed`). Past
 *      cancelled sessions were already skipped above.
 *   3. Anchor a single virtual active segment ending at `endedAt` if set,
 *      otherwise at "now", and starting at `ended - totalActiveMs` (or
 *      `startedAt` if the anchor would underflow). The segment is split
 *      across local days the same way a V4 segment is.
 *
 * The V3 fallback is intentionally simple: it loses pause/resume structure
 * but never double-counts paused time because totalActiveMs already excludes
 * paused minutes. Past instances (startedAt < now − 30d say) still surface
 * in the totals, which matches the V1/V2 review behaviour. Tests covering
 * the fallback live in the test child's `FocusAttributionTest`.
 */
object FocusAttribution {

    fun totalsByDay(
        sessions: List<FocusSessionEntity>,
        segments: List<FocusSessionSegmentEntity>,
        startMs: Long,
        endMs: Long,
        zone: ZoneId,
        nowMs: Long = System.currentTimeMillis()
    ): List<FocusDayTotal> {
        val segmentsBySession = segments.groupBy { it.sessionId }
        val perDay = mutableMapOf<String, FocusDayTotal>()
        for (session in sessions) {
            val contributions = attributeSession(session, segmentsBySession[session.id].orEmpty(), nowMs)
            for ((dayKey, ms) in contributions) {
                if (ms <= 0) continue
                val existing = perDay[dayKey]
                perDay[dayKey] = if (existing == null) {
                    FocusDayTotal(dayKey, ms, 1)
                } else {
                    existing.copy(activeMs = existing.activeMs + ms, sessionCount = existing.sessionCount + 1)
                }
            }
        }
        // Drop days strictly outside the [startMs, endMs) window so the
        // caller can ask for a specific period without re-filtering.
        val startKey = PlannerTime.toDayKey(java.time.Instant.ofEpochMilli(startMs).atZone(zone).toLocalDate())
        val endExclusiveKey = PlannerTime.toDayKey(java.time.Instant.ofEpochMilli(endMs).atZone(zone).toLocalDate())
        return perDay.entries
            .filter { (key, _) -> key.compareTo(startKey) >= 0 && key.compareTo(endExclusiveKey) < 0 }
            .sortedBy { it.key }
            .map { it.value }
    }

    fun actualMsByTask(
        sessions: List<FocusSessionEntity>,
        segments: List<FocusSessionSegmentEntity>,
        startMs: Long,
        endMs: Long,
        zone: ZoneId,
        nowMs: Long = System.currentTimeMillis()
    ): Map<String, Long> {
        val segmentsBySession = segments.groupBy { it.sessionId }
        val out = mutableMapOf<String, Long>()
        for (session in sessions) {
            val taskId = session.taskId ?: continue
            val contributions = attributeSession(session, segmentsBySession[session.id].orEmpty(), nowMs)
            val dayFiltered = filterToWindow(contributions, startMs, endMs, zone)
            val ms = dayFiltered.values.sum()
            if (ms <= 0) continue
            out[taskId] = (out[taskId] ?: 0L) + ms
        }
        return out
    }

    private fun filterToWindow(
        contributions: Map<String, Long>,
        startMs: Long,
        endMs: Long,
        zone: ZoneId
    ): Map<String, Long> {
        val startKey = PlannerTime.toDayKey(java.time.Instant.ofEpochMilli(startMs).atZone(zone).toLocalDate())
        val endExclusiveKey = PlannerTime.toDayKey(java.time.Instant.ofEpochMilli(endMs).atZone(zone).toLocalDate())
        return contributions.filter { (key, _) -> key.compareTo(startKey) >= 0 && key.compareTo(endExclusiveKey) < 0 }
    }

    /**
     * Returns `dayKey -> ms` for a single session, applying either V4 segment
     * attribution or the V3 fallback.
     */
    internal fun attributeSession(
        session: FocusSessionEntity,
        segments: List<FocusSessionSegmentEntity>,
        nowMs: Long
    ): Map<String, Long> {
        if (session.status == "cancelled") return emptyMap()
        val activeSegments = segments.filter { it.kind == "active" }
        if (activeSegments.isNotEmpty()) {
            val out = mutableMapOf<String, Long>()
            for (seg in activeSegments) {
                val end = seg.endedAt ?: nowMs
                if (end <= seg.startedAt) continue
                splitAcrossDays(seg.startedAt, end, out)
            }
            return out
        }
        // V3 fallback
        val totalActive = v3TotalActiveMs(session, nowMs)
        if (totalActive <= 0L) return emptyMap()
        val anchorEnd = session.endedAt ?: nowMs
        val anchorStart = (anchorEnd - totalActive).coerceAtLeast(session.startedAt)
        val out = mutableMapOf<String, Long>()
        splitAcrossDays(anchorStart, anchorEnd, out)
        return out
    }

    private fun v3TotalActiveMs(session: FocusSessionEntity, nowMs: Long): Long {
        val accumulated = session.pausedMs
        val liveSegment = session.activeStartedAt?.let { (nowMs - it).coerceAtLeast(0L) } ?: 0L
        return accumulated + liveSegment
    }

    /**
     * Split the half-open interval `[startMs, endMs)` across local-day
     * boundaries and add each slice's duration to `accumulator`, keyed by
     * ISO day key.
     */
    private fun splitAcrossDays(startMs: Long, endMs: Long, accumulator: MutableMap<String, Long>) {
        if (endMs <= startMs) return
        val zone = ZoneId.systemDefault()
        var cur = startMs
        while (cur < endMs) {
            val instant = java.time.Instant.ofEpochMilli(cur)
            val date = instant.atZone(zone).toLocalDate()
            val nextDayStart = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
            val sliceEnd = minOf(endMs, nextDayStart)
            val dayKey = date.toString()
            val sliceMs = sliceEnd - cur
            if (sliceMs > 0) accumulator[dayKey] = (accumulator[dayKey] ?: 0L) + sliceMs
            cur = sliceEnd
        }
    }
}

enum class DependencyCheck { Ok, SelfLink, Cycle }
sealed class AddBlockResult {
    data class Ok(val block: TimeBlockEntity) : AddBlockResult()
    data class ConflictsWithFixed(val block: TimeBlockEntity) : AddBlockResult()
    data object InvalidRange : AddBlockResult()
}
