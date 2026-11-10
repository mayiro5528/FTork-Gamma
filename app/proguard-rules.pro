# ML Kit ships its own consumer rules. Add project rules here if needed.

# 화면(JS)이 FriendlyNative.* 로 부르는 클래스와 함수는 이름이 바뀌거나 지워지면 안 돼요.
-keep class com.ftork.gamma.FriendlyBridge {
    @android.webkit.JavascriptInterface <methods>;
}
-keepattributes JavascriptInterface
