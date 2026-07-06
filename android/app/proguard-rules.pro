# Glass Falcon — keep DUML/telemetry data classes for reflection
-keepclassmembers class dev.glassfalcon.core.** { *; }
-keepclassmembers class dev.glassfalcon.** implements androidx.lifecycle.ViewModel { *; }

# MapLibre
-keep class org.maplibre.** { *; }

# ExoPlayer / Media3
-keep class androidx.media3.** { *; }

# DJI MSDK (optional — only included when DJI_APP_KEY is set)
-keep class dji.** { *; }
-keep class com.dji.** { *; }
