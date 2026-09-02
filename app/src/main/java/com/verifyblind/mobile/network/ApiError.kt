package com.verifyblind.mobile.network

import com.google.gson.Gson
import okhttp3.ResponseBody

data class ApiError(val error: String?, val code: String?)

object ApiErrorParser {
    private val gson = Gson()

    fun parse(body: ResponseBody?): ApiError? = try {
        body?.string()?.takeIf { it.isNotBlank() }?.let { gson.fromJson(it, ApiError::class.java) }
    } catch (_: Exception) {
        null
    }
}
