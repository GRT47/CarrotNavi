package com.example.carrotnavi

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
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
            val systemDpi = getSystemDpi()
            if (scale in 0.5f..2.0f && scale != 1.0f) {
                overrideConfiguration.densityDpi = (systemDpi * scale).toInt()
            } else {
                overrideConfiguration.densityDpi = systemDpi
            }
        }
        super.applyOverrideConfiguration(overrideConfiguration)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val scale = getDpiScale(this)
        updateResources(this, scale)
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

        fun getSystemDpi(): Int {
            return Resources.getSystem().configuration.densityDpi
        }

        fun getDpiScale(context: Context): Float {
            val prefs = context.getSharedPreferences("CarrotNaviPrefs", Context.MODE_PRIVATE)
            return prefs.getFloat(PREF_KEY_DPI_SCALE, DEFAULT_DPI_SCALE)
        }

        fun setDpiScale(context: Context, scale: Float) {
            val prefs = context.getSharedPreferences("CarrotNaviPrefs", Context.MODE_PRIVATE)
            prefs.edit().putFloat(PREF_KEY_DPI_SCALE, scale).apply()
            updateResources(context, scale)
        }

        fun updateResources(context: Context, scale: Float) {
            try {
                val systemDpi = getSystemDpi()
                val targetDpi = if (scale in 0.5f..2.0f && scale != 1.0f) {
                    (systemDpi * scale).toInt()
                } else {
                    systemDpi
                }

                val res = context.resources
                val config = Configuration(res.configuration)
                config.densityDpi = targetDpi
                val metrics = res.displayMetrics
                metrics.densityDpi = targetDpi
                metrics.density = targetDpi / 160.0f
                metrics.scaledDensity = metrics.density

                @Suppress("DEPRECATION")
                res.updateConfiguration(config, metrics)

                val appRes = context.applicationContext?.resources
                if (appRes != null && appRes !== res) {
                    val appConfig = Configuration(appRes.configuration)
                    appConfig.densityDpi = targetDpi
                    val appMetrics = appRes.displayMetrics
                    appMetrics.densityDpi = targetDpi
                    appMetrics.density = targetDpi / 160.0f
                    appMetrics.scaledDensity = appMetrics.density
                    @Suppress("DEPRECATION")
                    appRes.updateConfiguration(appConfig, appMetrics)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        fun recreateAllActivities(callerActivity: BaseActivity? = null) {
            Handler(Looper.getMainLooper()).post {
                val scale = if (callerActivity != null) getDpiScale(callerActivity) else 1.0f
                if (callerActivity != null) {
                    updateResources(callerActivity, scale)
                }

                val list = synchronized(activeActivities) { activeActivities.toList() }
                list.forEach { activity ->
                    try {
                        if (!activity.isFinishing && !activity.isDestroyed) {
                            updateResources(activity, scale)
                            activity.recreate()
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }

                if (callerActivity != null && !list.contains(callerActivity)) {
                    try {
                        if (!callerActivity.isFinishing && !callerActivity.isDestroyed) {
                            callerActivity.recreate()
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
                val systemDpi = getSystemDpi()
                val targetDpi = (systemDpi * scale).toInt()

                val config = Configuration(context.resources.configuration)
                config.densityDpi = targetDpi

                val newContext = context.createConfigurationContext(config)
                val newMetrics = newContext.resources.displayMetrics
                newMetrics.densityDpi = targetDpi
                newMetrics.density = targetDpi / 160.0f
                newMetrics.scaledDensity = newMetrics.density
                newContext
            } catch (e: Exception) {
                e.printStackTrace()
                context
            }
        }
    }
}
