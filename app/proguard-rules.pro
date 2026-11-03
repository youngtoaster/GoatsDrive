# Keep JNI entry points and methods called by native code
-keepclasseswithmembernames class * {
    native <methods>;
}

-keep class com.apexlink.projection.NativeBridge { *; }
-keep class com.apexlink.projection.ProjectionSurfaceView { *; }
-keep class com.apexlink.projection.service.ProjectionForegroundService { *; }
-keep class com.apexlink.projection.receiver.ConnectionTriggerReceiver { *; }

# Strip line numbers and debugging meta in release
-renamesourcefileattribute SourceFile
-keepattributes SourceFile,LineNumberTable
-optimizations !code/simplification/arithmetic,!field/*,!class/merging/*
