package com.example.carrotnavi

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData

data class RouteInfo(
    val szGoalName: String = "",
    val nGoPosDist: Int = 0,
    val nGoPosTime: Int = 0,
    val activeNavi: String = "tmap"
)

object RouteInfoRepository {
    private val _routeInfo = MutableLiveData(RouteInfo())
    val routeInfo: LiveData<RouteInfo> = _routeInfo

    fun updateRouteInfo(szGoalName: String, nGoPosDist: Int, nGoPosTime: Int, activeNavi: String) {
        _routeInfo.postValue(RouteInfo(szGoalName, nGoPosDist, nGoPosTime, activeNavi))
    }
    
    fun getRouteInfo(): RouteInfo {
        return _routeInfo.value ?: RouteInfo()
    }
}
