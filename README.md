# 一念 · Android

一念的原生 Android 客户端。本仓库当前阶段产出物：

- 干净的 Gradle 工程骨架（Kotlin DSL，Gradle Wrapper 已生成）。
- 与 `产品与技术方案.txt`、`UI设计规范.txt` 和 v4 视觉一致的四个底部入口。
- 本地优先：Room + DataStore；状态、依赖、时间和专注数据全部本地。
- 主动语音录入会真实落盘到应用私有目录，并在没有 ASR 服务时如实标注「转写未配置」。

## V2 范围（当前 main 分支）

在 V1 的本地 Idea、Task、手动计划与专注数据上落地产品方案阶段 C「个人规划」：

1. **时间规则与容量**：每周每天单独设置可用工作时间、休息缓冲、当日上限；计划页显示周可用分钟、已安排分钟、剩余容量与超载原因。
2. **重复事项**：日 / 工作日 / 每周规则，单次实例可单独覆盖或取消；归档规则只清未来未完成任务。
3. **可行性与排期**：确定性本地算法按优先级 / 截止压力 / 依赖就绪生成候选；不可行时给出稳定原因，不覆盖固定块。
4. **只读日历忙碌时间**：用户在「我的」页主动开启后方请求 `READ_CALENDAR`；无权限 / 无日历 / 关闭集成都可恢复，本地排期不依赖日历服务。
5. **排期草案、解释与应用**：计划页可生成 / 刷新草案；预览不写计划；只有用户应用后才保存；重复应用同一草案幂等；冲突时保持原计划完整。
6. **专注与计划回顾**：以真实的 `FocusSession` 为数据，汇总每日 / 每周计划与实际投入；不读取应用使用情况，不贴分心标签。
7. **Room v2 → v3 迁移**：每次发布都附带显式 SQL 迁移，不再使用 `fallbackToDestructiveMigration()`。

详见 [`docs/task-plan-v2.md`](docs/task-plan-v2.md) 与最终回归证据 [`docs/test-report-v2.md`](docs/test-report-v2.md)。V1 的范围与测试历史保留在 [`docs/task-plan-v1.md`](docs/task-plan-v1.md) 与 [`docs/test-report-v1.md`](docs/test-report-v1.md)。

## 范围

参见 `docs/task-plan-v1.md` 与 `docs/task-plan-v2.md`。

V1 包含：

1. 现在 / 闪念 / 计划 / 我的 四页 + 共享底部导航与语音胶囊。
2. Idea 与 Task 的本地增删改查；Idea 可显式转 Task 并保留来源。
3. 现在页直接新建任务：标题 / 说明 / 优先级 / 估时 / 可选截止日期；Idea 转 Task 自动保留 `derived_from` 关系。
4. 任务编辑器：可编辑标题、说明、优先级、估时、截止日期；显式完成 / 撤销完成；显式归档 / 删除。
5. 简单 DAG 依赖：自依赖、循环依赖被拒绝；编辑器内可查看当前前置依赖并增删。
6. 现在页给出有理由的本地推荐（确定性启发式，不联网）。
7. 专注计时：开始 / 暂停 / 继续 / 结束 / 放弃；计时结束不自动完成。
8. 计划页：周日期切换、单日时间块、手动安排、固定块保护。
9. 我的页：工作时间、默认专注时长、主题、自动化开关、ASR 能力状态（明确「未配置」）。
10. 启动时插入明确标记的示例数据；空态、错误态、权限态都给到恢复路径。

**本次不交付**：云端 LLM、ASR、TTS、账号同步、MCP/API、可靠提醒、使用统计、商店发布。所有这些能力在「我的 → 查看当前能力」里有明确列出，并保持未配置状态。

## 环境要求

- JDK 17（Android Gradle Plugin 8.2 要求）。本机已自带 OpenJDK 17 即可。
- Android SDK Platform 34、Build-Tools 34.x、Platform-Tools。
- Gradle Wrapper 已提交，无需全局 Gradle；`gradlew` 会按 `gradle/wrapper/gradle-wrapper.properties` 自动拉取 Gradle 8.5。

### macOS 一次性设置

```bash
brew install --cask zulu@17      # 或其他 JDK 17 提供方
echo "export JAVA_HOME=$(/usr/libexec/java_home -v 17)" >> ~/.zshrc
brew install --cask android-commandlinetools
echo "export ANDROID_HOME=$HOME/Library/Android/sdk" >> ~/.zshrc
echo "export PATH=$PATH:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools" >> ~/.zshrc
yes | sdkmanager --licenses
sdkmanager "platforms;android-34" "build-tools;34.0.0" "platform-tools"
```

然后把 `local.properties.example` 复制为 `local.properties`，按本机 SDK 路径调整。

## 构建与运行

```bash
./gradlew :app:assembleDebug
./gradlew :app:installDebug   # 需要连接真机或模拟器
```

输出：`app/build/outputs/apk/debug/app-debug.apk`。

## 自动化测试

本项目已经把仓储层测试迁到 Android instrumented 路径（避免 Robolectric 下载 Android runtime jar）。本地跑两个 Gradle 任务分别覆盖 JVM 单元与设备端集成：

```bash
# 纯 JVM 单元测试（android-free 工具类）
./gradlew :app:testDebugUnitTest --rerun-tasks
# 设备端集成 + Compose UI 烟测（需要 API 34 模拟器或真机）
./gradlew :app:connectedDebugAndroidTest
```

API 34 AVD 推荐使用 `Pixel_3a_API_34_extension_level_7_arm64-v8a`（已在本机验证），ADB 序列号 `emulator-5554`。如果只连接真机，请确保只有一个设备暴露给 ADB，否则 Gradle 会把用例同时下发，可能被 `:app:connectedDebugAndroidTest` 报告为重复运行。

最终的测试结果、覆盖范围与一次性 `SnapshotStateObserver` 事件纪要见 [`docs/test-report-v1.md`](docs/test-report-v1.md)。V2 自动化测试证据与覆盖范围见 [`docs/test-report-v2.md`](docs/test-report-v2.md)。当前套件零 `@Ignore`、零跳过、零失败，合计 38 条 JVM 单元 + 72 条设备端集成 = 110 条；V2 实施期间由可控回归暴露的 2 个 `PlannerEngine` 生产 bug 已修复并通过原始断言回归（详见 V2 测试报告）。

## 权限

- V1 录音：`android.permission.RECORD_AUDIO`，仅在用户主动点录音胶囊时按需触发。拒绝时录入弹层显示「需要麦克风权限才能录音」并提示去系统设置授权；失败时录音片段会从磁盘上删除，弹层展示「录音失败，原文件已删除」。
- V2 日历：清单里**仅**声明 `android.permission.READ_CALENDAR`，且**不**声明 `WRITE_CALENDAR`。`READ_CALENDAR` 在用户主动开启「我的 → 只读日历忙碌时间」开关后由系统弹窗申请；拒绝时本地排期照常工作。V2 测试套件里有专门的 JVM 用例在合并后的清单里寻找这两条事实，避免漂移。

> V1/V2 不申请存储、定位、通知、互联网、前台服务、录音之外的其他运行时权限。

## 架构

```
app/src/main/java/com/yinian/android
├── YinianApp.kt              # Application；构造容器，触发首次种子数据
├── MainActivity.kt           # Compose 入口；按主题设置根
├── audio/AudioRecorder.kt    # MediaRecorder 包装；保证失败时不留垃圾文件
├── calendar/CalendarBusyReader.kt  # V2 只读 CalendarContract.Instances 适配器
├── data/
│   ├── AppContainer.kt       # 简单 DI
│   ├── SeedInitializer.kt    # 首次启动写入示例数据 + 默认每周工作时间
│   ├── db/AppDatabase.kt     # Room v3 + MIGRATION_2_3
│   ├── dao/Daos.kt           # Flow + suspend 查询（V1 + V2 实体）
│   ├── entity/Entities.kt    # Idea / Task / Relation / TimeBlock / FocusSession + V2
│   │                         # WorkPolicy / RecurringRule / RecurringInstance / ScheduleDraft
│   ├── prefs/PrefsRepository # DataStore（主题/工作时段/默认专注/自动化/ASR/V2 日历集成开关）
│   └── repo/YinianRepository # 业务命令 + 启发式推荐 + V2 排期草案/应用 + 重复规则物化
├── planner/                  # V2 纯 Kotlin 确定性算法（Android-free，便于 JVM 单元测试）
│   ├── PlannerTypes.kt       # PlanItem / Placement / BusyInterval / DayWindow
│   ├── PlannerEngine.kt      # 评分 + 按 FreeInterval 贪心放置 + 跨天 carry-over
│   └── RecurringGenerator.kt # daily / weekday / weekly 实例生成
├── ui/
│   ├── theme/                # 颜色、字体、形状（v4 浅/深色）
│   ├── common/               # SoftCard / BottomNav / CaptureDock / GrooveRow / PillBadge
│   ├── nav/RootScaffold      # 顶层布局
│   ├── now/NowScreen         # 现在
│   ├── ideas/IdeasScreen     # 闪念
│   ├── plan/PlanScreen       # 计划（V2 增加「本周容量」卡 + 「生成排期草案」）
│   ├── me/MeScreen           # 我的（V2 增加「每周工作规则」/「重复规则」/只读日历/回顾）
│   ├── review/FocusReviewDialog  # V2 计划 vs 实际回顾
│   ├── tasks/TaskEditorDialog # 任务编辑器（新建 / 编辑 / 依赖）
│   └── capture/CaptureSheet  # 文本 + 录音
└── util/DateUtil.kt
```

## 设计遵循

- 颜色与表面层级见 `UI设计规范.txt`；`ui/theme/Color.kt` 直接对应规范里的色值。
- 卡片有底边、阴影、圆角；按压通过 Material 3 的 `clickable` 自然带 ripple。
- 浅色 / 深色都按规范实现，状态栏 / 导航栏透明并 inset 处理。
- 主要可点击控件提供 `contentDescription` 用于 TalkBack。

## 已知与故意未做

- 录音体验：当前显示录音时长和文件名；V1 暂无内置播放/波形。删除闪念条目会删除关联音频文件。
- 没有云端、没有自动排期、没有提醒通知。这些能力的明确禁用状态都写在「我的 → 查看当前能力」里。

## 反馈与限制

- 本仓库根的 `preview/`、`design/`、`产品与技术方案.txt`、`UI设计规范.txt`、`AGENTS.md` 保持原样，未做改动。
- 本次 debug 构建使用 JDK 17、Android SDK Platform 34 和 Build-Tools 34.0.0。若当前 shell 没有配置环境变量，可用本机安装路径临时设置：
  ```bash
  env JAVA_HOME=/path/to/jdk-17 \
    ANDROID_HOME=/path/to/android-sdk \
    ANDROID_SDK_ROOT=/path/to/android-sdk \
    PATH=/path/to/jdk-17/bin:$PATH \
    ./gradlew :app:assembleDebug --no-daemon --console=plain
  ```
  生成 `app/build/outputs/apk/debug/app-debug.apk`。也可以按前面的 macOS 设置步骤配置 JDK、Android SDK 与 `local.properties` 后直接运行 `./gradlew :app:assembleDebug`。
