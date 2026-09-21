# 默认 ProGuard 规则；发布版按需补充。
# 保留 HID 传输接口等跨模块契约的类名，避免混淆影响反射/JNI 绑定。
-keep interface com.pocketkeyboard.app.hid.HidTransport { *; }
