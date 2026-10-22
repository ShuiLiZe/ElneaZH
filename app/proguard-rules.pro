# 汉化器：混淆自己的代码，第三方库原样保留
-keep class com.android.apksig.** { *; }
-keep class org.apache.commons.compress.** { *; }
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod
# commons-compress / apksig 的可选依赖不打包
-dontwarn org.tukaani.**
-dontwarn com.github.luben.**
-dontwarn org.brotli.**
-dontwarn org.objectweb.asm.**
-dontwarn org.osgi.**
-dontwarn javax.annotation.**
-dontwarn com.google.errorprone.**
-dontwarn org.bouncycastle.**
-dontwarn org.conscrypt.**
-ignorewarnings
# 让崩溃栈还能对上（只留行号，不留源文件名）
-keepattributes LineNumberTable
-renamesourcefileattribute x
