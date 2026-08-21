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

# Play Console deobfuscates release crashes with the mapping file that supply uploads alongside
# the bundle — but without these two lines the deobfuscated frames carry no line numbers, which is
# most of what a stack trace is for. The cost is a few kilobytes.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Ktor pulls in slf4j, whose optional static binder is absent at runtime.
-dontwarn org.slf4j.impl.StaticLoggerBinder