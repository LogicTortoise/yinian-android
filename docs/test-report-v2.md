# V2 自动化测试报告

本报告汇总 Yinian Android V2 自动化测试的最终结果，覆盖仓库当前 V2
提交（`main` 分支）的 JVM 单元测试、设备端集成测试、迁移测试和权限
烟测。

V2 范围在实施过程中由测试套件暴露了 2 个生产规划器缺陷（同一任务被
多次排入 / 既出现在 `placements` 又出现在 `unscheduled`）。开发者子
代理在 `PlannerEngine.kt` 完成了最小修复（per-day 主循环顶部增加
`placedIds` 去重 + carry-over 段复用同一 `placedIds`），原断言保留为
回归证据，**已全部通过**。

## 环境

- JDK 17（Homebrew openjdk@17 17.0.19，路径
  `/opt/homebrew/Cellar/openjdk@17/17.0.19/libexec/openjdk.jdk/Contents/Home`）。
- Android SDK Platform 34、Build-Tools 34.0.0、Platform-Tools。
- AVD：`Pixel_3a_API_34_extension_level_7_arm64-v8a`（API 34 / Android 14
  / ABI `arm64-v8a`，extension level 7）。
- ADB 序列号：`emulator-5554`（**唯一**连接的设备）。

## 套件命令（一次性按顺序执行）

```bash
# JDK 17 + Android SDK 路径通过环境变量提供
export JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.19/libexec/openjdk.jdk/Contents/Home
export ANDROID_HOME=$HOME/Library/Android/sdk
export ANDROID_SDK_ROOT=$HOME/Library/Android/sdk
export PATH=$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$PATH

./gradlew :app:testDebugUnitTest --rerun-tasks --console=plain --no-daemon
./gradlew :app:connectedDebugAndroidTest --console=plain --no-daemon \
    -Pandroid.testInstrumentationRunnerArguments.serial=emulator-5554
```

`testDebugUnitTest` 必须先跑通：它会一并构建 debug + test APK，
是 `connectedDebugAndroidTest` 的前置。

## 最终结果

| 套件 | 类 | 测试数 | 通过 | 失败 | 跳过 |
|---|---|---:|---:|---:|---:|
| `:app:testDebugUnitTest` | V1 `DateUtilTest` | 8 | 8 | 0 | 0 |
| `:app:testDebugUnitTest` | V1 `DurationFormatterTest` | 4 | 4 | 0 | 0 |
| `:app:testDebugUnitTest` | V2 `ManifestPermissionsTest` | 3 | 3 | 0 | 0 |
| `:app:testDebugUnitTest` | V2 `PlannerEngineTest` | 13 | 13 | 0 | 0 |
| `:app:testDebugUnitTest` | V2 `RecurringGeneratorTest` | 10 | 10 | 0 | 0 |
| `:app:connectedDebugAndroidTest` | V1 `AppNavigationTest` | 4 | 4 | 0 | 0 |
| `:app:connectedDebugAndroidTest` | V1 `FocusAndPlanSmokeTest` | 2 | 2 | 0 | 0 |
| `:app:connectedDebugAndroidTest` | V1 `IdeaCaptureFlowTest` | 1 | 1 | 0 | 0 |
| `:app:connectedDebugAndroidTest` | V1 `TaskCreationFlowTest` | 1 | 1 | 0 | 0 |
| `:app:connectedDebugAndroidTest` | V1 `RepositoryIdeasTest` | 8 | 8 | 0 | 0 |
| `:app:connectedDebugAndroidTest` | V1 `RepositoryTasksTest` | 8 | 8 | 0 | 0 |
| `:app:connectedDebugAndroidTest` | V1 `RepositoryTimeBlocksTest` | 7 | 7 | 0 | 0 |
| `:app:connectedDebugAndroidTest` | V1 `RepositoryFocusTest` | 8 | 8 | 0 | 0 |
| `:app:connectedDebugAndroidTest` | V1 `RepositoryDependencyTest` | 9 | 9 | 0 | 0 |
| `:app:connectedDebugAndroidTest` | V2 `RepositoryWorkPolicyTest` | 5 | 5 | 0 | 0 |
| `:app:connectedDebugAndroidTest` | V2 `RepositoryRecurringTest` | 7 | 7 | 0 | 0 |
| `:app:connectedDebugAndroidTest` | V2 `RepositoryScheduleDraftTest` | 6 | 6 | 0 | 0 |
| `:app:connectedDebugAndroidTest` | V2 `RoomMigrationV2ToV3Test` | 1 | 1 | 0 | 0 |
| `:app:connectedDebugAndroidTest` | V2 `PlanMeReviewSmokeTest` | 5 | 5 | 0 | 0 |
| **合计** | | **110** | **110** | **0** | **0** |

零 `@Ignore`、零跳过、零失败。V1 的 60 条测试在 V2 生产代码下全部继
续通过。

JVM XML：

```
DateUtilTest               tests=8  fail=0 err=0 skip=0
DurationFormatterTest      tests=4  fail=0 err=0 skip=0
ManifestPermissionsTest    tests=3  fail=0 err=0 skip=0
PlannerEngineTest          tests=13 fail=0 err=0 skip=0
RecurringGeneratorTest     tests=10 fail=0 err=0 skip=0
TOTALS                     tests=38 fail=0 err=0 skip=0
```

设备端 XML（`TEST-Pixel_3a_API_34_...xml` 套件根属性）：

```
tests="72" failures="0" errors="0" skipped="0" time="170.806s"
```

## PlannerEngine 回归：已修复

V2 实施期间由保留的失败用例上报的两条 `PlannerEngine` 缺陷，现已在
生产代码修复并通过原始断言回归：

| # | 用例 | 修复前实际 | 修复后实际 |
|---|---|---|---|
| 1 | `PlannerEngineTest.plan_is_deterministic_for_identical_inputs`（`PlannerEngineTest.kt:73`） | `expected:<3> but was:<15>` 失败：同一 item 在多天被重复 placed | `first.placements.size == 3`、`unscheduled.size == 0`，两条 `plan()` 调用等价 |
| 2 | `PlannerEngineTest.plan_does_not_schedule_an_item_both_scheduled_and_unscheduled_after_carry_over`（`PlannerEngineTest.kt:261`） | 同一 item 既在 `placements` 又在 `unscheduled`（carry-over 在 day2 placed，但 day1 的 `EstimateTooLong` 未撤回） | carry-over 后只可能 placed 或 unscheduled 中一个；按 day1 = 30min / day2 = 9h 的策略放置到 day2 |

最小修复点（`app/src/main/java/com/yinian/android/planner/PlannerEngine.kt`）：

- per-day 主循环顶部 `if (item.item.id in placedIds) continue`；
- carry-over 段推进同一 `placedIds`：`placedIds += item.item.id` 后再
  `placedIt = true`；
- unscheduled 末尾仍调用 `.distinctBy { it.itemId }` 防止重复。

这两条用例的断言在提交版本中一字未改，作为固定回归证据保留下来。

## V2 新增覆盖一览

### 纯 JVM（Android-free `planner/` 包）

- `PlannerEngineTest` (13)：确定性 / 不与固定块 / 工作窗口与休息缓
  冲、日容量、依赖阻塞、优先级与截止压力排序、跨天 carry-over、估时
  过长与时长不受支持两种 unscheduled 原因、unscheduled 原因稳定、同
  任务既 placed 又 unscheduled、容量与已排时间一致性。
- `RecurringGeneratorTest` (10)：daily / weekday / weekly byDayMask /
  起始日 / 半开窗口边界 / 单实例覆盖 / 取消实例 / 幂等性 / 规则编辑
  diff 保留历史 / `byDayMask=0` 不产出。
- `ManifestPermissionsTest` (3)：合并后清单不存在 `WRITE_CALENDAR`、
  存在 `READ_CALENDAR`、不存在 `INTERNET`。

### 设备端集成

- `RepositoryWorkPolicyTest` (5)：`saveWorkPolicies` 替换语义、
  `bufferMinutes` + `dailyCapMinutes` 持久化、`computeWeekCapacity` 与
  启用工作日对应、`non-fixed` 计时 / `fixed` 不计、`disabled day` 覆
  写。
- `RepositoryRecurringTest` (7)：每日规则幂等、同
  `(ruleId, instanceDate)` 不重复、覆盖 / 取消、归档只清未来开放任
  务、weekday / weekly byDayMask、覆盖标题回写到生成出的任务行。
- `RepositoryScheduleDraftTest` (6)：构建草案不写任何 `TimeBlock`、
  显式 apply 才写、同 fingerprint 重 apply 是 NoOp、apply 时新 fixed
  冲突整个失败无部分写入、新 fixed 不被覆盖、可通过
  `revertScheduleDraft` 按 `draftId` 仅移除本次应用产生的块。
- `RoomMigrationV2ToV3Test` (1)：使用
  `FrameworkSQLiteOpenHelperFactory` 手工创建一个 v2 数据库（与 V1 实
  体一一对应），植入 ideas / tasks / relations / time_blocks /
  focus_sessions 真实样本行；再用生产 `MIGRATION_2_3` 打开，回到 V3
  后断言：
  - 每一个原 idea / task / relation / time-block / focus-session 字
    段值都保留；
  - 任务 `recurringRuleId` / `instanceDate`、`time_blocks`
    `recurringRuleId` / `instanceDate` / `draftId` 五个 V2 新列存在且
    默认 `NULL`；
  - 四个 V2 表 `work_policies` / `recurring_rules` /
    `recurring_instances` / `schedule_drafts` 通过写入 + 读回验证存
    在；
  - 所有 V2 DAO 接入正常。
  - 因为 `AppDatabase.exportSchema=false`，生产环境没有 Room 要求的
    schema JSON；测试完全用 SQL 自己堆出 v2 fixture 再让 Room 上
    `MIGRATION_2_3`，避免了"编译通过就算过"的伪验收。
- `PlanMeReviewSmokeTest` (5)：计划页显示"本周容量"卡 + "生成排期草
  案"按钮；草案对话框（"放弃"/"应用"按钮）打开后预览不立即写；
  「我的」页"每周工作规则"/"重复规则"入口可达；"本周专注回顾"入口
  可达；"只读日历忙碌时间"开关可达。

## V2 数据迁移测试说明（brief 要求）

V2 brief 要求"create a valid v2 SQLite fixture using SQL 或 explain the
precise blocker"。`AppDatabase.exportSchema=false` 把这条路径堵了 80%
——没有 schema JSON，`MigrationTestHelper.createDatabase(version = 2)`
不会生成可验证的源 schema。`RoomMigrationV2ToV3Test` 的解决方案：

1. 不依赖 `MigrationTestHelper`，直接用
   `androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory` 创
   建一份与 V1 实体对应的 v2 SQLite 文件；
2. 用 `db.execSQL(...)` 直接 INSERT 样本 idea / task / relation /
   block / focus session；
3. 关闭 helper 以释放文件；
4. 用 `Room.databaseBuilder(...).addMigrations(AppDatabase.MIGRATION_2_3).build()`
   重开同一文件，由 V3 的 `@Database` + production migration 完成
   `tasks` / `time_blocks` 五条 `ALTER TABLE ... ADD COLUMN` 与
   `work_policies` / `recurring_rules` / `recurring_instances` /
   `schedule_drafts` 四个 `CREATE TABLE`；
5. 通过 V3 DAO 读回并断言每一条 seed 数据 + 五个新列默认值 + 四个新表
   都已就位。

不是编译式验收；测试在 API 34 模拟器 `emulator-5554`（Android 14 /
`arm64-v8a`）上跑通。

## 迁移与回归证据（最终一次性 run）

- JVM：`./gradlew :app:testDebugUnitTest --rerun-tasks`
  - log：`outputs/v2-jvm-final.log`
  - HTML 汇总：`outputs/jvm-index.html`
  - per-test XML：`outputs/jvm-TEST-xml/TEST-*.xml`（5 份，含
    `TEST-com.yinian.android.planner.PlannerEngineTest.xml` 在内）。
- 设备端：`./gradlew :app:connectedDebugAndroidTest`
  - log：`outputs/v2-instrumented-final.log`
  - HTML 汇总：`outputs/instrumented-index.html`（72 tests / 0 failures
    / 100% / 2m43.53s）
  - 套件 XML：
    `outputs/instrumented-TEST-xml.xml` 根属性
    `tests="72" failures="0" errors="0" skipped="0" time="170.806s"`。
  - per-test logcat：
    `app/build/outputs/androidTest-results/connected/debug/Pixel_3a_API_34_extension_level_7_arm64-v8a(AVD) - 14/logcat-*.txt`
    共 72 份，按测试类计数——`RepositoryDependencyTest=9`、
    `RepositoryTasksTest=8`、`RepositoryIdeasTest=8`、
    `RepositoryFocusTest=8`、`RepositoryTimeBlocksTest=7`、
    `RepositoryRecurringTest=7`、`RepositoryScheduleDraftTest=6`、
    `PlanMeReviewSmokeTest=5`、`RepositoryWorkPolicyTest=5`、
    `AppNavigationTest=4`、`FocusAndPlanSmokeTest=2`、
    `TaskCreationFlowTest=1`、`IdeaCaptureFlowTest=1`、
    `RoomMigrationV2ToV3Test=1`，合计 72。
- `RoomMigrationV2ToV3Test` 通过证据：
  `app/build/outputs/androidTest-results/connected/debug/Pixel_3a_API_34_extension_level_7_arm64-v8a(AVD) - 14/logcat-com.yinian.android.data.RoomMigrationV2ToV3Test-migration_v2_to_v3_preserves_existing_rows_and_adds_v2_columns_and_tables.txt`，
  含 `I TestRunner: started: migration_v2_to_v3_...` 与
  `I TestRunner: finished: migration_v2_to_v3_...`。

## 已修复的 V2 测试自修正（不是产品 bug）

为了在 V2 的新生产 `YinianRepository` 构造器上编译，原仓库 V1 的
`TestFakes.kt` / `RepoFactory.kt` / `RepositoryIdeasTest.kt` 三处测试
桩需要把 `Fake*Dao` 集合扩展到 9 个 DAO 并新增一份 `CalendarBusyReader`
真实组件（API final 不可子类化）。三处补丁均在 `app/src/androidTest/**`
内：

- `TestFakes.kt`：加 `FakeWorkPolicyDao` / `FakeRecurringRuleDao` /
  `FakeRecurringInstanceDao` / `FakeScheduleDraftDao`，并扩展
  `FakeTaskDao.listByRecurringRule` / `getById` / `getAllSnapshot` /
  `getAllOpenSnapshot`、`FakeTimeBlockDao.listByRecurringRule` /
  `listByDraft` / `listInWindow` / `listByTask`。
- `RepoFactory.kt`：把新 DAO 全部用 fakes 接入 `YinianRepository`，
  真实 `CalendarBusyReader` 接收 `ApplicationProvider` 提供的 Context。
- `RepositoryIdeasTest.kt` 中持有真实 `audioRecorder` 的
  `makeRepoWithFilesDir` 同步补齐 4 个新 DAO + `CalendarBusyReader`。

这些不是产品 bug 修复，没有动 `app/src/main/**`。

## 覆盖范围与有意限制

- **仓储测试**仍使用 fake DAO（不真连 Room），验证的是
  `YinianRepository` 的业务命令 + 依赖校验 + 状态机分支 +
  `PrefsRepository` / `AudioRecorder` / `CalendarBusyReader` 的真实调
  用，以及 V2 增加的排期草案 / 重复规则 / 工作时间物化路径；不是
  SQL 与迁移路径。
- **真实的 Room 迁移路径**只覆盖 `RoomMigrationV2ToV3Test`：这条用
  例走完整的 SQL ALTER + CREATE，并通过 V3 DAO 读写一遍数据。
- **日历 `READ_CALENDAR` 真权限授予路径**没有覆盖：模拟器通常没日
  历账号，所以连带着 `readBusyIntervals` 在我们的设备上始终返回空。
  真日历集成需要真机 + 手动授权，由人工验证。
- **专注回顾的 Compose UI** 仅覆盖到对话框入口可达；不展开 review
  内部的 panel，因为 Me 页底部 `GroupCard` 居于 FAB 之下，未引入生
  产可滚动 Column 也无法保证可见性，故以 `assertExists()` 替代
  `assertIsDisplayed()`。
- **`PlannerEngine` 的两个生产 bug** 在本次 run 已修复并通过原始断
  言回归；不是已知 bug。
- **没有任何用例覆盖 `AGENTS.md` 里被刻意禁用的能力**（云端 LLM、
  ASR、TTS、账号同步等）。

## 一次性事件：本次 run 未观察到

本机本次一次性 run 中 `AppNavigationTest.all_four_bottom_tabs_are_reachable`
连同其他 71 条用例一起在一次 170s 的完整跑里通过。历史 V1 报告曾记
录过 Compose instrumentation 插桩层抖动造成的不稳定；本次没有触发。
如未来环境出现该现象，可参考 V1 处理路径：保留断言、对单类单独跑一
次以分离抖动、对照 `I TestRunner: finished: ...` 行确认通过。

## 仍在生效的硬件层提示

- AVD 推荐 `Pixel_3a_API_34_extension_level_7_arm64-v8a`。
- 真机并存时必须在命令行加
  `-Pandroid.testInstrumentationRunnerArguments.serial=emulator-5554`
  或先 `adb -s <other> disconnect`，否则 Gradle 会把用例同时下发造成
  重复运行。
