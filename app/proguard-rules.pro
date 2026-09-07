# ---------------------------------------------------------------------------
# JNA + uniffi
#
# The povo-core bridge is reflective end to end: JNA maps a Kotlin interface
# onto exported symbols in libpovo_core.so at runtime, and reads the field order
# of every Structure subclass by reflection. R8 sees none of that as a use, so
# without these rules the release build compiles, installs, and then dies the
# first time it touches the native layer.
# ---------------------------------------------------------------------------
-keep class com.sun.jna.** { *; }
-keep interface com.sun.jna.** { *; }
-keepclassmembers class * extends com.sun.jna.Structure {
    <fields>;
    <methods>;
}
-keepclassmembers class * implements com.sun.jna.Library {
    <methods>;
}
# JNA is a desktop-derived library and references AWT, which Android lacks.
-dontwarn java.awt.**

# The generated uniffi bindings: the JNA callback interfaces, the RustBuffer
# structures and the FFI entry points are all reached reflectively.
-keep class uniffi.povo_core.** { *; }
-keepclassmembers class uniffi.povo_core.** { *; }

# ---------------------------------------------------------------------------
# Models serialised into Room / DataStore
#
# kotlinx.serialization ships its own rules for generated serializers, but the
# enum whose entries are written by name still needs its values intact.
# ---------------------------------------------------------------------------
-keepclassmembers enum jp.povo.manager.core.model.** {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# AGP 9 turns on R8 strict full mode, where `-keep class X` no longer implies
# keeping X's constructors. Room instantiates its generated implementations
# reflectively, so name them explicitly.
-keep class * extends androidx.room.RoomDatabase {
    <init>();
}
