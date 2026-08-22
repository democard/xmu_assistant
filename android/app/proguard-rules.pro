# xmu助手 release 混淆规则（保守起步，回归测试发现问题再补）

# OkHttp/Okio 平台相关（官方建议）
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# Tink（security-crypto 底层）引用 errorprone 编译期注解，运行时不需要
-dontwarn com.google.errorprone.annotations.**

# javax.mail（android-mail）在 Android 上部分类不存在，静默即可
-dontwarn javax.activation.**
-dontwarn javax.mail.**
-dontwarn com.sun.mail.**

# Robolectric/测试类不进 release，防误报
-dontwarn org.robolectric.**
-dontwarn org.junit.**

# Kotlin Metadata 保留（Compose/反射元数据）
-keepclassmembers class kotlin.Metadata { *; }
