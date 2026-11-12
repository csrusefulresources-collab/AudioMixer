# Keep FFmpegKit and LAME entry points used through reflection/JNI.
-keep class com.antonkarpenko.ffmpegkit.** { *; }
-keep class de.sciss.jump3r.** { *; }
-dontwarn com.antonkarpenko.ffmpegkit.**
-dontwarn javax.sound.**
