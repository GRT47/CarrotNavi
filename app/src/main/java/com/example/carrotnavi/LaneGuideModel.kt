package com.example.carrotnavi

enum class LaneTurnType(val symbol: String, val description: String) {
    STRAIGHT("↑", "직진"),
    LEFT("←", "좌회전"),
    RIGHT("→", "우회전"),
    STRAIGHT_LEFT("↰", "직진/좌회전"),
    STRAIGHT_RIGHT("↱", "직진/우회전"),
    LEFT_RIGHT("↔", "좌/우회전"),
    UTURN("↶", "유턴"),
    LEFT_UTURN("↶←", "좌회전/유턴"),
    UNKNOWN("↑", "일반")
}

data class LaneItem(
    val laneNumber: Int,       // 1부터 시작하는 차선 번호 (좌측 1차로 기준)
    val turnType: LaneTurnType,
    val isAvailable: Boolean,  // 현재 추천 주행 차선인지 여부
    val isBusLane: Boolean = false
)

data class LaneGuideData(
    val isVisible: Boolean,
    val distance: Int,         // 교차로까지 남은 거리 (m)
    val laneCount: Int,        // 총 차선 수
    val lanes: List<LaneItem>
)

object LaneTurnParser {
    // Tmap LaneInfoData 및 RGConstant 기반 코드 집합
    private val GO_STRAIGHT_CODES = setOf(3, 4, 5, 8, 9, 52, 53, 55, 248)
    private val LEFT_TURN_CODES = setOf(12, 14, 16, 17, 44, 52, 69, 75, 76, 102, 105, 112, 115, 118)
    private val RIGHT_TURN_CODES = setOf(13, 15, 18, 19, 43, 53, 70, 73, 74, 101, 104, 111, 114, 117, 123, 124)
    private val UTURN_CODES = setOf(16, 44, 75, 76)

    fun parse(code: Int): LaneTurnType {
        val isStraight = code in GO_STRAIGHT_CODES
        val isLeft = code in LEFT_TURN_CODES
        val isRight = code in RIGHT_TURN_CODES
        val isUturn = code in UTURN_CODES

        return when {
            isLeft && isUturn -> LaneTurnType.LEFT_UTURN
            isUturn && !isStraight -> LaneTurnType.UTURN
            isStraight && isLeft -> LaneTurnType.STRAIGHT_LEFT
            isStraight && isRight -> LaneTurnType.STRAIGHT_RIGHT
            isLeft && isRight -> LaneTurnType.LEFT_RIGHT
            isLeft -> LaneTurnType.LEFT
            isRight -> LaneTurnType.RIGHT
            isStraight -> LaneTurnType.STRAIGHT
            else -> LaneTurnType.STRAIGHT
        }
    }
}
