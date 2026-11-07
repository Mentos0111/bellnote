# Bellnote

**Turn ntfy notifications into calendar events — automatically, on-device.**

Bellnote is a tiny Android app (no Google services required) that listens to notifications from the
[ntfy](https://ntfy.sh) client (`io.heckel.ntfy`), parses the message text, and writes it into your
system calendar with two reminders. You can also create, edit, and delete todos from a local UI, or
import them from a screenshot with a vision model.

[中文说明](#中文说明) · [LICENSE: MIT](LICENSE)

| Todos | Settings |
| --- | --- |
| ![main](docs/screenshots/main.png) | ![settings](docs/screenshots/settings.png) |

## Why

If you already push reminders through ntfy (scripts, cron jobs, another assistant), this app turns
those pushes into real calendar entries — with alarms, recurrence, and no cloud account. It also
works on ROMs without Google Play Services (tested on realme UI 6).

## Features

- **Notification → calendar**: listens to `io.heckel.ntfy`, parses the body, writes a
  `CalendarContract.Events` row plus reminders (default: 1 day and 1 hour before).
- **One notification, many todos**: JSON array, `{"todos":[…]}` wrapper, or blank-line separated blocks.
- **Remote delete**: send `{"action":"delete","title":"…"}` and the matching event is removed.
- **Screenshot import** (optional): pick an image, compress it, send to a vision model, review in the
  edit screen, then save. Needs your own API key.
- **Local calendar creation**: if the device has no writable calendar (common on GMS-less ROMs),
  one tap creates a local calendar via `ACCOUNT_TYPE_LOCAL`.
- **Manual add / edit / delete** with date, time, duration, repeat rule, note.

## Message protocol

Send from any ntfy client or `curl` (`POST https://ntfy.sh/<topic>` with the body below).

**Create one todo**

```json
{"title":"Math homework","time":"2026-11-08 19:00","duration":90,"note":"Chapter 3","repeat":"weekly"}
```

- `title` — required, ≤ 30 chars
- `time` — `yyyy-MM-dd HH:mm`, local time, must be in the future
- `duration` — integer minutes (5–1440), default 60
- `note` — optional
- `repeat` — `none` / `daily` / `weekly` / `monthly` / `yearly`

**Create several todos** — JSON array, or `{"todos":[…]}`.

**Delete**

```json
{"action":"delete","title":"Math homework","time":"2026-11-08 19:00"}
```

Plain text form also works: `删除：Math homework`. Only events written by Bellnote can be deleted;
title must match exactly; without `time`, every same-titled event is deleted.

Key-value text (`标题：…` / `时间：…`) is accepted as a fallback, and unparseable messages become a
todo at *now + 1 hour* so nothing is silently lost.

## How it works

| Piece | What it does |
| --- | --- |
| `service/NtfyNotificationListener` | `NotificationListenerService` filtered to `io.heckel.ntfy` |
| `parser/TodoParser` | JSON + key-value parsing, `parseAll` for multiple todos, `parseCommand` for delete |
| `parser/MiniJson` | ~200-line JSON reader (so parsing is unit-testable on the JVM) |
| `data/CalendarRepository` | insert / update / delete events and reminders, marker-based query |
| `ai/VisionExtractor` | screenshot → JSON via a vision model (optional) |

Events are tagged with `ORGANIZER = bellnote@local` and `[bellnote]` in the description, so the app
can list only its own entries and never touch your other calendars.

## Build

Requirements: JDK 17, Android SDK 34.

```bash
./gradlew assembleDebug          # macOS / Linux
gradlew.bat assembleDebug        # Windows
```

On Windows, `build.cmd` wraps the Gradle call and prints the APK path
(`build.cmd`, `build.cmd testDebugUnitTest`).

APK output: `app/build/outputs/apk/debug/app-debug.apk` (debug-signed; sideload it).

## Install

1. Install the APK (allow “install unknown apps”).
2. Grant **calendar** permission on first save.
3. Enable **notification access**: Settings → Apps → Special app access → Notification access.
   If the listener never connects, open the app once and toggle the permission off/on — some ROMs
   only bind the service when the app process is alive.
4. Pick a target calendar in Settings (create a local one with the button if the list is empty).
5. Optional: set an API key for screenshot import.

### realme / ColorOS notes

- No built-in “new local calendar” entry — use the app’s **Create local calendar** button.
- Add the app (and the ntfy client) to battery whitelist / auto-start so the listener survives.
- The desktop calendar card shows *today’s timeline* only, not all upcoming todos.

## Privacy

- No analytics, no ads, no telemetry. Everything stays on the device.
- The only network call is the optional screenshot import to the endpoint you configure.
- Your API key is stored in the app’s private SharedPreferences.

## Limitations

- Recurring events are edited/deleted as a whole series (no “this occurrence only” yet).
- Deleting from a push is not undoable.
- Some vendor calendars reject `ORGANIZER`; the description marker is used as a fallback.

## License

MIT — see [LICENSE](LICENSE).

---

## 中文说明

**Bellnote** 把 ntfy 推送变成系统日历里的待办，全程在本机完成，不需要 Google 服务。

- 监听 `io.heckel.ntfy` 的通知，解析正文后写入 `CalendarContract`，自动加两条提醒（默认提前 1 天 + 1 小时）
- 一条通知可以写多条待办（JSON 数组 / `{"todos":[…]}` / 空行分段）
- 发 `{"action":"delete","title":"…"}` 或「删除：标题」即可远程删待办（只删本 App 写的）
- 截图识别（可选）：截图 → 压缩 → 视觉模型 → 编辑页确认后入日历，需要自己的 API Key
- 设备没有可写日历时，一键创建 `ACCOUNT_TYPE_LOCAL` 本地日历
- 手动添加 / 编辑 / 删除，支持重复规则

构建：`gradlew.bat assembleDebug`（JDK 17 + SDK 34），Windows 上可直接跑 `build.cmd`。
协议、安装步骤、realme 适配见上方英文小节，中文细节在 `docs/` 里。

隐私：不收集任何数据；唯一联网是可选截图识别。

协议：MIT。
