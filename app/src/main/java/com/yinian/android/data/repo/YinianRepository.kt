package com.yinian.android.data.repo

import com.yinian.android.audio.AudioRecorder
import com.yinian.android.calendar.CalendarBusyReader
import com.yinian.android.data.dao.FocusDao
import com.yinian.android.data.dao.IdeaDao
import com.yinian.android.data.dao.RecurringInstanceDao
import com.yinian.android.data.dao.RecurringRuleDao
import com.yinian.android.data.dao.RelationDao
import com.yinian.android.data.dao.ScheduleDraftDao
import com.yinian.android.data.dao.TaskDao
import com.yinian.android.data.dao.TimeBlockDao
import com.yinian.android.data.dao.WorkPolicyDao
import com.yinian.android.data.entity.FocusSessionEntity
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
import kotlinx.coroutines.flow.first
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
    private val workPolicyDao: WorkPolicyDao,
    private val recurringRuleDao: RecurringRuleDao,
    private val recurringInstanceDao: RecurringInstanceDao,
    private val scheduleDraftDao: ScheduleDraftDao,
    private val prefs: PrefsRepository,
    val audioRecorder: AudioRecorder,
    private val calendarBusyReader: CalendarBusyReader
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
        // Drop pending future instances; keep past instances intact so the
        // user's history is preserved.
        val tasks = taskDao.listByRecurringRule(id)
        val nowMs = System.currentTimeMillis()
        for (t in tasks) {
            if (t.status == "open" && (t.dueAt == null || t.dueAt > nowMs)) {
                archiveTask(t.id)
            }
        }
        timeBlockDao.listByRecurringRule(id).forEach { block ->
            if (block.startAt > nowMs) timeBlockDao.deleteById(block.id)
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
        val blocks = timeBlockDao.listInWindow(startMs, endMs)
            .filter { it.fixed }
            .map { BusyInterval(it.startAt, it.endAt, com.yinian.android.planner.BusySource.FixedBlock) }
        val calEnabled = prefs.calendarEnabled.first()
        val calBusy = if (calEnabled && calendarBusyReader.hasReadPermission()) {
            calendarBusyReader.readBusyIntervals(startMs, endMs)
        } else emptyList()
        return blocks + calBusy
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

enum class DependencyCheck { Ok, SelfLink, Cycle }
sealed class AddBlockResult {
    data class Ok(val block: TimeBlockEntity) : AddBlockResult()
    data class ConflictsWithFixed(val block: TimeBlockEntity) : AddBlockResult()
    data object InvalidRange : AddBlockResult()
}
