# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# Preserve line number information for debugging stack traces.
-keepattributes SourceFile,LineNumberTable

# Hide the original source file name.
-renamesourcefileattribute SourceFile

# Keep domain models (enums, sealed interfaces, data classes)
-keep class com.tenmilelabs.touchlock.domain.model.** { *; }

# Keep service actions (referenced by string in intents)
-keep class com.tenmilelabs.touchlock.service.LockOverlayService {
    public static final java.lang.String ACTION_*;
    public static final int NOTIFICATION_ID;
}
