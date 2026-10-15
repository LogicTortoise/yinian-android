# V2 自动化测试报告（V2-CAL / V2-CAL-REVIEW-FIX / V2-ME 一体化最终）

本报告汇总 Yinian Android V2 端到端自动化测试的最终结果，覆盖仓库当前
`main` 分支（含 V2-CAL 日 / 周 / 月视图、可编辑草案 + V2-CAL-REVIEW-FIX
草案边界修复 + V2-ME 系统日历剥离、重复规则编辑、专注回顾与 v3→v4 迁
移）的 JVM 单元测试、设备端集成测试、迁移测试和权限烟测。

| 子版本 | 提交 / 标签 | 范围 |
|---|---|---|
| V2-CAL | 计划页日 / 周 / 月视图、`PlanDate` 帮手、可编辑草案（`EditableDraft`、`DraftEditDialog`） | P1 + P2 |
| V2-CAL-REVIEW-FIX | 月视图 42 天全宽查询、`data/repo/EditedDraftApplyRequest` 数据/UI 层分离、apply 之前空 / 范围 / 重叠 / 越界五重门 | P1 + P2 复盘 |
| V2-ME | 删除 `READ_CALENDAR` / `WRITE_CALENDAR` / `CalendarBusyReader`；引入 `focus_session_segments` + `MIGRATION_3_4`；`updateRecurringRule` + `synchronizeFutureOpenInstances`；`FocusReviewDialog` 日/周 + 当前进行中 + 跨午夜分摊 + V3 回退归因；Me 页可达性修复 | P0 + P3 + P4 |
| V2-DRAFT-IDEMPOTENCY-FIX | `applyEditedScheduleDraft` 重复保护门提前到 live-block 冲突检查之前 | 编辑后变体指纹幂等 |
| V2-FOCUS-REVIEW-CRASH-FIX | `FocusReviewDialog` 把 `return@Column` 早返回拆成平衡的 `if / else` 两分支，修复空数据初次组合 `SlotTable.length=0; index=-5` Compose 运行时崩溃 | 弹窗可重复打开 |

> V2-TEST-REGRESSION（2026-10-16）追加：修正 `RepositoryApplyEditedDraftTest` 的连续调用幂等用例（去除 `revertScheduleDraft`），重命名并修正 `RepositoryV2MeRecurringTest` 中与 `recurringRuleId` / `PlanItem.locked` 相关的用例，新增 `PlanMeReviewSmokeTest.me_page_focus_review_dialog_exposes_day_week_toggle_and_navigation_controls` 冒烟；该冒烟用例在 `outputs/v2-test-regression.md` 报告里稳定暴露 `FocusReviewDialog` 弹窗的 `SlotTable.length=0; index=-5` 崩溃并被作为生产 handoff 留给开发子代理，未通过弱化断言 / 删除用例 / `@Ignore` 等方式绕过。V2-TEST-FINAL（2026-10-16，本次最终回归）在 V2-FOCUS-REVIEW-CRASH-FIX 落地后跑完整两套件，168 / 0 / 0 / 0 全部通过；详见本地工件 `outputs/v2-test-final.md`（该目录被 `.gitignore` 排除，不纳入版本控制，仅供本地留存）。

## 环境

- JDK 17（Homebrew `openjdk@17` 17.0.19，路径
  `/opt/homebrew/Cellar/openjdk@17/17.0.19/libexec/openjdk.jdk/Contents/Home`）。
- Android SDK Platform 34、Build-Tools 34.0.0、Platform-Tools。
- AVD：`Pixel_3a_API_34_extension_level_7_arm64-v8a`（Android 14 / API 34
  / ABI `arm64-v8a`）。本地额外连接的物理机 `127.0.0.1:5555` (Samsung
  `SM_G998B`) **不参与**本测试。
- ADB 序列号：API 34 模拟器 = `emulator-5554`（**唯一**真正参与测试的
  设备）。每条 `connectedDebugAndroidTest` 命令显式带
  `ANDROID_SERIAL=emulator-5554`。
- 时区：测试 JVM 一律运行在 `TZ=UTC`，确保生产代码中
  `FocusAttribution.splitAcrossDays` 的本地日期分摊结果是确定性的；这
  个常量也是仓库外的环境约束、不写入任何代码。

## 套件命令（一次性按顺序执行）

```bash
export JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.19/libexec/openjdk.jdk/Contents/Home
export ANDROID_HOME=$HOME/Library/Android/sdk
export ANDROID_SDK_ROOT=$HOME/Library/Android/sdk
export PATH=$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$PATH
export TZ=UTC

# 1) 纯 JVM 单元测试 (Android-free)
./gradlew :app:testDebugUnitTest --rerun-tasks --console=plain --no-daemon

# 2) 设备端集成 + Compose UI 烟测 (API 34 模拟器 only)
ANDROID_SERIAL=emulator-5554 \
  ./gradlew :app:connectedDebugAndroidTest --console=plain --no-daemon
```

`testDebugUnitTest` 先跑通：它会一并构建 debug + test APK，
是 `connectedDebugAndroidTest` 的前置。

## 最终结果

| 套件 | 测试类 | 测试数 | 通过 | 失败 | 跳过 |
|---|---|---:|---:|---:|---:|
| `:app:testDebugUnitTest` | `DateUtilTest` | 8 | 8 | 0 | 0 |
| `:app:testDebugUnitTest` | `DurationFormatterTest` | 4 | 4 | 0 | 0 |
| `:app:testDebugUnitTest` | `ManifestPermissionsTest` | 3 | 3 | 0 | 0 |
| `:app:testDebugUnitTest` | `PlannerEngineTest` | 13 | 13 | 0 | 0 |
| `:app:testDebugUnitTest` | `RecurringGeneratorTest` | 10 | 10 | 0 | 0 |
| `:app:testDebugUnitTest` | **`FocusAttributionTest`** *(V2-ME 新增)* | 10 | 10 | 0 | 0 |
| `:app:testDebugUnitTest` | **`PlanDateTest`** *(V2-CAL 新增)* | 12 | 12 | 0 | 0 |
| `:app:connectedDebugAndroidTest` | `AppNavigationTest` | 4 | 4 | 0 | 0 |
| `:app:connectedDebugAndroidTest` | `FocusAndPlanSmokeTest` | 2 | 2 | 0 | 0 |
| `:app:connectedDebugAndroidTest` | `IdeaCaptureFlowTest` | 1 | 1 | 0 | 0 |
| `:app:connectedDebugAndroidTest` | `TaskCreationFlowTest` | 1 | 1 | 0 | 0 |
| `:app:connectedDebugAndroidTest` | `RepositoryIdeasTest` | 8 | 8 | 0 | 0 |
| `:app:connectedDebugAndroidTest` | `RepositoryTasksTest` | 8 | 8 | 0 | 0 |
| `:app:connectedDebugAndroidTest` | `RepositoryTimeBlocksTest` | 7 | 7 | 0 | 0 |
| `:app:connectedDebugAndroidTest` | `RepositoryFocusTest` | 14 | 14 | 0 | 0 |
| `:app:connectedDebugAndroidTest` | `RepositoryDependencyTest` | 9 | 9 | 0 | 0 |
| `:app:connectedDebugAndroidTest` | `RepositoryWorkPolicyTest` | 5 | 5 | 0 | 0 |
| `:app:connectedDebugAndroidTest` | `RepositoryRecurringTest` | 7 | 7 | 0 | 0 |
| `:app:connectedDebugAndroidTest` | `RepositoryScheduleDraftTest` | 6 | 6 | 0 | 0 |
| `:app:connectedDebugAndroidTest` | **`RepositoryV2MeRecurringTest`** *(V2-ME 新增；本轮重命名 + 澄清 `recurringRuleId` / `PlanItem.locked` 相关用例)* | 15 | 15 | 0 | 0 |
| `:app:connectedDebugAndroidTest` | **`RepositoryApplyEditedDraftTest`** *(V2-CAL-REVIEW-FIX 新增)* | 8 | 8 | 0 | 0 |
| `:app:connectedDebugAndroidTest` | `RoomMigrationV2ToV3Test` | 1 | 1 | 0 | 0 |
| `:app:connectedDebugAndroidTest` | **`RoomMigrationV3ToV4Test`** *(V2-ME 新增)* | 2 | 2 | 0 | 0 |
| `:app:connectedDebugAndroidTest` | **`PlanMeReviewSmokeTest`** *(V2-CAL+V2-ME 更新；新增 Focus Review 弹窗冒烟)* | 10 | 10 | 0 | 0 |
| **合计** | | **168** | **168** | **0** | **0** |

零 `@Ignore`、零跳过。V2-TEST-FINAL（2026-10-16）跑完整两套件后 1 条之前失败的 Focus Review 弹窗冒烟 `me_page_focus_review_dialog_exposes_day_week_toggle_and_navigation_controls` 也已修复并通过；当前 168 条用例全部绿色，全部覆盖率与一次性 `FocusReviewDialog` 崩溃的处置记录见本地工件 `outputs/v2-test-final.md` 与 `outputs/v2-focus-review-crash-fix.md`（`outputs/` 目录被 `.gitignore` 排除，不纳入版本控制，仅供本地留存）。V1 的 60 条老用例已包含在 `HEAD` 上 V2 报告的 110 条基线里（38 JVM + 72 设备）；本轮在 V2-CAL / V2-CAL-REVIEW-FIX / V2-ME / V2-DRAFT-IDEMPOTENCY-FIX / V2-FOCUS-REVIEW-CRASH-FIX 上净增 58 条用例（22 JVM + 36 设备端），由 110 → 168（60 JVM + 108 设备）。

JVM 套件 XML 根属性：

```
DateUtilTest                  tests=8  fail=0 err=0 skip=0
DurationFormatterTest         tests=4  fail=0 err=0 skip=0
ManifestPermissionsTest       tests=3  fail=0 err=0 skip=0
PlannerEngineTest             tests=13 fail=0 err=0 skip=0
RecurringGeneratorTest        tests=10 fail=0 err=0 skip=0
FocusAttributionTest          tests=10 fail=0 err=0 skip=0  ← NEW
PlanDateTest                  tests=12 fail=0 err=0 skip=0  ← NEW
JVM TOTAL                     tests=60 fail=0 err=0 skip=0
```

设备端套件根属性（V2-TEST-FINAL，2026-10-16）：

```
tests="108" failures="0" errors="0" skipped="0" time="91.653s"
```

V2-TEST-REGRESSION run（修复前）记录的
`tests="108" failures="1" errors="0" skipped="0" time="87.938s"` 与上述
最终值差异即为 Focus Review 冒烟用例由 `ArrayIndexOutOfBoundsException`
失败转向通过。

（实际时间在 `outputs/instrumented-TEST-xml.xml` 中以毫秒为单位记录；
本仓库不转发过去 V1 报告的 170s / 2m43s 这种固定时长数字，因为每次
跑都会受 AVD 启动、ADB socket / Compose 仪表化抖动影响，单次值不是
断言。）

> 1 条失败即新加的 Focus Review 弹窗冒烟；它直接复现了生产代码的
> `FocusReviewDialog.kt` 在空数据初始组合时的 `SlotTable.length=0;
> index=-5` Compose 运行时崩溃（`return@Column` 早返回导致组边界对
> 不齐）。完整的崩溃栈与最小修复路径见
> 本地工件 `outputs/v2-test-regression.md`（`outputs/` 目录被 `.gitignore` 排除，不纳入版本控制，仅供本地留存）的 "Production
> defect" 一节；该测试按 brief 的"不通过任何手段绕过真实缺陷"原则原样
> 保留。

## 本轮新增覆盖一览

### V2-CAL：`PlanDateTest`（JVM，12 用例）

- `startOfWeek` 对一周 7 天的输入都返回 Monday。
- `weekRange` 给出从 Monday 起的 7 天半开区间。
- `monthGrid` 永远 42 个格子、首格 Monday、末格 = 首格 + 41 天。
- 闰年 (2024) 与平年 (2025) 的二月分别含 / 不含 29 日。
- 跨月跨年边界（January 2026）— 前导单元格来自上一年 12 月，末尾
  单元格进入次年 2 月。
- `isSameMonth`、`toLocalDate` ↔ `toStartOfDayMs`、`dayKey` ↔
  `parseDayKey`、`shiftDays/Weeks/Months`、`toEndOfDayMs == startOfNextDay`
  全部断言。

### V2-ME：`FocusAttributionTest`（JVM，10 用例）

- V4 单段在单日内 → 1 天，整段时长。
- V4 段跨午夜 → 跨 2 个本地日，每段 ms 等于 `[startMs, endMs)` 在该日
  的切片长度。
- V4 段 `endedAt = null` 时被 `nowMs` 封顶。
- V4 段混合 active + paused → 只有 active 段被计入，paused 段被排
  除。
- V3 回退：completed 会话用 `endedAt` 锚定 → 当日计入完整时长。
- V3 回退：cancelled 会话 → 全部排除。
- V3 回退：active 会话锚定 `now − totalActive` → 当日计入。
- `actualMsByTask` 按 taskId 分桶有效 ms。
- 跨日段剪裁到询问窗口 `[start, end)` 内部。

### V2-ME：`RepositoryFocusTest` 新增 6 条用例

`FocusSessionSegmentDao` 在每次生命周期转换（`startFocus` /
`pauseFocus` / `resumeFocus` / `endFocus` / `cancelFocus`）上都被
写入正确的段：
- `startFocus_writes_one_open_active_segment`
- `pauseFocus_closes_active_segment_and_opens_paused_segment`
- `resumeFocus_closes_paused_segment_and_opens_new_active_segment`
- `endFocus_closes_the_open_active_segment_and_writes_no_paused_segment`
- `cancelFocus_closes_any_open_segment_so_no_row_carries_endedAt_null`
- `segments_are_attributed_per_session_and_do_not_leak_between_sessions`

### V2-ME：`RepositoryV2MeRecurringTest`（设备端，15 用例）

新覆盖：
- `updateRecurringRule` 改写规则行并返回更新后的实体；未知 id 返回
  null；负 priority 钳到 1；负 estimate 钳到 0；horizon ≤ 0 钳到 1。
- `updateRecurringRule(applyToFutureOpenInstances = true)`：未来已开
  任务的标题/估时同步；过去/已完成/被覆盖/已取消实例不被改写。
- `synchronizeFutureOpenInstances`：把规则切换成 weekday 后，Saturday /
  Sunday 的未来任务被归档；其他天保留。
- `archiveRecurringRule`：未来开放任务被归档（observeActiveTasks 不再
  包含它们），今日 / 过去 / 完成 / 被覆盖实例保留；同一调用重复一次
  是 id 幂等。
- `setInstanceOverride` 单实例标题/估时写入并回写到 generated 任务
  行；`cancelled = true` 让 generator 跳过那日。
- `(ruleId, dayKey)` 幂等性：在两次 `materialiseRecurringInstances` 之
  后每个 (rule, day) pair 仍然只出现一次。
- `materialise` 不写入 `time_blocks`（独立的时间块分配是排期的工作）。

### V2-CAL-REVIEW-FIX：`RepositoryApplyEditedDraftTest`（设备端，8 用例）

对 `applyEditedScheduleDraft` 端的 5 个验证门：

1. **空 proposal 守卫** → `InvalidProposal("所有建议都已被移除")`，无 DB
   写。
2. **每行范围** → `endMs > startMs`，否则 `InvalidProposal`。
3. **每行窗口** → `[startMs, endMs)` 必须落在半开
   `[windowStartDay, endOfDayMs(windowEndDay)]` 之间，否则
   `InvalidProposal`。
4. **行间重叠** → 排序后若 `cur.startMs < prev.endMs`，否则
   `InvalidProposal`。
5. **现存块冲突** → `listInWindow(ws, we)` 取回所有 fixed + non-fixed
   块（不是只 fixed），命中重叠则 `Conflict(fixedBlockTitle)`，不写新
   块。
- 指纹改写后 `EDITED|<original.fp>|<sorted rows>`，apply 一次后入库
  一条带 `appliedAt` 的 schedule_draft 行；相同的 edited 请求在
  `revertScheduleDraft` 之后再 apply 触发 `NoOp`，不重复写块。
- `buildScheduleDraft` 不持久化任何 draft 行（仍在内存里），只有
  `applyEditedScheduleDraft` 走完后才入库并标 `appliedAt != null`。

### V2-CAL：`PlanMeReviewSmokeTest`（设备端，从 5 → 10 条）

针对屏幕布局的回归烟测：

- 计划页容量卡 + day-view 上 `生成排期草案` + `添加时间块` 可见。
- 计划页 view-tabs (`日 / 周 / 月`) 与 nav 控件 (`今天 / 上一段 / 下一段`)
  通过 `contentDescription` 可定位。
- 切到月视图后，month-grid 的 `plan-month-grid` testTag 仍然存在。
- 编辑弹窗打开时 `排期草案` 文本不增量（预览阶段不写数据库）。
- 「我的」页在竖向滚动下能逐项找到「每周工作规则」「重复规则」「专
  注回顾」三个入口——这是 V2-ME 修复 Me 页不可滚动到底部的固定回归
  用例。
- 「我的」页不再显示「只读日历忙碌时间」开关字符串（V2-ME 已移除）。
- 点击「查看当前能力」打开对话框，确认列明
  「Android 系统日历集成（V2-ME 已移除）」。

### V2-ME：`RoomMigrationV3ToV4Test`（设备端，2 用例）

`MIGRATION_3_4` 是**纯添加**的，没有 fallback 或字段重写，断言如下：

- `migration_v3_to_v4_retains_every_v3_row_and_adds_focus_session_segments_table`
  1. `FrameworkSQLiteOpenHelperFactory` 用 *V1 实体 byte-for-byte*
     的 CREATE 语句手搓一个 v2 文件 fixture（包括 `relations`、`time_blocks`
     五列原版）。
  2. 用 `addMigrations(MIGRATION_2_3)` 升级到 v3 再关库。
  3. 重新 `addMigrations(MIGRATION_2_3, MIGRATION_3_4)` 打开到 v4，并
     断言：原 ideas / tasks / relations / time_blocks / focus_sessions 行
     全部存在；`focus_session_segments` 表已建且空白；插入一段 active
     段后 `all()`、`listInWindow` 都能查回相同 row。
- `migration_v3_to_v4_does_not_drop_or_rewrite_focus_or_task_rows` 用一段
  `endedAt = null` 的 live 段验证 MIGRATION_3_4 没有改写段字段。

这里 `RoomMigrationV2ToV3Test` 也做了小改：`addMigrations(MIGRATION_2_3)`
→ `addMigrations(MIGRATION_2_3, MIGRATION_3_4)`，避免在 Room 期望
`version=4` 时落单 `v3 → v4` 找不到路径——见 V2-ME 数据库升 v4 的直
接副作用。

### `ManifestPermissionsTest`（V2-ME 的关键校验）

`manifest_declares_READ_CALENDAR_so_users_can_opt_in` 被翻转成
`merged_manifest_does_not_declare_READ_CALENDAR`，断言这一条权限也
不再出现。另新增 `manifest_does_not_declare_INTERNET_or_STORAGE_or_LOCATION_permissions`
统一守卫其它所有离线/无存储/无定位约束——任何漂移都会立刻 CI 红。

## V2-ME 涉及的 test-fakes / RepoFactory 修补

为了让 V2 测试在新构造函数签名上编译通过，test-child 改了
`app/src/androidTest/java/com/yinian/android/data/repo/` 下两份共享桩：

- `TestFakes.kt`：新增 `FakeFocusSessionSegmentDao`，镜像生产
  `FocusSessionSegmentDao`：`upsert`、`close`、`listForSession`、`all`、
  `listInWindow`。
- `TestFakes.kt`：移除对已删除
  `com.yinian.android.calendar.CalendarBusyReader` 的 import。
- `RepoFactory.kt`：`makeRepository` 不再传入 `calendarBusyReader`；
  新增 `focusSegmentDao = FakeFocusSessionSegmentDao()`。
- `RepositoryIdeasTest.kt`：其私有 `makeRepoWithFilesDir` 同等替换，
  构造一个真实 `AudioRecorder(context)` 不再经过 `CalendarBusyReader`。

`@Database` 在 v4 上注册了 `MIGRATION_3_4`；构造 builder 时不调用任
何 `fallbackToDestructiveMigration*` 路径，迁移是显式的。

## 测试报告来源 / 一次性不会漂移的事实

`outputs/instrumented-TEST-xml.xml` 的根属性是本仓库生成的
`androidx.test.runner.AndroidJUnitRunner` 输出，写明 `failures="0"`
`errors="0" sk_pped="0"`。`outputs/jvm-TEST-xml/TEST-*.xml` 7 份同
样写在 `outputs/` 下。

`PlanMeReviewSmokeTest.me_page_*` 的 `performScrollTo` 在设备 IME 与底
部导航重叠的极端布局上可能超时——V2-ME 把外层 Column 改成
`verticalScroll(rememberScrollState())` + 上下 inset padding +
140dp 底部 Spacer 已让这组测试稳定 9/9 通过。

## 已知 / 故意不做

- **真实 Android 系统日历授权路径**：已移除（`READ_CALENDAR` / `WRITE_CALENDAR` /
  `CalendarBusyReader` 三件套都不存在），没有 device-side 的授权流可以测。
- **本机 ASR / 云端 LLM / 账号同步**：V1/V2 都没有接入，
  `ManifestPermissionsTest` 用 manifest 层面守卫；UI 层由
  `me_page_capabilities_dialog_lists_the_calendar_integration_as_removed`
  + V1 的能力对话框列表守住。
- **`PlannerEngine` 的两条 V2 修复**（同任务重复 placed /
  placed+unscheduled 重复）保留在 `PlannerEngineTest.kt` 原位置，未做
  任何改动继续作为固定回归证据。
- **跨午夜会话切分在非 UTC 机器上**：生产代码
  `FocusAttribution.splitAcrossDays` 内部硬编码 `ZoneId.systemDefault()`；
  在本仓库的测试用例全部假设 `TZ=UTC`，任何在非 UTC 机器上跑测试
  的人需要在 shell 里 `export TZ=UTC`。这一步是文档约定，不是
  production bug，因为我们没有"修正生产代码"的所有权。

- **`FocusReviewDialog` 空数据初始组合崩溃** ✅ 已修复（V2-FOCUS-REVIEW-CRASH-FIX）。原
  `AlertDialog.text` lambda 里的
  ```kotlin
  if (perDay.isEmpty() && totalScheduledMs == 0L) {
      Text(emptyStateHint(mode), …)
      return@Column      // 非局部返回 → Compose 组边界不齐
  }
  ```
  会让 Compose 编译出的 endGroup / endRoot 提前收尾，导致
  `SlotTable.length=0; index=-5` 数组越界崩溃（设备端冒烟
  `PlanMeReviewSmokeTest.me_page_focus_review_dialog_exposes_day_week_toggle_and_navigation_controls`
  修复前在每次 `connectedDebugAndroidTest` 都稳定复现）。修复把早返回
  替换成 `else` 分支，使两条分支都在同一个 `Column` 组下成对收尾 —
  完整 diff 与编译证据见
  本地工件 `outputs/v2-focus-review-crash-fix.md`（`outputs/` 目录被 `.gitignore` 排除，不纳入版本控制，仅供本地留存）。
  V2-TEST-FINAL run（2026-10-16）该用例与 168 条整体用例都通过，回归
  证据见本地工件 `outputs/v2-test-final.md`（`outputs/` 目录被 `.gitignore` 排除，不纳入版本控制，仅供本地留存）。

## 一次性事件：本次 run 未观察到

本机本次一次性 run 中 95 条设备端用例在 API 34 模拟器
`Pixel_3a_API_34_extension_level_7_arm64-v8a`（已用
`adb -s emulator-5554` 显式定向）上一次跑完。历史 V1 报告曾记录过
Compose instrumentation 插桩层抖动造成的不稳定；本次没有触发。如
未来环境出现该现象，可参考 V1 处理路径：保留断言、对单类单独跑一次
以分离抖动、对照 `I TestRunner: finished: ...` 行确认通过。

## 仍在生效的硬件层提示

- AVD 推荐 `Pixel_3a_API_34_extension_level_7_arm64-v8a`。
- 真机并存时必须显式 `ANDROID_SERIAL=emulator-5554`（或在
  `connectedDebugAndroidTest` 上加
  `-Pandroid.testInstrumentationRunnerArguments.serial=emulator-5554`），
  否则 Gradle 会把用例同时下发造成重复运行。本仓库在你看到这行的时
  候已经存在 `127.0.0.1:5555` Samsung 真机。
- JVM 测试一律在 `TZ=UTC` 下运行；这一约束反映在上面"已知 / 故意
  不做"的最后一条。
