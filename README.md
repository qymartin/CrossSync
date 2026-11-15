# CrossSync

把 Garmin Connect 的数据每天同步到 Android 的 Health Connect。第一版只同步**每日总卡路里消耗**。

- 每天在设定的时间（默认凌晨 1:00）自动运行，回看过去 7 天：数据没变就跳过，变了就覆盖，少了就补上。
- 首次使用时自动回填过去 180 天。
- 写入 Health Connect 失败时，会弹出高优先级通知，App 首页显示红色提示条和「重试」按钮。
- 可以导出 CSV，作为独立备份。

## 工作方式

```
Garmin 云端 ──(只读)──> GarminSource ─┐
                                     ├─> SyncEngine ──> HealthConnectSink ──> Health Connect
                            Ledger <─┘    (逐日比对)       (按固定 ID 覆盖)
```

- **Garmin**：在 App 内嵌的 Garmin 官方登录页登录（两步验证也在这里完成），拿到令牌后加密保存在手机上。CrossSync 不保存密码，也从不向 Garmin 写数据。
- **Health Connect**：每天一条记录，ID 形如 `garmin-total_calories-2026-11-13`。同一个 ID 带更新的版本号写入时，Health Connect 会替换旧记录。
- **账本**：App 内部的一个 JSON 文件，记录每天写了什么，用来判断数据有没有变化。

以后要换数据来源或写入目标，只需在 `core/Core.kt` 里实现新的 `Source` 或 `Sink`。

## 构建

推送到 `main` 后，GitHub Actions 会自动编译、签名，并发布到 Releases。签名需要一个仓库 Secret：`SIGNING_PASSWORD`。
