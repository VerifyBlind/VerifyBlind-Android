package com.verifyblind.mobile.api

import com.verifyblind.mobile.BuildConfig
import com.verifyblind.mobile.network.LocaleHeaderInterceptor
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

object RetrofitClient {
    private const val BASE_URL = BuildConfig.API_BASE_URL
    //"http://192.168.1.100:5102/api/Verify/"

    // Yalnızca debug build'lerde body loglanır
    private val logging = HttpLoggingInterceptor().apply {
        level = if (BuildConfig.DEBUG) HttpLoggingInterceptor.Level.BODY
                else HttpLoggingInterceptor.Level.NONE
    }

    // Certificate Pinning YOK (kasıtlı, 2026-07-08): api.verifyblind.com Cloudflare-proxied; edge
    // sertifikasını Cloudflare yönetir ve CA'yı habersiz değiştirebilir (bu domain için CT loglarında
    // Let's Encrypt + Google Trust Services + Sectigo üçü de görüldü). Cloudflare pinlemeyi resmen
    // desteklemiyor (HPKP kaldırıldı), OWASP de pin seti uzaktan güncellenemiyorsa önermiyor. Sistem
    // trust store'una (network_security_config.xml) bırakılır; edge sertifika zinciri sunucu
    // tarafında izlenir ve CA değişiminde alarm üretir — istemciyi bloklamaz.
    private val client = OkHttpClient.Builder()
        .addInterceptor(LocaleHeaderInterceptor())
        .addInterceptor(NetworkRetryInterceptor())
        .addInterceptor(logging)
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)    // 0 = sonsuz — sunucu cevap verene kadar bekle
        .writeTimeout(0, TimeUnit.SECONDS)   // 0 = sonsuz — büyük payload yükleme
        .build()

    val api: KimlikApi by lazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .addConverterFactory(GsonConverterFactory.create())
            .client(client)
            .build()
            .create(KimlikApi::class.java)
    }
}
