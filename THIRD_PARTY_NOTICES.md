# Third-party notices

Bellnote 自身以 MIT 许可发布（见 [LICENSE](LICENSE)）。下面列出它实际用到的第三方组件、模型与字体，以及各自的许可。

## 运行时依赖（打进 APK）

| 组件 | 版本 | 许可 | 用途 |
| --- | --- | --- | --- |
| `androidx.core:core-ktx` | 1.13.1 | Apache License 2.0 | Kotlin 扩展与兼容层 |
| `androidx.appcompat:appcompat` | 1.7.0 | Apache License 2.0 | Activity / 主题 / 控件兼容 |
| `androidx.drawerlayout:drawerlayout` | 1.2.0 | Apache License 2.0 | 首页侧边抽屉 |
| `androidx.recyclerview:recyclerview` | 1.3.2 | Apache License 2.0 | 待办列表、全部日程 |
| `androidx.constraintlayout:constraintlayout` | 2.1.4 | Apache License 2.0 | 布局约束 |
| `androidx.activity:activity-ktx` | 1.9.0 | Apache License 2.0 | 结果回调、Activity 扩展 |
| `com.google.mlkit:text-recognition-chinese` | 16.0.1 | **ML Kit Terms of Service**（Google，闭源，包含随包分发的端侧 OCR 模型） | 中文截图文字识别（可选） |
| `com.google.mediapipe:tasks-genai` | 0.10.24 | Apache License 2.0 | 端侧小模型推理（可选本地识别） |

ML Kit 的许可不是开源许可，使用它需遵守 <https://developers.google.com/ml-kit/terms>。它的端侧 OCR 模型随 APK 一起分发，不会再单独下载。

## 运行期下载的模型（不打进 APK）

| 模型 | 许可 | 说明 |
| --- | --- | --- |
| Gemma 3 270M-IT（q8，`.task`） | **Gemma Terms of Use**（Google） | 用户在设置里点「下载模型」时才从 ModelScope 下载到应用私有目录 |
| Gemma 3 1B-IT（q4，`.task`） | **Gemma Terms of Use**（Google） | 同上，默认模型 |
| Qwen2.5 0.5B-Instruct（q8，`.task`） | Apache License 2.0 | 同上 |

Gemma 的使用受 Gemma Terms of Use 约束（<https://ai.google.dev/gemma/terms>），其中包含再分发与用途限制。模型文件由 [ModelScope（魔搭）](https://modelscope.cn) 托管，下载行为同时受该平台条款约束。

## 只在测试期使用

| 组件 | 版本 | 许可 |
| --- | --- | --- |
| `junit:junit` | 4.13.2 | Eclipse Public License 1.0（仅 `testImplementation`，不进 APK） |

## 字体

本应用**没有**打包任何自定义字体，全部使用 Android 系统字体（`sans-serif` / `sans-serif-medium`），不涉及字体许可与再分发。

## 协议与名称

- [ntfy](https://ntfy.sh) 只是 HTTP 协议与公共服务，本项目不包含 ntfy 的代码或图标，也不需要 ntfy 客户端。
- Android、Google Play、ML Kit、Gemma 是 Google LLC 的商标；Qwen 是阿里巴巴的商标；ModelScope 是阿里的平台。本项目与这些公司无隶属关系。

## 更新方式

新增依赖或更换模型时，请同步更新本文件；`README.md` 的 License 段落与本文件一起维护。
