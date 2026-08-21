# Release stack traces are worth reading: Play deobfuscates with the uploaded mapping file, but
# without these the frames arrive with no line numbers in them.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Ktor pulls in slf4j, whose optional static binder is absent at runtime.
-dontwarn org.slf4j.impl.StaticLoggerBinder
