# VerifyBlind — R8 kuralları
#
# Bu dosya 2027 Şubat'ta yürürlüğe giren Play "DEX code optimization" gerekliliği için
# R8'i açarken yazıldı (min. %25 optimization/shrinking/obfuscation kapsamı).
#
# Kuralları yazarken iki şey doğrulandı, varsayılmadı:
#  1. Retrofit ve OkHttp JAR'ları META-INF/proguard altında kendi kurallarını taşıyor →
#     onlar için kural YAZMAYA GEREK YOK.
#  2. Gson, BouncyCastle (bcprov+bcpkix), JMRTD ve CBOR HİÇBİR consumer kuralı taşımıyor
#     (scuba AAR'ında da proguard.txt yok) → aşağıdaki kurallar onlar için zorunlu.

# ─────────────────────────────────────────────────────────────────────────────
# OBFUSCATION KAPALI — bilinçli bir ürün kararı
# ─────────────────────────────────────────────────────────────────────────────
# Uygulamanın güven modeli "kaynak public, telefonundaki DEX'in hash'ini kendin doğrula".
# Obfuscation hash doğrulamasını teknik olarak bozmaz, ama yayınlanan kaynak ile çalışan
# ikili arasındaki insan-okunur karşılığı yok eder — denetlenebilirlik iddiasının bedeli
# kazanılan birkaç yüz KB'den yüksek. Shrink + optimize açık, isimler okunur kalıyor.
#
# Yan fayda: Sentry stack trace'leri mapping dosyası yüklemeden okunabilir kalır
# (Sentry Gradle plugin'i kurulu değil), ve Gson'ın alan adlarıyla derdi olmaz.
-dontobfuscate

# Sentry/Play Console crash raporlarında satır numarası görebilmek için.
-keepattributes SourceFile,LineNumberTable,Signature,InnerClasses,EnclosingMethod
-keepattributes *Annotation*,RuntimeVisibleAnnotations,AnnotationDefault

# ─────────────────────────────────────────────────────────────────────────────
# Gson — consumer kuralı YOK, yansımayla çalışır
# ─────────────────────────────────────────────────────────────────────────────
# R8 bir alanın yalnız yansımayla okunduğunu göremez, "ölü" sanıp atar; sonuç sessizce
# null gelen alanlardır (derleme hatası değil, çalışma zamanı bozukluğu).
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken
-keepclassmembers,allowshrinking class com.google.gson.** { <fields>; }
-dontwarn com.google.gson.**
-dontwarn sun.misc.Unsafe

# ─────────────────────────────────────────────────────────────────────────────
# Kendi serileştirilen modellerimiz
# ─────────────────────────────────────────────────────────────────────────────
# api/ApiModels.kt: 29 data class + 98 @SerializedName — relay sözleşmesinin tamamı.
# backup/BackupModels.kt: .vfbackup dosya formatı — bir alanın düşmesi kullanıcının
# yedeğini SESSİZCE geri yüklenemez hale getirir, o yüzden burada cimrilik yapmıyoruz.
-keep class com.verifyblind.mobile.api.** { *; }
-keep class com.verifyblind.mobile.backup.** { *; }
-keep class com.verifyblind.mobile.network.ApiError { *; }
-keep class com.verifyblind.mobile.data.HistoryEntity { *; }

# Gson enum'ları isimle serileştirir; values()/valueOf() yansımayla çağrılır.
-keepclassmembers enum com.verifyblind.mobile.** {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# Room: @Entity/@Dao/@Database üçlüsü + üretilen Impl sınıfları.
# (Room AAR'ı kendi kurallarını taşır; bu ek katman entity alanlarını garantiye alır.)
-keep @androidx.room.Entity class * { *; }
-keep class * extends androidx.room.RoomDatabase { *; }

# ─────────────────────────────────────────────────────────────────────────────
# BouncyCastle — consumer kuralı YOK
# ─────────────────────────────────────────────────────────────────────────────
# Sağlayıcı sınıfları algoritma adıyla, yansımayla bulunur; R8 çağrı grafiğinde
# göremez. Bunlar düşerse NFC okuma ve attestation doğrulama çalışma zamanında patlar.
-keep class org.bouncycastle.jcajce.provider.** { *; }
-keep class org.bouncycastle.jce.provider.** { *; }
-keep class org.bouncycastle.crypto.** { *; }
-keep class org.bouncycastle.asn1.** { *; }
# BC masaüstü JDK sınıflarına referans verir; Android'de yokturlar ve kullanılmazlar.
-dontwarn org.bouncycastle.**
-dontwarn javax.naming.**
-dontwarn java.awt.**
-dontwarn javax.swing.**

# ─────────────────────────────────────────────────────────────────────────────
# JMRTD / SCUBA (NFC pasaport-kimlik okuma) — consumer kuralı YOK
# ─────────────────────────────────────────────────────────────────────────────
-keep class org.jmrtd.** { *; }
-keep class net.sf.scuba.** { *; }
-dontwarn org.jmrtd.**
-dontwarn net.sf.scuba.**
-dontwarn javax.imageio.**

# ─────────────────────────────────────────────────────────────────────────────
# CBOR (AWS Nitro attestation COSE_Sign1 çözümlemesi) — consumer kuralı YOK
# ─────────────────────────────────────────────────────────────────────────────
-keep class com.upokecenter.cbor.** { *; }
-keep class com.upokecenter.numbers.** { *; }
-dontwarn com.upokecenter.**

# ─────────────────────────────────────────────────────────────────────────────
# Yerel (JNI) köprüler
# ─────────────────────────────────────────────────────────────────────────────
# native metotların adı C tarafındaki sembolle eşleşmek zorunda — R8 bunu bilemez.
-keepclasseswithmembernames class * {
    native <methods>;
}
# LiteRT/TFLite: FaceEmbedder'ın Interpreter'ı ve tensör yardımcıları.
-keep class org.tensorflow.lite.** { *; }
-keep class com.google.ai.edge.litert.** { *; }
-dontwarn org.tensorflow.lite.**

# ─────────────────────────────────────────────────────────────────────────────
# Determinizm
# ─────────────────────────────────────────────────────────────────────────────
# R8'in ServiceLoader optimizasyonu servis listesini build'den build'e farklı sıralayabiliyor
# (F-Droid'in belgelediği bilinen yeniden-üretilebilirlik kırıcısı). Bizim en büyük
# ServiceLoader tüketicimiz BouncyCastle'ın sağlayıcı kaydı ve o zaten yukarıda -keep'li.
# verify-reproducibility.yml her release'te bunu doğruluyor; orada FAIL görürsek ilk
# şüpheli burasıdır.
-keep class java.util.ServiceLoader { *; }

# ─────────────────────────────────────────────────────────────────────────────
# Bulut yedek sağlayıcıları — kural taşımayan, yansımaya dayanan SDK'lar
# ─────────────────────────────────────────────────────────────────────────────
# Bu blok, R8 ilk açıldığında DEX'i 36 MB'tan 9 MB'a indirdikten SONRA eklendi:
# google-api-client kendi kurallarını taşıyor AMA google-api-services-drive,
# google-http-client, dropbox-core-sdk, dropbox-android-sdk ve jackson-core HİÇ
# taşımıyor (tek tek doğrulandı). Bu yol kullanıcının yedeğini geri yükleme yolu —
# buradaki bir eksiklik derleme hatası değil, "yedeğim açılmıyor" olarak geri döner.

# Drive model sınıfları alanlarını @Key ile işaretler; eşleme tamamen yansımayla.
-keepclassmembers class * {
    @com.google.api.client.util.Key <fields>;
}
-keep class * extends com.google.api.client.json.GenericJson { *; }
-keep class com.google.api.services.drive.model.** { *; }
-keep class com.google.api.client.googleapis.** { *; }
# Apache HttpClient build.gradle.kts'te exclude edildi; http-client ona referans veriyor.
-dontwarn org.apache.http.**
-dontwarn android.net.http.AndroidHttpClient
-dontwarn com.google.api.client.**

# Dropbox v2 üretilmiş serileştiricileri + Jackson tabanını kullanır.
-keep class com.dropbox.core.** { *; }
-keep class com.fasterxml.jackson.** { *; }
-dontwarn com.dropbox.core.**
-dontwarn com.fasterxml.jackson.**
-dontwarn okio.**

# ─────────────────────────────────────────────────────────────────────────────
# Retrofit + R8 full mode — ÜRETİMDE DOĞRULANMIŞ REGRESYON, SİLME
# ─────────────────────────────────────────────────────────────────────────────
# Belirti: her API çağrısı patlar, ama kullanıcıya "İnternete ulaşılamadı" diye görünür
# (MainViewModel'in catch(Exception) bloğu her istisnayı CONNECTION sayıyor).
#   ClassCastException: java.lang.Class cannot be cast to java.lang.reflect.ParameterizedType
#     at retrofit2.HttpServiceMethod$CallAdapted.parseAnnotations
#   (Sentry issue 143088976, release 1.0.168+233 — R8'in açıldığı ilk build)
#
# Sebep: R8 full mode (AGP 9 varsayılanı) KEEP EDİLMEYEN sınıflardan generic imzaları
# siler. -keepattributes Signature bunu ÖNLEMEZ: öznitelik korunur ama sınıf keep
# edilmediği için imza zaten üretilmez. KimlikApi'nin tamamı `suspend fun ...: Response<T>`
# ve Retrofit dönüş tipini ParameterizedType'a cast ederek tip argümanını okuyor.
#
# Retrofit bu üç kuralı 2.10'da kendi retrofit2.pro'suna ekledi; biz 2.9.0'dayız ve
# 2.9.0'ın taşıdığı kurallarda YOKLAR (jar içindeki dosya okunarak doğrulandı).
# Retrofit sürümü yükseltilirse bu blok gereksizleşir ama zararsızdır.
-keep,allowobfuscation,allowshrinking interface retrofit2.Call
-keep,allowobfuscation,allowshrinking class retrofit2.Response
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation
