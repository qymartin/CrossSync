# CrossSync 版本记录

版本号格式：`功能版本.编译号`，例如 1.0.8 = 1.0 功能版本的第 8 次编译。
功能版本写在根目录的 `VERSION` 文件里，新增功能时升级（1.0 → 1.1）；编译号是 GitHub Actions 的运行次数，只增不减。

## v1.3

- 首个版本：Release `v1.3.17`；v1.2 的最终代码保存在分支 `baseline-v1.2`（= Release 1.2.16）
- 新增 **Report** 模块（`report/`）。底部导航变为 Fitness | Report | Sync。Fitness 和 Sync 的代码不变；Report 只通过 `ReportDataSource` 读取 Fitness 的缓存（`FitnessStore.snapshot()`），生成前调用一次 Fitness 的自动刷新（14 天）。

### 功能
- **周报 / 月报 / 年报**，顶部切换。每类只保存最新一份（`reports.json`），新的覆盖旧的。
  - 周报：每周日 15:00，覆盖上周日到周六，对比前 3 周。
  - 月报：每月 1 日 15:00，覆盖上个月，对比最近 3 个月；一周算在它的周六所在的月。
  - 年报：只靠按钮，用今年到目前为止 + 前 4 年的月均值（月均值 = 当月各周均值的平均）。
- **本地计算**：一周 = 周日到周六；周均值 = 当周有数据的日子求平均。指标：总消耗、体重、BMI（身高在本地算）、体脂、总睡眠、浅睡 / 深睡 / REM 占比。关键数字（各分组均值、与上一分组的变化、最高 / 最低的周或月、总睡眠低于 7 小时的个数）在本地算好再发送。本期没有数据时不调用 API。
- **发送内容**：只有上面的均值和关键数字、周期标签，以及系统提示里的年龄（出生年份在本地换算，每年 1/1 加一岁）、性别、族裔。不发每日数据、身高、出生年份、身份信息、旧报告。
- **模型**：每次先查 `GET /v1/models`，选列表里最新的 `claude-sonnet-*`；查不到用 `claude-sonnet-5-5`。不开联网搜索。用强制的 `write_report` 工具返回结构化结果（评级、变化方向、综合评价、主要发现、按主题分组的目标与行动）。写作要求全文在 `ClaudeClient.systemPrompt`。
- **失败处理**：没网时 WorkManager 等联网再运行，不算失败。可重试的错误（网络、429/5xx、回复不完整）自动重试 3 次：30 分钟、2 小时、6 小时；之后在报告上方显示失败卡片和「重试」按钮，并发通知。API 密钥被拒（401/403）时不重试，重新弹出设置表单。年报和「重试」按钮只试一次。
- **设置**：首次打开 Report 时弹出表单：身高、出生年份、性别、族裔、Anthropic API 密钥。用 Keystore 加密保存（`SecureStore("report")`），保存后不再显示。保存后申请 Health Connect 后台读取权限（`READ_HEALTH_DATA_IN_BACKGROUND`），让定时报告能在 App 关闭时刷新数据；拒绝时使用缓存数据。
- **定时**：两个精确闹钟（周、月）+ 每 12 小时的兜底检查；开机、App 更新、改时间或时区后重新设置。打开 App 时也会检查：到期而还没生成的报告会补生成（首次填完资料后，会立刻生成最近一期的周报和月报）。
- **通知**：只写「周报已生成」「周报生成失败」这类通用文字，不带数据；点通知打开 Report 页。

## v1.2

- 首个版本：Release `v1.2.14`；定稿版本：Release `v1.2.16`（分支 `baseline-v1.2`）；v1.1 的最终代码保存在分支 `baseline-v1.1`（= Release 1.1.13）
- 只改 Fitness 模块，Sync 不变。

### 改动
- **历史数据导入（一次性）**：Fitness 页在没有历史数据时显示「导入 7 月以前的历史数据」卡片，用系统文件选择器选 `crosssync-history.json`（由 Claude 从 Google Takeout 生成，不放进仓库），存进缓存。导入后卡片消失，不提供重新导入。
- **分界线 2026-07-01**：之前的日子只用导入的 Google Health 数据，之后只用 Health Connect。缓存中 7/1 以前的 Health Connect 数据会被丢弃。页面上标明两段数据的来源。
- **刷新范围**：手动刷新核对过去 6 个月；打开页面且数据超过 6 小时未更新时，自动核对过去 14 天；都不早于 7/1。若上次刷新更早，从上次刷新前一天开始，避免漏掉日子。超出范围的数据保留在缓存里。首次读取 Health Connect 时从 7/1 读起。
- **多来源取值**：同一天有多个 App 时，卡路里取当天总数最高的那个 App，睡眠取总时长最长的那个 App，不跨 App 相加。理由是同一时间只佩戴一台设备，其他来源只会是副本或兜底估算。体重和体脂仍取平均。
- **纵轴**：整数刻度（1、2、5、10、20、25、50…），最低不低于 0，数据大约占图高的一半；右轴与左轴网格对齐。
- 移除了 1.1.12 的「已读取」诊断行。
- 1.2 后续细节：每张图固定 5 个纵轴刻度；月视图改为过去 12 个月（含本月）；Sync 的「同步指定日期范围」按钮去掉「（强制覆盖）」字样（行为不变：仍会重写该范围）；同步记录的历史只显示最近 10 条。
- 导出（Sync 页）：导出 CrossSync 处理过的全部数据为一个 CSV，一行是某天的一项数据，列为 `date,type,value,unit,source,status,synced_at,error`。包含 Sync 的同步记录（`total_calories`，来源 `sync→health_connect`）和 Fitness 的全部每日数据（`calories`、`weight`、`body_fat`、`sleep`、`sleep_light`、`sleep_deep`、`sleep_rem`，来源 `google_health_import` 或 `health_connect`）。由 `export/` 层汇总：每个模块实现 `ExportProvider` 提供自己的行，模块之间不互相读取。以后新增数据类型只会多出新的 type，列不变。

### 导入文件的生成规则（`crosssync-history.json`）
- 只含 2026-07-01 以前的数据，日期按太平洋时间（含夏令时）计算。
- 卡路里：只用 Google Health 自己记录的每分钟数据，排除 Health Connect 副本；同一分钟有两条时取较高的；不足 20 小时的日子去掉；全天只有基础代谢估算（没戴手环）的日子去掉。
- 体重和体脂：排除 Health Connect 副本，取当天平均值。
- 睡眠：同一晚重叠的记录取最长的（时长相同时优先有分期的），同一天分开的几段（如午睡）相加；分期百分比 = 浅睡、深睡、REM 各自占三者之和的比例。

## v1.1

- 首个版本：Release `v1.1.11`
- 1.0 的同步功能原样保留，作为 **Sync** 模块；新增 **Fitness** 模块。底部导航 Fitness 在左（默认打开），Sync 在右；两个模块各自独立运行。

### Fitness 模块
- **数据来源**：只读 Health Connect（不直接连接 Garmin，也不写入任何数据）。读取权限：总卡路里、体重、体脂率、睡眠，以及历史数据（30 天以前）。
- **缓存**：手机上的 `fitness.json`。首次读取 2010 年至今全部数据；打开 Fitness 页时，若距上次刷新超过 6 小时则自动刷新，只重新读取过去 7 天（如果上次刷新更早，则从上次刷新前一天开始）；手动点「刷新」会重新读取全部历史（1.1.13 起），这样后来补进 Health Connect 的旧数据也能读到。不含今天。
- **一天有多个 App 的数据时**：卡路里只取一个 App（CrossSync 优先，其次 Garmin Connect，再次数据最多的 App）；睡眠只取一个 App（Garmin Connect 优先，其次 CrossSync）；体重、体脂取当天所有读数的平均值。
- **睡眠**：算在醒来那天；总睡眠 = 浅睡 + 深睡 + REM（+ 未分期的睡眠），不含清醒；三个百分比按占总睡眠计算。
- **三张折线图**：总卡路里；体重（左轴 kg）/ 体脂率（右轴 %）；睡眠（左轴小时 / 右轴 %）。
- **时间粒度**（同时作用于三张图）：日 = 截至昨天的 21 天；月 = 今年 1 月到本月，每月日平均；年 = 每个有数据的年份，每年日平均。只用有数据的日子求平均。
- **横轴**：统一日期格式（`2026-11-14` / `2026-11` / `2026`），下方标注「时间粒度：日 · 起 至 止」。
- **选择显示的数据**：每张图上方的按钮可开关每条线，选择会被记住；纵轴只按显示中的线自动缩放；只剩右轴的线时改用左轴。点图表可查看当天数值。
- **折叠屏**：宽度 ≥ 600dp（Honor Magic V5 内屏约 860dp）时，卡路里和体重/体脂并排，睡眠图占满一行；外屏（约 420dp）三图上下排列。

### 代码
- `fitness/FitnessData.kt`：`DayStats`、`Field`、`FitnessReader` 接口、`FitnessStore`（缓存）、`FitnessRepository`（刷新逻辑）
- `fitness/HealthConnectFitnessReader.kt`：Health Connect 读取与去重
- `fitness/Aggregation.kt`：日 / 月 / 年聚合
- `fitness/LineChart.kt`：Canvas 折线图（双轴、自动缩放、点击提示）
- `fitness/FitnessScreen.kt`：Fitness 页面
- `ui/MainActivity.kt`：新增底部导航 `AppRoot`；Sync 页面代码未改

## v1.0（基线）

- 快照分支：`baseline-v1.0`（提交 `bad9e99`）
- 对应安装包：Release `v1.0.8`（`CrossSync-1.0.8.apk`）
- 保存日期：2026-11-15

### 功能
- **数据**：Garmin Connect（国际版）每日总卡路里消耗（含基础代谢）→ Health Connect `TotalCaloriesBurnedRecord`，每天一条，时间为当地 0:00–24:00。
- **登录**：App 内嵌 Garmin 官方移动端登录页（支持两步验证），用 service ticket 换取 DI OAuth2 令牌；令牌用 Android Keystore 加密保存，密码不保存。
- **写入规则**：记录 ID 为 `garmin-total_calories-YYYY-MM-DD`，版本号取写入时间。与 Garmin 一致则跳过；不一致或上次失败则覆盖；Garmin 无数据而之前写过则删除。只同步已结束的日子，不含今天。
- **自动同步**：每天定时（默认 01:00，可改），回看过去 7 天；精确闹钟触发，另有每 12 小时一次的兜底检查，开机或改时区后会重新设定。
- **首次回填**：Garmin 登录且 Health Connect 授权都完成后，自动回填过去 180 天（每 30 天一段）。
- **手动同步**：同步过去 7 天；同步指定日期范围（强制覆盖）。
- **失败提醒**：写入失败时弹出高优先级通知，首页显示红色提示条和「重试」/「重新登录」按钮；系统暂停任务不算失败。
- **同步记录**：最上方卡片显示最近一次同步的时间、范围、方式和结果（成功绿字、失败红字），下面列出历史记录。
- **导出**：把账本导出为 CSV（日期、类型、数值、记录 ID、同步时间、状态、错误）。
- **数据存放**：只存在手机上（Health Connect 和 App 的账本文件），App 关闭了系统备份，除 Garmin 外不连接任何服务器。
- **图标**：自定义图标 `branding/icon.png`。

### 结构
- `core/Core.kt`：`Metric`、`DailyValue`、`Source`、`Sink` 接口
- `garmin/`：登录（`LoginActivity`）、令牌（`GarminAuth`）、数据源（`GarminSource`）
- `healthconnect/HealthConnectSink.kt`：写入端
- `sync/`：`SyncEngine`（逐日比对）、`Ledger`（JSON 账本）、`SyncWorker`、`Scheduler`、`Notifier`
- `ui/MainActivity.kt`：单页界面（Compose）
- `.github/workflows/build.yml`：编译、签名、发布，并把编译日志写到 `ci-logs` 分支
