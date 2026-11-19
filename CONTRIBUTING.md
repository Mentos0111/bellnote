# 参与贡献（Contributing）

欢迎！Bellnote 是一个安卓日历待办 App（截图 / 一句话 → 系统日历），
仓库是公开的，任何人 fork 之后都可以改、提合并请求（Pull Request，简称 PR）。

## 怎么开始（第一次）

1. **Fork**：打开 <https://github.com/Mentos0111/bellnote>，点右上角 **Fork**，得到你自己的一份副本。
2. **克隆到本地**：
   ```bash
   git clone https://github.com/<你的用户名>/bellnote.git
   cd bellnote
   git remote add upstream https://github.com/Mentos0111/bellnote.git
   ```
3. **建分支**（不要直接在 `main` 上改）：
   ```bash
   git switch -c fix/一句话时间解析
   ```
4. 改代码、跑测试（见下），提交：
   ```bash
   git add <改动文件>
   git commit -m "fix(parser): 期限词不进标题"
   git push -u origin fix/一句话时间解析
   ```
5. 回到 GitHub，点 **Compare & pull request**，目标选 `Mentos0111/bellnote` 的 `main`。
   PR 会自动跑单测；绿了之后等维护者 review。

> 合并之后，GitHub 会自动把你算成这个仓库的贡献者，不需要谁手动“加人”。

## 本地怎么构建 / 跑测试

- 需要：JDK 17、Android SDK（`local.properties` 里写 `sdk.dir`）。
- 构建 APK：`./gradlew assembleDebug`（Windows 上可以用项目里的 `build.cmd`）。
- **提交前必须跑单测**：
  ```bash
  ./gradlew testDebugUnitTest
  ```
  当前基线：**30 个测试类 / 275 个用例，必须全绿**。
- 真机验证：装 `app/build/outputs/apk/debug/app-debug.apk` 到安卓手机上试一遍；
  UI 相关改动最好附一张截图。

## 代码约定

- **一次 commit 只做一件事**，信息用约定式前缀：`feat(...)` / `fix(...)` / `docs(...)` / `ci(...)` / `chore(...)`。
- 新增功能尽量配单测；修 bug 先写能复现的用例，再改实现。
- **不要改版本号**：`app/build.gradle.kts` 里的 `bellnoteDefaultVersionName` 由维护者发版时统一改。
- **不要打 tag**：`v*` 标签会触发自动发版（构建 + 正式签名 + 发 Release），只由维护者操作。
- 不要改 `CHANGELOG.md` 的标题格式（`## [x.y.z] - 日期` 会被发版流程解析）。
- 新设置项、新入口先开 issue 聊，不要直接写。

## 红线（会被直接拒绝）

- **不提交个人信息**：真实姓名、电话、邮箱、聊天截图、真实课程/作业内容、任何密钥。
  公开仓库对所有人可见。
- **不提交密钥 / 签名文件**：`keystore/`、`*.jks`、`*.credentials.txt`、`backup/` 一律不进仓库。
- 不提交构建产物（`build/`、`*.apk`、`.gradle/`），仓库的 `.gitignore` 已经覆盖。
- 不删别人的代码/文档来“顺手整理”；只改你这次要改的。

## 目录速览

| 目录 | 放什么 |
|---|---|
| `app/src/main/java/com/bellnote/` | 源码：`ui/` 界面、`data/` 存储与日历、`parser/` 解析、`ai/` 模型调用、`service/` 后台 |
| `app/src/test/java/com/bellnote/` | 单元测试 |
| `docs/` | 截图与说明 |
| `scripts/` | 发版、校验等维护脚本（一般不用改） |
| `.github/workflows/` | CI：PR 跑测试，打 tag 发版 |

## 有疑问

- 开一个 [Issue](https://github.com/Mentos0111/bellnote/issues) 描述现象/想法；
- 或者在 PR 里直接问，维护者会看。
