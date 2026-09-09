package com.example.carrotnavi

data class SearchHistoryItem(
    val place_name: String,
    val road_address_name: String = "",
    val address_name: String = "",
    val x: String,
    val y: String,
    val timestamp: Long = System.currentTimeMillis()
) {
    fun toKakaoDocument(): KakaoDocument = KakaoDocument(
        place_name = place_name,
        road_address_name = road_address_name,
        address_name = address_name,
        x = x,
        y = y
    )

    companion object {
        fun fromKakaoDocument(doc: KakaoDocument): SearchHistoryItem = SearchHistoryItem(
            place_name = doc.place_name,
            road_address_name = doc.road_address_name,
            address_name = doc.address_name,
            x = doc.x,
            y = doc.y,
            timestamp = System.currentTimeMillis()
        )
    }
}
