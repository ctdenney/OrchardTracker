# Room
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-dontwarn androidx.room.paging.**

# OSMDroid
-keep class org.osmdroid.** { *; }

# Google Play Services Location
-keep class com.google.android.gms.location.** { *; }

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }

# Our listener interfaces. R8 full-mode (default in AGP 8+) merges
# single-implementer interfaces into their concrete classes, which breaks
# the `invokeinterface` bytecode at call sites. Crash symptom:
#   IncompatibleClassChangeError: Found class X, but interface was expected
-keep interface com.example.gpstagger.gps.LocationSource$Listener { *; }
-keep interface com.example.gpstagger.gps.UsbGpsManager$Listener { *; }

# usb-serial-for-android resolves driver implementations (FTDI, CP210x,
# CH34x, PL2303, CDC-ACM) reflectively via UsbSerialProber. The drivers
# look unreachable to R8 since they're not referenced by name from app code.
-keep class com.hoho.android.usbserial.** { *; }
-dontwarn com.hoho.android.usbserial.**
