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

# commons-compress 的可选编解码依赖（zstd/brotli/xz/pack200/asm）：App 只用
# ZipInputStream 解 resource.zip，这些类运行时不可达，加 dontwarn 即可
-dontwarn com.github.luben.zstd.**
-dontwarn org.brotli.dec.**
-dontwarn org.tukaani.xz.**
-dontwarn org.objectweb.asm.**
-dontwarn java.lang.reflect.AnnotatedType

# ===== Jime JNI 反射保留规则（jni_env.h:166-222，FindClass + GetMethodID）=====
# R8 会改名/删除这些"看起来没被调用"的构造器，GetMethodID 返回 null 即 native 崩溃
-keep class com.jobeen.ime.engine.rime.core.Rime {
    public static void handleMessage(int, java.lang.Object[]);
}
-keep class com.jobeen.ime.engine.rime.core.CandidateProto {
    <init>(java.lang.String, java.lang.String, java.lang.String, java.lang.String);
}
-keep class com.jobeen.ime.engine.rime.core.CommitProto {
    <init>(java.lang.String);
}
-keep class com.jobeen.ime.engine.rime.core.ContextProto {
    <init>(com.jobeen.ime.engine.rime.core.CompositionProto, com.jobeen.ime.engine.rime.core.MenuProto, java.lang.String, int);
}
-keep class com.jobeen.ime.engine.rime.core.SyllableProto {
    <init>(java.lang.String, java.lang.String, java.lang.String, int, int);
}
-keep class com.jobeen.ime.engine.rime.core.CompositionProto {
    <init>(int, int, int, int, java.lang.String, java.lang.String, com.jobeen.ime.engine.rime.core.SyllableProto[]);
}
-keep class com.jobeen.ime.engine.rime.core.MenuProto {
    <init>(int, int, boolean, int, com.jobeen.ime.engine.rime.core.CandidateProto[], java.lang.String, java.lang.String[]);
}
-keep class com.jobeen.ime.engine.rime.core.StatusProto {
    <init>(java.lang.String, java.lang.String, boolean, boolean, boolean, boolean, boolean, boolean, boolean);
}
-keep class com.jobeen.ime.engine.rime.core.SchemaItem {
    <init>(java.lang.String, java.lang.String, java.lang.String, java.lang.String, java.lang.String);
}
-keep class com.jobeen.ime.engine.rime.core.RimeKeyEvent {
    <init>(int, int, java.lang.String);
}