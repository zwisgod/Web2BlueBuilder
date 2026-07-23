# ═══════════════════════════════════════════════════════════════════
# proguard-rules.pro — Web2Blue Android 壳工程混淆规则
#
# 核心原则：框架层（蓝牙桥接、JS 接口、回调）绝对不混淆；
#           用户的业务代码不在壳工程中，无需关心。
#
# 使用方式：app/build.gradle 的 release 构建类型中已引用此文件：
#   proguardFiles getDefaultProguardFile('proguard-android-optimize.txt'), 'proguard-rules.pro'
# ═══════════════════════════════════════════════════════════════════


# ───────────────────────────────────────────────────────────────────
# 规则 1：保护 JS 桥接类（最高优先级！）
#
# 问题背景：
#   混淆会把 BlueAppBridge.connect(String,String) 重命名为 a.a(String,String)，
#   但 HTML 里的 JS 调用的是 BlueApp.connect(...)——固定的名字。
#   一旦被混淆重命名，JS 调用就会 silently 失败（不报错，但什么都不执行）。
#   这是最难排查的 bug，必须彻底防护。
#
# 解决方案：
#   保持 BlueAppBridge 的类名和所有公开方法名不变。
# ───────────────────────────────────────────────────────────────────

# 保留整个 bridge 包（BlueAppBridge + CallbackDispatcher）
-keep class com.web2blue.shell.bridge.** { *; }

# 专门保护所有带 @JavascriptInterface 注解的方法
# （双重保险：即使类名被混淆了，方法名也要保留）
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# ───────────────────────────────────────────────────────────────────
# 规则 2：保护蓝牙层（防止 Callback 子类被混淆导致回调失效）
#
# 问题背景：
#   BluetoothGattCallback、BluetoothAdapter.LeScanCallback 等是 Android 系统
#   通过反射调用的类。混淆后方法名变了，系统找不到对应方法，回调就丢失了。
# ───────────────────────────────────────────────────────────────────

# 保留整个 bluetooth 包（BluetoothRouter、SppConnector、BleConnector、IBluetoothConnector）
-keep class com.web2blue.shell.bluetooth.** { *; }

# 保留所有 BluetoothGattCallback 子类（Android BLE 核心回调）
-keep class * extends android.bluetooth.BluetoothGattCallback { *; }

# 保留所有 BroadcastReceiver 子类（经典蓝牙扫描广播接收）
-keep class * extends android.content.BroadcastReceiver { *; }

# 保留 BLE ScanCallback 子类（BLE 扫描结果回调）
-keep class * extends android.bluetooth.le.ScanCallback { *; }

# ───────────────────────────────────────────────────────────────────
# 规则 3：保护调试层（防止调试控制台 UI 行为异常）
# ───────────────────────────────────────────────────────────────────

# 保留 debug 包（LogEntry、LogRepository、DebugConsoleView、LogEntryAdapter）
-keep class com.web2blue.shell.debug.** { *; }

# ───────────────────────────────────────────────────────────────────
# 规则 4：保护 Activity（防止 AndroidManifest 中引用的类名被混淆）
#
# Manifest 中声明的是完整类名（如 .WebViewActivity），
# 系统启动时通过这个类名反射实例化，混淆后找不到就崩溃。
# ───────────────────────────────────────────────────────────────────

-keep class com.web2blue.shell.MainActivity    { *; }
-keep class com.web2blue.shell.WebViewActivity { *; }

# ───────────────────────────────────────────────────────────────────
# 规则 5：保留 Android 标准回调（防止系统通过反射调用的方法被删除）
# ───────────────────────────────────────────────────────────────────

# 保留所有 Activity 的生命周期方法
-keepclassmembers class * extends android.app.Activity {
    public void *(android.view.View);
}

# 保留 onRequestPermissionsResult（权限申请回调）
-keepclassmembers class * extends android.app.Activity {
    public void onRequestPermissionsResult(int, java.lang.String[], int[]);
}

# ───────────────────────────────────────────────────────────────────
# 规则 6：第三方库保留（如果后续引入了网络库、JSON 库等，在此添加）
# ───────────────────────────────────────────────────────────────────

# 保留 AndroidX（系统组件，不需要混淆）
-keep class androidx.** { *; }
-dontwarn androidx.**

# 保留 Google Material（UI 组件）
-keep class com.google.android.material.** { *; }
-dontwarn com.google.android.material.**

# ───────────────────────────────────────────────────────────────────
# 规则 7：调试辅助（保留行号信息，方便 crash 时定位源码位置）
# ───────────────────────────────────────────────────────────────────

# 保留源文件名和行号（在 StackTrace 中显示行号而不是混淆后的位置）
-keepattributes SourceFile, LineNumberTable

# 保留泛型签名（反射和序列化依赖）
-keepattributes Signature

# 保留注解（@JavascriptInterface 等注解必须保留才能被识别）
-keepattributes *Annotation*

# 保留异常类型信息
-keepattributes Exceptions

# 将混淆映射文件输出到 build 目录（用于 crash 反混淆）
# 使用 retrace 工具 + mapping.txt 可以还原真实 StackTrace
-printmapping build/outputs/mapping/release/mapping.txt

# ───────────────────────────────────────────────────────────────────
# 规则 8：噪音抑制（减少无关警告，保持构建输出清洁）
# ───────────────────────────────────────────────────────────────────

# 忽略反射调用相关的警告（SppConnector 用反射调用 createRfcommSocket）
-dontwarn java.lang.reflect.**

# 忽略 Android 系统类的缺失警告（部分 API 在旧版本 SDK 上不存在）
-dontwarn android.bluetooth.**
