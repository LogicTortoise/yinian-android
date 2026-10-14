package com.yinian.android.data

import com.yinian.android.data.db.AppDatabase
import com.yinian.android.data.entity.IdeaEntity
import com.yinian.android.data.entity.RelationEntity
import com.yinian.android.data.entity.TaskEntity
import com.yinian.android.data.entity.TimeBlockEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Inserts a small set of clearly-labelled example data on the very first launch so the
 * app is not blank. Subsequent launches are a no-op (the count check is atomic).
 */
class SeedInitializer(
    private val database: AppDatabase,
    private val scope: CoroutineScope
) {
    fun runIfEmpty() {
        scope.launch(Dispatchers.IO) {
            val count = database.ideaDao().count()
            if (count > 0) return@launch
            val now = System.currentTimeMillis()
            val day = 24L * 3600_000L

            val idea1 = IdeaEntity(
                id = UUID.randomUUID().toString(),
                title = "晚饭想吃点清淡的",
                body = "中午吃得太油了，晚上想煮点面条或者简单的沙拉。",
                source = "text",
                createdAt = now - 4 * 3600_000L,
                updatedAt = now - 4 * 3600_000L
            )
            val idea2 = IdeaEntity(
                id = UUID.randomUUID().toString(),
                title = "周末要不要去爬山",
                body = "查一下天气，决定走哪条线。",
                source = "text",
                createdAt = now - 26 * 3600_000L,
                updatedAt = now - 26 * 3600_000L
            )
            val idea3 = IdeaEntity(
                id = UUID.randomUUID().toString(),
                title = "回看上周的专注记录",
                body = "复盘哪几次是真的在做事。",
                source = "voice",
                createdAt = now - 3 * day,
                updatedAt = now - 3 * day,
                transcriptStatus = "disabled"
            )
            database.ideaDao().upsert(idea1)
            database.ideaDao().upsert(idea2)
            database.ideaDao().upsert(idea3)

            val taskA = TaskEntity(
                id = UUID.randomUUID().toString(),
                title = "整理今天的笔记",
                notes = "把闪念里能转成动作的挑出来。",
                status = "open",
                priority = 2,
                estimateMinutes = 25,
                dueAt = now + 6 * 3600_000L,
                createdAt = now - 3600_000L,
                updatedAt = now - 3600_000L,
                domain = "work"
            )
            val taskB = TaskEntity(
                id = UUID.randomUUID().toString(),
                title = "回复客户的邮件",
                notes = "草稿先写一版，等我确认再发。",
                status = "open",
                priority = 1,
                estimateMinutes = 15,
                dueAt = now + 3 * 3600_000L,
                createdAt = now - 5 * 3600_000L,
                updatedAt = now - 5 * 3600_000L,
                domain = "work"
            )
            val taskC = TaskEntity(
                id = UUID.randomUUID().toString(),
                title = "买牛奶",
                notes = "楼下便利店就行。",
                status = "open",
                priority = 3,
                estimateMinutes = 10,
                createdAt = now - 30 * 60_000L,
                updatedAt = now - 30 * 60_000L,
                domain = "life"
            )
            val taskD = TaskEntity(
                id = UUID.randomUUID().toString(),
                title = "写周报",
                notes = "基于「整理今天的笔记」。",
                status = "open",
                priority = 2,
                estimateMinutes = 45,
                createdAt = now - 2 * 3600_000L,
                updatedAt = now - 2 * 3600_000L,
                domain = "work",
                derivedFromIdeaId = idea1.id
            )
            database.taskDao().upsert(taskA)
            database.taskDao().upsert(taskB)
            database.taskDao().upsert(taskC)
            database.taskDao().upsert(taskD)

            // taskD depends on taskA.
            database.relationDao().upsert(
                RelationEntity(taskD.id, taskA.id, "depends_on", now)
            )
            // idea1 is the source of taskD (already encoded via derivedFromIdeaId).

            // One example fixed block today 14:00-15:00 and a manual block 16:00-16:30.
            val cal = java.util.Calendar.getInstance().apply {
                timeInMillis = now
                set(java.util.Calendar.HOUR_OF_DAY, 14)
                set(java.util.Calendar.MINUTE, 0)
                set(java.util.Calendar.SECOND, 0)
                set(java.util.Calendar.MILLISECOND, 0)
            }
            val fixedStart = cal.timeInMillis
            val fixedEnd = fixedStart + 60 * 60_000L
            val manualStart = fixedEnd + 60 * 60_000L
            val manualEnd = manualStart + 30 * 60_000L
            database.timeBlockDao().upsert(
                TimeBlockEntity(
                    id = UUID.randomUUID().toString(),
                    taskId = taskB.id,
                    title = "会议",
                    startAt = fixedStart,
                    endAt = fixedEnd,
                    fixed = true
                )
            )
            database.timeBlockDao().upsert(
                TimeBlockEntity(
                    id = UUID.randomUUID().toString(),
                    taskId = taskA.id,
                    title = "整理笔记",
                    startAt = manualStart,
                    endAt = manualEnd,
                    fixed = false
                )
            )
        }
    }
}
