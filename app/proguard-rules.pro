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

# The crash reporter (crash/CrashReportingHandler.kt) files a GitHub issue
# with the raw exception type and stack trace from the release build, and
# nobody reading that issue has the R8 mapping file — it never leaves Play
# Console. Without these, a report is a wall of single-letter class names and
# no line numbers, which is not something a maintainer can act on.
#
# Line numbers are kept, and the real source file name is deliberately *not*
# hidden (renamesourcefileattribute is left off) so a trace reads as
# "Foo.kt:42" against this repository's own file layout without needing the
# mapping file at all.
-keepattributes SourceFile,LineNumberTable

# Exception types stay named, so exceptionType (shown in the crash dialog and
# in the filed issue's title) is the real class rather than an obfuscated one.
-keep public class * extends java.lang.Throwable
