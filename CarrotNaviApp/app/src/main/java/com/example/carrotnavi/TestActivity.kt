package com.example.carrotnavi

import android.app.AppOpsManager
import android.content.Context
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity

class TestActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            val appOps = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            val setModeMethod = AppOpsManager::class.java.getMethod(
                "setMode", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, String::class.java, Int::class.javaPrimitiveType
            )
            setModeMethod.invoke(appOps, 32, android.os.Process.myUid(), packageName, AppOpsManager.MODE_IGNORED)
            Log.e("TestAppOps", "SUCCESS: setMode AppOpsManager.MODE_IGNORED")
        } catch (e: Exception) {
            Log.e("TestAppOps", "FAILED: " + e.message)
        }
    }
}
