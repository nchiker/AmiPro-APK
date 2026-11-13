# UniFFI's generated Kotlin reaches the Rust library through JNA by reflection and symbol name:
# stripping or renaming any of it breaks the bridge, and only in release builds.
-keep class uniffi.** { *; }
-keep class com.sun.jna.** { *; }
-keepclassmembers class * extends com.sun.jna.Structure { *; }
-keepclassmembers class * implements com.sun.jna.Callback { *; }
-dontwarn java.awt.**
-dontwarn com.sun.jna.**
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
