package com.example.carrotnavi

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData

object LaneDataRepository {
    private val _observableLaneGuide = MutableLiveData<LaneGuideData?>()
    val observableLaneGuide: LiveData<LaneGuideData?> get() = _observableLaneGuide

    /**
     * Tmap RGData 또는 ObservableLaneData로부터 추출한 차선 정보 업데이트
     */
    fun updateLaneData(
        bLane: Boolean,
        laneCount: Int,
        laneDist: Int,
        turnInfo: IntArray?,
        available: IntArray?,
        etcInfo: IntArray? = null
    ) {
        if (!bLane || laneCount <= 0 || turnInfo == null || turnInfo.isEmpty()) {
            if (_observableLaneGuide.value?.isVisible == true) {
                _observableLaneGuide.postValue(
                    _observableLaneGuide.value?.copy(isVisible = false)
                )
            }
            return
        }

        val effectiveCount = minOf(laneCount, turnInfo.size)
        val laneItems = mutableListOf<LaneItem>()

        for (i in 0 until effectiveCount) {
            val code = turnInfo[i]
            val turnType = LaneTurnParser.parse(code)
            
            // available 정보가 있으면 1인 경우 추천, 없으면 모든 차선을 활성화 상태로 표시
            val isAvail = if (available != null && i < available.size) {
                available[i] == 1
            } else {
                true
            }

            val isBus = if (etcInfo != null && i < etcInfo.size) {
                etcInfo[i] > 0
            } else {
                false
            }

            laneItems.add(
                LaneItem(
                    laneNumber = i + 1,
                    turnType = turnType,
                    isAvailable = isAvail,
                    isBusLane = isBus
                )
            )
        }

        val guideData = LaneGuideData(
            isVisible = true,
            distance = laneDist,
            laneCount = effectiveCount,
            lanes = laneItems
        )

        _observableLaneGuide.postValue(guideData)
    }

    fun clear() {
        if (_observableLaneGuide.value != null) {
            _observableLaneGuide.postValue(null)
        }
    }
}
