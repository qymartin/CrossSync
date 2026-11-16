# CrossSync 版本记录

版本号格式：`功能版本.编译号`，例如 1.0.8 = 1.0 功能版本的第 8 次编译。
功能版本写在根目录的 `VERSION` 文件里，新增功能时升级（1.0 → 1.1）；编译号是 GitHub Actions 的运行次数，只增不减。

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
