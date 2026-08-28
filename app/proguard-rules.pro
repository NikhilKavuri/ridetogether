# Firebase Realtime Database is normally the #1 source of R8 crashes, because
# it maps snapshots onto model classes by reflection and R8 renames the fields
# out from under it. This app never does that -- every read goes through
# explicit snapshot.child("field") calls in Snapshots.kt and every write is a
# plain Map. So there is nothing here to keep, and no reflective failure mode.

-keepattributes Signature
-keepattributes *Annotation*
-keepattributes SourceFile,LineNumberTable

# Keep enough of a stack trace to be able to read a crash report.
-renamesourcefileattribute SourceFile

# OkHttp ships its own rules but these silence known R8 warnings.
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
