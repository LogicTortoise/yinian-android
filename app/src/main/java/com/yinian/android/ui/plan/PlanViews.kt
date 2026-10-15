package com.yinian.android.ui.plan

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.yinian.android.data.entity.TimeBlockEntity
import com.yinian.android.ui.common.PillBadge
import com.yinian.android.ui.common.SoftCard
import java.time.LocalDate

/** Stable test tags so the test child can address view controls. */
object PlanTestTags {
    const val VIEW_TABS = "plan-view-tabs"
    const val VIEW_TAB_DAY = "plan-view-tab-day"
    const val VIEW_TAB_WEEK = "plan-view-tab-week"
    const val VIEW_TAB_MONTH = "plan-view-tab-month"
    const val NAV_PREV = "plan-nav-prev"
    const val NAV_NEXT = "plan-nav-next"
    const val NAV_TODAY = "plan-nav-today"
    const val MONTH_GRID = "plan-month-grid"
    const val MONTH_CELL_PREFIX = "plan-month-cell-"
    const val WEEK_ROW = "plan-week-row"
    const val WEEK_CELL_PREFIX = "plan-week-cell-"
    const val DAY_PANE = "plan-day-pane"
    const val DAY_BLOCK_PREFIX = "plan-day-block-"
}

/**
 * Header strip: view selector + previous/next + today. Always reachable
 * even when the screen content is long: it lives outside the scrolling
 * region of the view bodies.
 */
@Composable
fun PlanViewHeader(
    mode: PlanViewMode,
    onSelectMode: (PlanViewMode) -> Unit,
    title: String,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onToday: () -> Unit,
    isToday: Boolean,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ViewTabs(
                mode = mode,
                onSelect = onSelectMode,
                modifier = Modifier.weight(1f, fill = true)
            )
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = onToday, enabled = !isToday) {
                Text("今天")
            }
        }
        Spacer(Modifier.height(4.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onPrev) {
                Icon(
                    Icons.Outlined.ChevronLeft,
                    contentDescription = "上一段"
                )
            }
            Text(
                modifier = Modifier
                    .weight(1f)
                    .testTag("plan-nav-title"),
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            IconButton(onClick = onNext) {
                Icon(
                    Icons.Outlined.ChevronRight,
                    contentDescription = "下一段"
                )
            }
        }
    }
}

@Composable
private fun TextButton(
    onClick: () -> Unit,
    enabled: Boolean = true,
    content: @Composable () -> Unit
) {
    androidx.compose.material3.TextButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .semantics { contentDescription = "今天按钮" }
            .testTag(PlanTestTags.NAV_TODAY)
    ) {
        content()
    }
}

@Composable
private fun ViewTabs(
    mode: PlanViewMode,
    onSelect: (PlanViewMode) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .background(
                MaterialTheme.colorScheme.surfaceVariant,
                RoundedCornerShape(999.dp)
            )
            .padding(4.dp)
            .testTag(PlanTestTags.VIEW_TABS)
            .semantics { contentDescription = "视图切换" },
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        TabChip(
            label = "日",
            selected = mode == PlanViewMode.Day,
            onClick = { onSelect(PlanViewMode.Day) },
            testTag = PlanTestTags.VIEW_TAB_DAY,
            contentDesc = "切换到日视图"
        )
        TabChip(
            label = "周",
            selected = mode == PlanViewMode.Week,
            onClick = { onSelect(PlanViewMode.Week) },
            testTag = PlanTestTags.VIEW_TAB_WEEK,
            contentDesc = "切换到周视图"
        )
        TabChip(
            label = "月",
            selected = mode == PlanViewMode.Month,
            onClick = { onSelect(PlanViewMode.Month) },
            testTag = PlanTestTags.VIEW_TAB_MONTH,
            contentDesc = "切换到月视图"
        )
    }
}

@Composable
private fun TabChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    testTag: String,
    contentDesc: String
) {
    Box(
        modifier = Modifier
            .background(
                if (selected) MaterialTheme.colorScheme.surface else Color.Transparent,
                RoundedCornerShape(999.dp)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .testTag(testTag)
            .semantics { contentDescription = contentDesc }
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** Month view: 6×7 grid with local block markers / counts per day. */
@Composable
fun MonthGridView(
    summary: MonthSummary,
    today: LocalDate,
    selectedDate: LocalDate,
    onPickDay: (LocalDate) -> Unit,
    modifier: Modifier = Modifier
) {
    val cells = summary.grid.chunked(7)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag(PlanTestTags.MONTH_GRID)
    ) {
        WeekdayHeader()
        for (week in cells) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(72.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                for (day in week) {
                    MonthCell(
                        summary = day,
                        isToday = day.date == today,
                        isSelected = day.date == selectedDate,
                        isInMonth = PlanDate.isSameMonth(summary.anchor, day.date),
                        onPick = { onPickDay(day.date) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

@Composable
private fun WeekdayHeader() {
    val labels = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        for (label in labels) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = 4.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun MonthCell(
    summary: DaySummary,
    isToday: Boolean,
    isSelected: Boolean,
    isInMonth: Boolean,
    onPick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val bg = when {
        isSelected -> MaterialTheme.colorScheme.primaryContainer
        isToday -> MaterialTheme.colorScheme.surfaceVariant
        else -> Color.Transparent
    }
    val border = if (isToday && !isSelected) MaterialTheme.colorScheme.primary else Color.Transparent
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(2.dp)
            .background(bg, RoundedCornerShape(12.dp))
            .border(1.dp, border, RoundedCornerShape(12.dp))
            .clickable(onClick = onPick)
            .padding(6.dp)
            .testTag(PlanTestTags.MONTH_CELL_PREFIX + summary.date)
            .semantics {
                contentDescription = "日期 ${PlanDate.dayLabel(summary.date)}，" +
                    "${summary.blockCount} 个时间块"
            },
        contentAlignment = Alignment.TopStart
    ) {
        Column {
            Text(
                summary.date.dayOfMonth.toString(),
                style = MaterialTheme.typography.titleSmall,
                color = if (isInMonth) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(2.dp))
            if (summary.blockCount > 0) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .background(
                                MaterialTheme.colorScheme.primary,
                                CircleShape
                            )
                    )
                    Text(
                        "${summary.blockCount}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                Spacer(Modifier.height(6.dp))
            }
        }
    }
}

/** Week view: 7 horizontal day cards showing local block counts. */
@Composable
fun WeekStripView(
    summary: WeekSummary,
    today: LocalDate,
    selectedDate: LocalDate,
    onPickDay: (LocalDate) -> Unit,
    modifier: Modifier = Modifier
) {
    val scroll = rememberScrollState()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(scroll)
            .testTag(PlanTestTags.WEEK_ROW)
            .semantics { contentDescription = "周视图 7 天" },
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        for (day in summary.days) {
            WeekCell(
                summary = day,
                isToday = day.date == today,
                isSelected = day.date == selectedDate,
                onPick = { onPickDay(day.date) },
                modifier = Modifier.width(96.dp)
            )
        }
    }
}

@Composable
private fun WeekCell(
    summary: DaySummary,
    isToday: Boolean,
    isSelected: Boolean,
    onPick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val bg = when {
        isSelected -> MaterialTheme.colorScheme.primaryContainer
        isToday -> MaterialTheme.colorScheme.surfaceVariant
        else -> MaterialTheme.colorScheme.surface
    }
    Box(
        modifier = modifier
            .background(bg, RoundedCornerShape(16.dp))
            .clickable(onClick = onPick)
            .padding(10.dp)
            .testTag(PlanTestTags.WEEK_CELL_PREFIX + summary.date)
            .semantics {
                contentDescription = "日期 ${PlanDate.dayLabel(summary.date)}，" +
                    "${summary.blockCount} 个时间块，" +
                    "${summary.totalMinutes} 分钟"
            }
    ) {
        Column {
            Text(
                PlanDate.weekdayLabel(summary.date),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                summary.date.dayOfMonth.toString(),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(4.dp))
            if (summary.blockCount > 0) {
                PillBadge("${summary.blockCount} 项")
            } else {
                Text(
                    "无安排",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (summary.totalMinutes > 0) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "${summary.totalMinutes} 分",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * Day view body: ordered local time blocks for the selected day + a slot to
 * show empty-day messaging. Content is rendered inside a column so the
 * caller controls the surrounding scroll behaviour.
 */
@Composable
fun DayBlocksPane(
    blocks: List<TimeBlockEntity>,
    onRemoveBlock: (TimeBlockEntity) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag(PlanTestTags.DAY_PANE)
    ) {
        if (blocks.isEmpty()) {
            SoftCard(modifier = Modifier.fillMaxWidth()) {
                Text("这一天还没有安排。", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(4.dp))
                Text(
                    "你可以在下面手动添加一个时间块，或在任务详情里安排。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            for (block in blocks) {
                DayBlockRow(
                    block = block,
                    onRemove = { onRemoveBlock(block) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                )
            }
        }
    }
}

@Composable
private fun DayBlockRow(
    block: TimeBlockEntity,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier
) {
    SoftCard(modifier = modifier, cornerRadius = 18.dp, edgeThickness = 2.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(block.title, style = MaterialTheme.typography.titleSmall)
                Text(
                    PlanTimeLabels.timeOf(block.startAt) + " - " +
                        PlanTimeLabels.timeOf(block.endAt),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            when {
                block.fixed -> {
                    PillBadge(
                        "固定",
                        background = MaterialTheme.colorScheme.tertiaryContainer,
                        contentColor = MaterialTheme.colorScheme.tertiary
                    )
                    Spacer(Modifier.width(6.dp))
                }
                block.source == "plan" -> {
                    PillBadge(
                        "排期草案",
                        background = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Spacer(Modifier.width(6.dp))
                }
                block.source == "recurring" -> {
                    PillBadge(
                        "重复",
                        background = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Spacer(Modifier.width(6.dp))
                }
            }
            IconButton(onClick = onRemove) {
                Icon(Icons.Outlined.Close, contentDescription = "移除时间块")
            }
        }
    }
}

/** Localised time-of-day label used inside the day pane. */
private object PlanTimeLabels {
    private val fmt = java.text.SimpleDateFormat("HH:mm", java.util.Locale.US)
    fun timeOf(ms: Long): String = fmt.format(java.util.Date(ms))
}