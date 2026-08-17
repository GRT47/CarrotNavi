package com.example.carrotnavi.v2

import android.app.Presentation
import android.content.Context
import android.os.Bundle
import android.view.Display
import android.util.Log
import com.example.carrotnavi.R
import com.kakaomobility.knsdk.ui.view.KNNaviView

class MapPresentation(outerContext: Context, display: Display) : Presentation(outerContext, display) {
    private val TAG = "MapPresentation"
    private var naviView: KNNaviView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.presentation_map)
        
        try {
            naviView = findViewById(R.id.presentation_navi_view)
            Log.d(TAG, "MapPresentation created and layout inflated")
            
            // Initialization of KNSDK map on the secondary display might fail if it uses a Singleton 
            // that doesn't support multiple contexts or if it strictly requires an Activity context.
            // We just instantiate it in the XML for this spike to see if it works.
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize MapPresentation", e)
        }
    }
}
