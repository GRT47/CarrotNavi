package com.example.carrotnavi

import android.os.Bundle
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import org.json.JSONObject

object KakaoSdiRepository {
    private val _observableKakaoData = MutableLiveData<Bundle>()
    val observableKakaoData: LiveData<Bundle> get() = _observableKakaoData

    private val currentBundle = Bundle().apply { putString("navitype", "kakao") }

    fun updateLocation(speed: Int, roadName: String, roadLimitSpeed: Int, rawData: String = "", tbtDist: Int = -1, tbtTurnType: Int = -1, tbtText: String = "", lat: Double = 0.0, lon: Double = 0.0) {
        val tbtJson = JSONObject()
        tbtJson.put("szPosRoadName", roadName)
        if (tbtDist >= 0) {
            tbtJson.put("nTBTDist", tbtDist)
            tbtJson.put("nTBTTurnType", tbtTurnType)
            tbtJson.put("szTBTMainText", tbtText)
        }
        currentBundle.putString("tbtInfo", tbtJson.toString())
        currentBundle.putInt("roadLimitSpeed", roadLimitSpeed)
        currentBundle.putString("rawData", rawData)
        currentBundle.putInt("speed", speed)
        currentBundle.putDouble("lat", lat)
        currentBundle.putDouble("lon", lon)
        _observableKakaoData.postValue(currentBundle.clone() as Bundle)
    }

    fun updateRouteInfo(remainDist: Int, remainTime: Int, goalName: String = "", goalPosX: Double = 0.0, goalPosY: Double = 0.0) {
        currentBundle.putInt("nGoPosDist", remainDist)
        currentBundle.putInt("nGoPosTime", remainTime)
        currentBundle.putString("szGoalName", goalName)
        currentBundle.putDouble("goalPosX", goalPosX)
        currentBundle.putDouble("goalPosY", goalPosY)
        _observableKakaoData.postValue(currentBundle.clone() as Bundle)
    }

    fun updateTbt(dist: Int, turnType: Int) {
        // TBT is currently mapped inside firstSDIInfo by UdpSenderService when we pass nSdiDist etc.
        // Or we can just set them in firstSDIInfo here
    }

    // Maps KNSafety to Tmap's firstSDIInfo JSON structure
    fun updateSafeties(roadLimitSpeed: Int, sdiType1: Int, speedLimit1: Int, dist1: Int, isBlock1: Boolean, blockAvgSpeed: Int, sdiType2: Int, speedLimit2: Int, dist2: Int) {
        currentBundle.putInt("limitSpeed", roadLimitSpeed)
        
        if (sdiType1 > 0) {
            val firstSdi = JSONObject()
            firstSdi.put("nSdiType", sdiType1)
            firstSdi.put("nSdiSpeedLimit", speedLimit1)
            firstSdi.put("nSdiDist", dist1)
            firstSdi.put("bSdiBlockSection", isBlock1)
            if (isBlock1) {
                firstSdi.put("nSdiBlockAverageSpeed", blockAvgSpeed)
            }
            currentBundle.putString("firstSDIInfo", firstSdi.toString())
        } else {
            currentBundle.remove("firstSDIInfo")
        }

        if (sdiType2 > 0) {
            val secondSdi = JSONObject()
            secondSdi.put("nSdiType", sdiType2)
            secondSdi.put("nSdiSpeedLimit", speedLimit2)
            secondSdi.put("nSdiDist", dist2)
            currentBundle.putString("secondSDIInfo", secondSdi.toString())
        } else {
            currentBundle.remove("secondSDIInfo")
        }
        
        _observableKakaoData.postValue(currentBundle.clone() as Bundle)
    }
}
