package com.example.carrotnavi

import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity
import java.util.Collections

open class BaseActivity : AppCompatActivity() {

    override fun attachBaseContext(newBase: Context) {
        val customContext = applyCustomDpi(newBase)
        super.attachBaseContext(customContext)
    }

    override fun applyOverrideConfiguration(overrideConfiguration: Configuration?) {
        if (overrideConfiguration != null) {
            val scale = getDpiScale(this)
            if (scale in 0.5f..2.0f && scale != 1.0f) {
                val defaultDpi = resources.displayMetrics.densityDpi
                overrideConfiguration.densityDpi = (defaultDpi * scale).toInt()
            }
        }
        super.applyOverrideConfiguration(overrideConfiguration)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        activeActivities.add(this)
    }

    override fun onDestroy() {
        activeActivities.remove(this)
        super.onDestroy()
    }

    companion object {
        const val PREF_KEY_DPI_SCALE = "CUSTOM_DPI_SCALE"
        const val DEFAULT_DPI_SCALE = 1.0f

        private val activeActivities = Collections.synchronizedSet(HashSet<BaseActivity>())

        fun getDpiScale(context: Context): Float {
            val prefs = context.getSharedPreferences("CarrotNaviPrefs", Context.MODE_PRIVATE)
            return prefs.getFloat(PREF_KEY_DPI_SCALE, DEFAULT_DPI_SCALE)
        }

        fun setDpiScale(context: Context, scale: Float) {
            val prefs = context.getSharedPreferences("CarrotNaviPrefs", Context.MODE_PRIVATE)
            prefs.edit().putFloat(PREF_KEY_DPI_SCALE, scale).apply()
        }

        fun recreateAllActivities() {
            Handler(Looper.getMainLooper()).post {
                val list = synchronized(activeActivities) { activeActivities.toList() }
                list.forEach { activity ->
                    try {
                        if (!activity.isFinishing && !activity.isDestroyed) {
                            activity.recreate()
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
        }

        fun applyCustomDpi(context: Context): Context {
            val scale = getDpiScale(context)
            if (scale !in 0.5f..2.0f || scale == 1.0f) {
                return context
            }
            return try {
                val config = Configuration(context.resources.configuration)
                val metrics = context.resources.displayMetrics
                val targetDpi = (metrics.densityDpi * scale).toInt()
                config.densityDpi = targetDpi

                val newContext = context.createConfigurationContext(config)
                val newMetrics = newContext.resources.displayMetrics
                newMetrics.density = metrics.density * scale
                newMetrics.scaledDensity = metrics.scaledDensity * scale
                newMetrics.densityDpi = targetDpi
                newContext
            } catch (e: Exception) {
                e.printStackTrace()
                context
            }
        }
    }
}
