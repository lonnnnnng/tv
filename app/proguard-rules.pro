# long: proguard-android-optimize.txt 已保留 @JavascriptInterface 方法，避免重复或整包保留影响混淆压缩。

# long: 正式版移除调试和播放地址日志，保留警告、错误以及混淆映射用于排查长播故障。
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}
