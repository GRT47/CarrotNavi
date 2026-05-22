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
}
