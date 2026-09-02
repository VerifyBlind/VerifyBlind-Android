package com.verifyblind.mobile.data

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class PartnerItem(
    val id: String,
    val name: String,
    val logoUrl: String? = null,
    val logoBase64: String? = null,
    val lastUpdated: Long = 0L
)

object PartnerManager {
    private const val PREFS_NAME = "VerifyBlind_Partners"
    private const val KEY_PARTNERS = "partners_json"
    
    private val _partners = MutableStateFlow<Map<String, PartnerItem>>(emptyMap())
    val partners: StateFlow<Map<String, PartnerItem>> = _partners.asStateFlow()
    
    private lateinit var prefs: SharedPreferences
    private val gson = Gson()
    
    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        loadFromPrefs()
    }
    
    private fun loadFromPrefs() {
        val json = prefs.getString(KEY_PARTNERS, null)
        if (json != null) {
            try {
                val type = object : TypeToken<Map<String, PartnerItem>>() {}.type
                val map: Map<String, PartnerItem> = gson.fromJson(json, type)
                _partners.value = map
            } catch (e: Exception) {
                // persistent error handling
            }
        }
    }
    
    fun savePartner(item: PartnerItem) {
        val currentMap = _partners.value.toMutableMap()
        currentMap[item.id] = item
        _partners.value = currentMap
        
        val json = gson.toJson(currentMap)
        prefs.edit().putString(KEY_PARTNERS, json).apply()
    }
    
    fun getPartner(id: String): PartnerItem? {
        return _partners.value[id]
    }
}
