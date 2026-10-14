package com.yinian.android.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "ideas")
data class IdeaEntity(
    @PrimaryKey val id: String,
    val title: String,
    val body: String,
    val source: String,            // "text" | "voice" | "system"
    val createdAt: Long,
    val updatedAt: Long,
    val archived: Boolean = false,
    val recordingPath: String? = null,
    val transcriptStatus: String = "none" // "none" | "pending" | "ready" | "failed" | "disabled"
)

@Entity(tableName = "tasks")
data class TaskEntity(
    @PrimaryKey val id: String,
    val title: String,
    val notes: String,
    val status: String,            // "open" | "done" | "archived"
    val priority: Int,             // user priority 1..5 (1 highest)
    val estimateMinutes: Int,
    val dueAt: Long? = null,
    val createdAt: Long,
    val updatedAt: Long,
    val derivedFromIdeaId: String? = null,
    val domain: String = "general" // "work" | "life" | "general"
)

@Entity(tableName = "relations", primaryKeys = ["fromId", "toId", "kind"])
data class RelationEntity(
    val fromId: String,
    val toId: String,
    val kind: String,              // "depends_on" | "related" | "derived_from"
    val createdAt: Long
)

@Entity(tableName = "time_blocks")
data class TimeBlockEntity(
    @PrimaryKey val id: String,
    val taskId: String?,
    val title: String,
    val startAt: Long,
    val endAt: Long,
    val fixed: Boolean = false,
    val source: String = "manual"  // "manual" | "system"
)

@Entity(tableName = "focus_sessions")
data class FocusSessionEntity(
    @PrimaryKey val id: String,
    val taskId: String?,
    val startedAt: Long,
    val endedAt: Long?,
    /**
     * Total active ms accumulated before the current run. Doubles as the elapsed
     * shown while the session is paused/completed/cancelled.
     */
    val pausedMs: Long = 0,
    /**
     * Wall-clock when the current active segment started. Null when the session
     * is not actively running (paused, completed, or cancelled).
     */
    val activeStartedAt: Long? = null,
    val status: String,            // "active" | "paused" | "completed" | "cancelled"
    val note: String = ""
)
