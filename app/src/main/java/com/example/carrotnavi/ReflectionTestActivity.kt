package com.example.carrotnavi

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

class ReflectionTestActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val clazz = Class.forName("com.kakaomobility.knsdk.ui.view.KNNaviView_StateDelegate")
        for (m in clazz.methods) {
            android.util.Log.d("ReflectionTest", "Method: " + m.name)
        }
        finish()
    }
}
