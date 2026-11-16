# JNA / UniFFI : les classes appelées depuis le code natif doivent rester intactes.
-keep class com.sun.jna.** { *; }
-keep class uniffi.dicto_ffi.** { *; }
-dontwarn java.awt.**
