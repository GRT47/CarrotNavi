package com.example.carrotnavi

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData

data class TrafficSignalData(
    val isVisible: Boolean,
    val isRed: Boolean,
    val isYellow: Boolean = false,
    val isGreen: Boolean,
    val isLeft: Boolean,
    val remainTime: Int,
    val distance: Int = 0
)

object TrafficSignalRepository {
    private val _observableSignal = MutableLiveData<TrafficSignalData?>()
    val observableSignal: LiveData<TrafficSignalData?> get() = _observableSignal
    private var isTestMode = false

    fun updateSignal(
        isVisible: Boolean,
        isRed: Boolean,
        isGreen: Boolean,
        isLeft: Boolean,
        remainTime: Int
    ) {
        updateSignal(isVisible, isRed, false, isGreen, isLeft, remainTime, 0)
    }

    fun updateSignal(
        isVisible: Boolean,
        isRed: Boolean,
        isYellow: Boolean,
        isGreen: Boolean,
        isLeft: Boolean,
        remainTime: Int,
        distance: Int = 0
    ) {
        if (isTestMode) return
        applySignal(isVisible, isRed, isYellow, isGreen, isLeft, remainTime, distance)
    }

    private fun applySignal(
        isVisible: Boolean,
        isRed: Boolean,
        isYellow: Boolean,
        isGreen: Boolean,
        isLeft: Boolean,
        remainTime: Int,
        distance: Int
    ) {
        val shouldShow = isVisible && (isRed || isYellow || isGreen || isLeft || remainTime > 0)
        if (!shouldShow) {
            if (_observableSignal.value?.isVisible == true) {
                _observableSignal.postValue(_observableSignal.value?.copy(isVisible = false))
            }
            return
        }

        _observableSignal.postValue(
            TrafficSignalData(
                isVisible = true,
                isRed = isRed,
                isYellow = isYellow,
                isGreen = isGreen,
                isLeft = isLeft,
                remainTime = remainTime,
                distance = distance
            )
        )
    }

    fun startTestMode(durationMs: Long = 10000) {
        isTestMode = true
        applySignal(
            isVisible = true,
            isRed = true,
            isYellow = false,
            isGreen = false,
            isLeft = false,
            remainTime = 15,
            distance = 120
        )
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            isTestMode = false
            clear()
        }, durationMs)
    }

    fun clear() {
        if (_observableSignal.value != null) {
            _observableSignal.postValue(null)
        }
    }
}
