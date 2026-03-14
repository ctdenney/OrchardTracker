# Room
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-dontwarn androidx.room.paging.**

# OSMDroid
-keep class org.osmdroid.** { *; }

# Google Play Services Location
-keep class com.google.android.gms.location.** { *; }
