# CrossSync 版本记录

版本号格式：`功能版本.编译号`，例如 1.0.8 = 1.0 功能版本的第 8 次编译。
功能版本写在根目录的 `VERSION` 文件里，新增功能时升级（1.0 → 1.1）；编译号是 GitHub Actions 的运行次数，只增不减。

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
