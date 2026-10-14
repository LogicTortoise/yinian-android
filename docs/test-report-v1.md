# V1 自动化测试报告

本报告汇总 Yinian Android V1 自动化测试的最终结果，覆盖仓库当前 V1
提交（`main` 分支）的 JVM 单元测试与设备端集成测试。

测试证据已经过二次回归，全部套件零 `@Ignore`、零失败、零错误。

## 环境

- JDK 17（Homebrew openjdk@17；详见项目自动记忆）。
- Android SDK Platform 34、Build-Tools 34.0.0、Platform-Tools。
- AVD：`Pixel_3a_API_34_extension_level_7_arm64-v8a`（API 34，extension
  level 7）。
- ADB 序列号：`emulator-5554`。

## 套件命令（一次性按顺序执行）

```bash
# JVM 单元测试
./gradlew :app:testDebugUnitTest --rerun-tasks

# 设备端集成 + Compose UI 烟测
./gradlew :app:connectedDebugAndroidTest
```

`testDebugUnitTest` 配套会先构建依赖它的 debug + test APK，是后续
`connectedDebugAndroidTest` 的前置。两个套件都要在本仓库当前 `main`
分支工作树上跑过才算验收通过（本次回归使用的就是该工作树，并非从
零重新 clone）。

## 最终结果

| 套件 | 测试类 | 测试数 | 通过 | 失败 | 跳过 |
|---|---|---:|---:|---:|---:|
| `:app:testDebugUnitTest` | `DateUtilTest`（含 `DateUtil` 8 例 + `DurationFormatter` 4 例） | 12 | 12 | 0 | 0 |
| `:app:connectedDebugAndroidTest` | `RepositoryIdeasTest` | 8 | 8 | 0 | 0 |
| `:app:connectedDebugAndroidTest` | `RepositoryTasksTest` | 8 | 8 | 0 | 0 |
| `:app:connectedDebugAndroidTest` | `RepositoryTimeBlocksTest` | 7 | 7 | 0 | 0 |
| `:app:connectedDebugAndroidTest` | `RepositoryFocusTest` | 8 | 8 | 0 | 0 |
| `:app:connectedDebugAndroidTest` | `RepositoryDependencyTest` | 9 | 9 | 0 | 0 |
| `:app:connectedDebugAndroidTest` | `AppNavigationTest`（底部导航 + 四页可达性，4 个用例） | 4 | 4 | 0 | 0 |
| `:app:connectedDebugAndroidTest` | `FocusAndPlanSmokeTest`（计划页周条 + 现在页专注入口） | 2 | 2 | 0 | 0 |
| `:app:connectedDebugAndroidTest` | `IdeaCaptureFlowTest` | 1 | 1 | 0 | 0 |
| `:app:connectedDebugAndroidTest` | `TaskCreationFlowTest` | 1 | 1 | 0 | 0 |
| **合计** | | **60** | **60** | **0** | **0** |

> 仓储测试通过 `RepoFactory` + `TestFakes` 的真 `ApplicationProvider`
> Context 构造仓储，调用 `YinianRepository` 的真实业务命令；UI 测试
> 使用真 `MainActivity` + Compose 语义节点。

## 已修复的回归

`RepositoryTimeBlocksTest` 之前因为 `addManualTimeBlock` 在 fixed block
上的重叠判据与生产实现不一致而以 `@Ignore` 跳过。生产层把
`TimeBlockDao.getBetween` 切换到半开区间 `startAt < :end AND endAt > :start`
之后，仓储测试同步：

- 解除 `addManualTimeBlock_rejects_an_overlap_on_a_fixed_block_and_leaves_it_intact`
  的 `@Ignore`。
- 新增 `addManualTimeBlock_rejects_an_overlap_when_proposed_window_starts_before_the_fixed_block`
  用同形状窗口锁定原回归。
- 新增 `addManualTimeBlock_allows_a_boundary_adjacent_block_under_half_open_intervals`
  验证半开区间下边界相邻块被允许共存。

最终重测 7/7 通过，套件零跳过。

## 一次性事件：SnapshotStateObserver 跨线程告警

早期重测里，`IdeaCaptureFlowTest.text_idea_capture_surfaces_in_Ideas_screen`
触发过一次 `SnapshotStateObserver` 的 `Detected multithreaded access`
告警（堆栈落到 `arch_disk_io_*`，Compose 插桩层面的多线程访问，非业务
缺陷）。UI 测试已经加了重试逻辑：

- 用 `composeRule.waitForIdle()` 在节点读取之间让位。
- 重新拉取 semantics 节点最多 5 秒。
- `MainActivity` 把一次状态订阅从 `collectAsState` 切换为
  `collectAsStateWithLifecycle`，避免 stop 状态下还在写 snapshot。

最终 48/48 设备端测试中没有再次复现。该告警属于 Compose UI 测试的
已知插桩层面抖动，不应解读为产品 bug。

## 测试覆盖与有意限制

- 仓储测试用 fake DAO 替换 Room 实现，**不是**集成 Room 数据库。验证
  的是 `YinianRepository` 的业务命令、依赖校验、状态机分支和
  `PrefsRepository` / `AudioRecorder` 的真实调用，而不是 SQL 与迁移
  路径。
- `AudioRecorder.start/stop` 在 instrumented 里只断言文件大小 > 0，不
  覆盖真实音频数据；删除录音文件时仓储会真正从 `filesDir` 删除。
- 没有 instrumented 用例覆盖权限被拒的录音路径——这条分支由人工验证，
  因为 `MediaRecorder` 构造和 instrumented 权限撤销存在时序竞争。
- Compose 烟测依赖首次启动时由 `SeedInitializer` 写入的示例数据。
  失败重试时不会清理 app 数据，靠 UUID 标签避让旧行。
- 没有任何用例覆盖 `AGENTS.md` 里被刻意禁用的能力（云端 LLM、ASR、
  TTS、账号同步等）。

## 仍生效的硬件层提示

- AVD 推荐 `Pixel_3a_API_34_extension_level_7_arm64-v8a`。
- 真机并存时要在命令行用 `-Pandroid.testInstrumentationRunnerArguments.serial=emulator-5554`
  或先 `adb -s <other> disconnect`，避免 Gradle 把用例同时下发造成
  重复运行。
