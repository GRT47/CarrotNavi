package com.example.carrotnavi

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData

data class OpenpilotState(
    val carrot2: String = "",
    val ip: String = "",
    val trafficState: Int = 0,
    val xState: Int = 0,
    val active: Boolean = false,
    val vEgoKph: Int = 0,
    val vCruiseKph: Int = 0,
    val carcruiseSpeed: Double = 0.0,
    val logCarrot: String = "",
    val isOnroad: Boolean = false,
    val tbtDist: Double = 0.0,
    val sdiDist: Double = 0.0,
    val leftBlinker: Boolean = false,
    val rightBlinker: Boolean = false,
    val brakeLights: Boolean = false
)

object OpenpilotStateRepository {
    private val _state = MutableLiveData(OpenpilotState())
    val state: LiveData<OpenpilotState> get() = _state

    fun updateState(
        carrot2: String,
        ip: String,
        trafficState: Int,
        xState: Int,
        active: Boolean,
        vEgoKph: Int = 0,
        vCruiseKph: Int = 0,
        carcruiseSpeed: Double = 0.0,
        logCarrot: String = "",
        isOnroad: Boolean = false,
        tbtDist: Double = 0.0,
        sdiDist: Double = 0.0,
        leftBlinker: Boolean = false,
        rightBlinker: Boolean = false,
        brakeLights: Boolean = false
    ) {
        _state.postValue(
            OpenpilotState(
                carrot2 = carrot2,
                ip = ip,
                trafficState = trafficState,
                xState = xState,
                active = active,
                vEgoKph = vEgoKph,
                vCruiseKph = vCruiseKph,
                carcruiseSpeed = carcruiseSpeed,
                logCarrot = logCarrot,
                isOnroad = isOnroad,
                tbtDist = tbtDist,
                sdiDist = sdiDist,
                leftBlinker = leftBlinker,
                rightBlinker = rightBlinker,
                brakeLights = brakeLights
            )
        )
    }
}
