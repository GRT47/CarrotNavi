package com.example.carrotnavi

data class BookmarkItem(
    val type: String, // "HOME", "OFFICE", "FAVORITE"
    val place_name: String,
    val road_address_name: String = "",
    val address_name: String = "",
    val x: String,
    val y: String,
    val timestamp: Long = System.currentTimeMillis()
) {
    fun toKakaoDocument(): KakaoDocument {
        return KakaoDocument(
            place_name = place_name,
            road_address_name = road_address_name,
            address_name = address_name,
            x = x,
            y = y
        )
    }

    companion object {
        const val TYPE_HOME = "HOME"
        const val TYPE_OFFICE = "OFFICE"
        const val TYPE_FAVORITE = "FAVORITE"

        fun fromKakaoDocument(doc: KakaoDocument, type: String): BookmarkItem {
            return BookmarkItem(
                type = type,
                place_name = doc.place_name,
                road_address_name = doc.road_address_name,
                address_name = doc.address_name,
                x = doc.x,
                y = doc.y
            )
        }
    }
}
