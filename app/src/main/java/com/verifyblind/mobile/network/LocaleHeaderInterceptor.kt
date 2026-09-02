package com.verifyblind.mobile.network

import okhttp3.Interceptor
import okhttp3.Response
import java.util.Locale

class LocaleHeaderInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val lang = Locale.getDefault().language.let { if (it == "tr") "tr" else "en" }
        return chain.proceed(
            chain.request().newBuilder()
                .header("Accept-Language", lang)
                .build()
        )
    }
}
