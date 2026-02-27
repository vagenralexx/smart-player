# Mantém as classes Room (evita erros de ofuscação no banco de dados)
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-keep @androidx.room.Dao class *

# Mantém as classes Media3
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**

# Mantém os métodos usados pelo Coil
-dontwarn com.bumptech.glide.**
