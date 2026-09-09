package com.example.carrotnavi

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

object DestinationBookmarkManager {

    private const val PREFS_NAME = "CarrotNaviBookmarks"
    private const val KEY_HOME = "bookmark_home"
    private const val KEY_OFFICE = "bookmark_office"
    private const val KEY_FAVORITES = "bookmark_favorites"

    private val gson = Gson()

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    // Home
    fun getHome(context: Context): BookmarkItem? {
        val json = getPrefs(context).getString(KEY_HOME, null) ?: return null
        return try {
            gson.fromJson(json, BookmarkItem::class.java)
        } catch (e: Exception) {
            null
        }
    }

    fun setHome(context: Context, item: BookmarkItem) {
        val json = gson.toJson(item.copy(type = BookmarkItem.TYPE_HOME))
        getPrefs(context).edit().putString(KEY_HOME, json).apply()
    }

    fun clearHome(context: Context) {
        getPrefs(context).edit().remove(KEY_HOME).apply()
    }

    // Office
    fun getOffice(context: Context): BookmarkItem? {
        val json = getPrefs(context).getString(KEY_OFFICE, null) ?: return null
        return try {
            gson.fromJson(json, BookmarkItem::class.java)
        } catch (e: Exception) {
            null
        }
    }

    fun setOffice(context: Context, item: BookmarkItem) {
        val json = gson.toJson(item.copy(type = BookmarkItem.TYPE_OFFICE))
        getPrefs(context).edit().putString(KEY_OFFICE, json).apply()
    }

    fun clearOffice(context: Context) {
        getPrefs(context).edit().remove(KEY_OFFICE).apply()
    }

    // Favorites
    fun getFavorites(context: Context): List<BookmarkItem> {
        val json = getPrefs(context).getString(KEY_FAVORITES, null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<BookmarkItem>>() {}.type
            gson.fromJson(json, type) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun addFavorite(context: Context, item: BookmarkItem) {
        val list = getFavorites(context).toMutableList()
        // 중복 방지 (장소명과 좌표가 같으면 기존 것 제거 후 최신으로 추가)
        list.removeAll { it.place_name == item.place_name && it.x == item.x && it.y == item.y }
        list.add(0, item.copy(type = BookmarkItem.TYPE_FAVORITE, timestamp = System.currentTimeMillis()))
        saveFavorites(context, list)
    }

    fun removeFavorite(context: Context, item: BookmarkItem) {
        val list = getFavorites(context).toMutableList()
        list.removeAll { it.place_name == item.place_name && it.x == item.x && it.y == item.y }
        saveFavorites(context, list)
    }

    fun clearFavorites(context: Context) {
        getPrefs(context).edit().remove(KEY_FAVORITES).apply()
    }

    private fun saveFavorites(context: Context, list: List<BookmarkItem>) {
        val json = gson.toJson(list)
        getPrefs(context).edit().putString(KEY_FAVORITES, json).apply()
    }
}
