# R8's rules for COCFARM's release build (app/build.gradle.kts).
#
# The bot is Python (Chaquopy), and it calls the app's own Java by name:
# jclass("com.cocfarm.app.Bridge") and whatever Bridge hands back. R8 can't
# see those calls, so the app's classes are kept whole, names and all. They're
# small; the libraries around them (Material, ML Kit) are what R8 shrinks.
# Chaquopy's runtime and ML Kit bring their own rules.
-keep class com.cocfarm.app.** { *; }

# Stack traces in the log keep their line numbers.
-keepattributes SourceFile,LineNumberTable
