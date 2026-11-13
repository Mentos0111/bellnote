# R8 规则（release 构建已开启 minify + shrinkResources）
#
# manifest 里声明的四大组件（Activity / Service / Receiver / Provider）R8 会按
# manifest 自动保留，不需要手写。全项目没有反射、没有动态类加载、没有 JSON 反序列化
# 到自定义类（用 org.json 手工取字段），所以这里只做两件事：
#   1. 保住行号和源文件名，让 App 内的崩溃日志还能看懂；
#   2. 显式列出入口组件，防止以后有人改 manifest 或挪包名时被误删。

-keepattributes SourceFile,LineNumberTable,Signature,InnerClasses,EnclosingMethod
-renamesourcefileattribute SourceFile

# 通知监听：系统按类名绑定，必须保留
-keep class com.bellnote.service.NtfyNotificationListener { *; }
# 无障碍截屏服务：系统按类名绑定，必须保留
-keep class com.bellnote.service.ScreenCaptureService { *; }
# 定时对账的广播接收器：系统按类名唤起
-keep class com.bellnote.service.ReconcileReceiver { *; }
# 通知栏磁贴服务：系统按类名绑定
-keep class com.bellnote.service.QuickAddTileService { *; }

# 离线大模型（MediaPipe LLM Inference）：native 方法靠 JNI 按类名找，
# 生成的 protobuf 类只用到 javalite，缺的那几个注解类按 R8 建议忽略即可。
-keep class com.google.mediapipe.** { *; }
-keep class com.google.protobuf.** { *; }
-dontwarn com.google.protobuf.**
-dontwarn com.google.mediapipe.**
