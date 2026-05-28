package com.verifyblind.mobile.api

import com.verifyblind.mobile.BuildConfig
import com.verifyblind.mobile.network.LocaleHeaderInterceptor
import okhttp3.CertificatePinner
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

    // Certificate Pinning: api.verifyblind.com
    // Pin 1: Mevcut leaf sertifika (19 Mayıs 2026'ya kadar geçerli)
    // Pin 2: Let's Encrypt Root X1 — backup pin (leaf yenilendiğinde devreye girer)
    // DEBUG modda pinning atlanır — local Docker bağlantısı için
    private val certificatePinner = CertificatePinner.Builder()
        .add("api.verifyblind.com", BuildConfig.CERT_PIN_1)
        .add("api.verifyblind.com", BuildConfig.CERT_PIN_2)
        .build()

    private val client = OkHttpClient.Builder()
        .apply { if (!BuildConfig.USE_LOCAL_API) certificatePinner(certificatePinner) }
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
