package com.example.carrotnavi

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData

data class TrafficSignalData(
    val isVisible: Boolean,
    val isRed: Boolean,
    val isGreen: Boolean,
    val isLeft: Boolean,
    val remainTime: Int
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
        if (isTestMode) return
        applySignal(isVisible, isRed, isGreen, isLeft, remainTime)
    }

    private fun applySignal(
        isVisible: Boolean,
        isRed: Boolean,
        isGreen: Boolean,
        isLeft: Boolean,
        remainTime: Int
    ) {
        val shouldShow = isVisible && (isRed || isGreen || isLeft || remainTime > 0)
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
                isGreen = isGreen,
                isLeft = isLeft,
                remainTime = remainTime
            )
        )
    }

    fun startTestMode(durationMs: Long = 10000) {
        isTestMode = true
        applySignal(
            isVisible = true,
            isRed = true,
            isGreen = false,
            isLeft = false,
            remainTime = 15
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
