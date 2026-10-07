package com.example.carrotnavi

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.content.res.Resources
import androidx.appcompat.app.AppCompatActivity

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

    companion object {
        const val PREF_KEY_DPI_SCALE = "CUSTOM_DPI_SCALE"
        const val DEFAULT_DPI_SCALE = 1.0f

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
        }

        fun restartApp(context: Context) {
            try {
                val packageManager = context.packageManager
                val intent = packageManager.getLaunchIntentForPackage(context.packageName)
                if (intent != null) {
                    val componentName = intent.component
                    val restartIntent = Intent.makeRestartActivityTask(componentName)
                    context.startActivity(restartIntent)
                    Runtime.getRuntime().exit(0)
                }
            } catch (e: Exception) {
                e.printStackTrace()
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
