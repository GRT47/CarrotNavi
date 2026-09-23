package com.example.carrotnavi

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData

object SdiDataRepository {
    val isNightMode = MutableLiveData<Boolean>(false)
    var lastTmapDetectedNight: Boolean = false

    fun applyThemeMode(context: android.content.Context, tmapNight: Boolean? = null) {
        if (tmapNight != null) {
            lastTmapDetectedNight = tmapNight
        }
        val prefs = context.getSharedPreferences("CarrotNaviPrefs", android.content.Context.MODE_PRIVATE)
        val mode = prefs.getString("MAP_THEME_MODE", "auto") ?: "auto"
        val effectiveIsNight = when (mode) {
            "day" -> false
            "night" -> true
            else -> lastTmapDetectedNight
        }
        if (isNightMode.value != effectiveIsNight) {
            isNightMode.postValue(effectiveIsNight)
        }
    }

    private val _observableRoadLimitSpeed = MutableLiveData<Int>()
    val observableRoadLimitSpeed: LiveData<Int> get() = _observableRoadLimitSpeed

    var sdiType: Int = 1
    var sdiSpeedLimit: Int = 80
    var sdiDistance: Int = 300
    var sdiBlockType: Int = 0
    var sdiBlockSpeed: Int = 0
    var sdiBlockDist: Int = 0

    fun updateRoadLimitSpeed(speed: Int) {
        if (speed >= 30 && _observableRoadLimitSpeed.value != speed) {
            _observableRoadLimitSpeed.postValue(speed)
        }
    }

    fun updateCurrentSdiState(
        limitSpeed: Int, type: Int, speedLimit: Int, distance: Int,
        blockType: Int, blockSpeed: Int, blockDist: Int
    ) {
        updateRoadLimitSpeed(limitSpeed)
        sdiType = type
        sdiSpeedLimit = speedLimit
        sdiDistance = distance
        sdiBlockType = blockType
        sdiBlockSpeed = blockSpeed
        sdiBlockDist = blockDist
    }
}
