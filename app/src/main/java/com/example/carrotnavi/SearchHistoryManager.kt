package com.example.carrotnavi

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

object SearchHistoryManager {
    private const val PREFS_NAME = "CarrotNaviSearchHistory"
    private const val KEY_HISTORY = "SEARCH_HISTORY_LIST"
    private const val MAX_HISTORY_COUNT = 30

    private val gson = Gson()

    @Synchronized
    fun getHistory(context: Context): MutableList<SearchHistoryItem> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_HISTORY, null) ?: return mutableListOf()
        return try {
            val type = object : TypeToken<MutableList<SearchHistoryItem>>() {}.type
            gson.fromJson(json, type) ?: mutableListOf()
        } catch (e: Exception) {
            mutableListOf()
        }
    }

    @Synchronized
    fun addHistory(context: Context, item: SearchHistoryItem) {
        if (item.place_name.isBlank() && item.road_address_name.isBlank() && item.address_name.isBlank()) {
            return
        }

        val list = getHistory(context)
        // Remove existing item with identical coordinate or identical name
        list.removeAll {
            (it.x == item.x && it.y == item.y) ||
            (it.place_name.isNotEmpty() && it.place_name == item.place_name)
        }

        // Add to the top
        list.add(0, item)

        // Limit size
        if (list.size > MAX_HISTORY_COUNT) {
            val trimmedList = list.take(MAX_HISTORY_COUNT).toMutableList()
            saveList(context, trimmedList)
        } else {
            saveList(context, list)
        }
    }

    @Synchronized
    fun removeHistory(context: Context, item: SearchHistoryItem) {
        val list = getHistory(context)
        val removed = list.removeAll {
            (it.x == item.x && it.y == item.y) && it.place_name == item.place_name
        }
        if (removed) {
            saveList(context, list)
        }
    }

    @Synchronized
    fun clearHistory(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().remove(KEY_HISTORY).apply()
    }

    private fun saveList(context: Context, list: List<SearchHistoryItem>) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = gson.toJson(list)
        prefs.edit().putString(KEY_HISTORY, json).apply()
    }
}
