package com.example.tmapbridge

import kotlinx.coroutines.flow.MutableStateFlow

data class DriveData(
    val speed: Int = 0,
    val speedLimit: Int = 0,
    val sdiType: Int = -1, // 0~: camera types, 22: speed bump
    val sdiDist: Int = -1
)

enum class AuthStatus {
    LOADING, SUCCESS, FAILED
}

object TmapDataManager {
    val isDriving = MutableStateFlow(false)
    val authStatus = MutableStateFlow(AuthStatus.LOADING)
    val driveData = MutableStateFlow(DriveData())
    val satelliteCount = MutableStateFlow(0)
    
    val appLogs = MutableStateFlow<List<String>>(emptyList())
    
    fun addLog(msg: String) {
        val currentLogs = appLogs.value.toMutableList()
        currentLogs.add(0, msg)
        // Keep only the latest 100 logs to prevent memory issues
        if (currentLogs.size > 100) {
            currentLogs.removeAt(currentLogs.size - 1)
        }
        appLogs.value = currentLogs
    }
}
