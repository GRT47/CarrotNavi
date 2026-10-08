package com.example.carrotnavi

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData

data class CompassData(
    val angle: Int,
    val directionText: String
)

object CompassDataRepository {
    private val _observableCompass = MutableLiveData<CompassData>()
    val observableCompass: LiveData<CompassData> get() = _observableCompass

    fun updateAngle(angle: Int) {
        if (angle < 0) return
        val normalized = ((angle % 360) + 360) % 360
        val dir = getDirection(normalized)
        if (_observableCompass.value?.angle != normalized) {
            _observableCompass.postValue(CompassData(normalized, dir))
        }
    }

    private fun getDirection(angle: Int): String {
        return when (angle) {
            in 338..360, in 0..22 -> "N"
            in 23..67 -> "NE"
            in 68..112 -> "E"
            in 113..157 -> "SE"
            in 158..202 -> "S"
            in 203..247 -> "SW"
            in 248..292 -> "W"
            in 293..337 -> "NW"
            else -> "N"
        }
    }
}
