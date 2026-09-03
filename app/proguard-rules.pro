# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# 保持 Sherpa-onnx 的类结构，防止被混淆
-keep class com.k2fsa.sherpa.onnx.** { *; }

#opecc4j
-keep public class com.github.houbb.opencc4j.** {}
-keep interface com.github.houbb.opencc4j.* {*;}
-keep class com.github.houbb.opencc4j.model.** {}
-keep class com.github.houbb.opencc4j.support.* {*;}
-keep public class com.github.houbb.heaven.** {}
-keep interface com.github.houbb.heaven.* {*;}
-keep @com.github.houbb.heaven.annotation.* class *
-keep class * {
    @com.github.houbb.heaven.annotation.* *;
}
-dontwarn com.huaban.analysis.jieba.**
-dontwarn java.awt.**
-dontwarn java.beans.**
-dontwarn java.lang.management.**
-dontwarn javax.tools.**
-keep public class com.huaban.analysis.jieba.** {}
-keep interface com.huaban.analysis.jieba.* {*;}

# 保持 ONNX Runtime 的类结构
-keep class ai.onnxruntime.** { *; }

# 确保 native 方法不会被重命名或移除
-keepclasseswithmembernames class * {
    native <methods>;
}