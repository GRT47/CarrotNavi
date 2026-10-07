package com.example.carrotnavi

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
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
        activeActivities.add(this)
    }

    override fun onResume() {
        super.onResume()
        applyFullscreen(isFullscreen(this))
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            applyFullscreen(isFullscreen(this))
        }
    }

    override fun onDestroy() {
        activeActivities.remove(this)
        super.onDestroy()
    }

    fun applyFullscreen(isFullscreen: Boolean) {
        val win = window ?: return
        val decor = win.decorView
        val controller = WindowCompat.getInsetsController(win, decor)
        if (isFullscreen) {
            // 상단 상태바 및 하단 내비게이션 바(소프트키) 숨김
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

            @Suppress("DEPRECATION")
            decor.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            )
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
            @Suppress("DEPRECATION")
            decor.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
        }
        ViewCompat.requestApplyInsets(decor)
    }

    companion object {
        const val PREF_KEY_DPI_SCALE = "CUSTOM_DPI_SCALE"
        const val DEFAULT_DPI_SCALE = 1.0f

        const val PREF_KEY_FULLSCREEN = "FULLSCREEN_MODE"
        const val DEFAULT_FULLSCREEN = false

        private val activeActivities = Collections.synchronizedSet(HashSet<BaseActivity>())

        fun isFullscreen(context: Context): Boolean {
            val prefs = context.getSharedPreferences("CarrotNaviPrefs", Context.MODE_PRIVATE)
            return prefs.getBoolean(PREF_KEY_FULLSCREEN, DEFAULT_FULLSCREEN)
        }

        fun setFullscreen(context: Context, enabled: Boolean) {
            val prefs = context.getSharedPreferences("CarrotNaviPrefs", Context.MODE_PRIVATE)
            prefs.edit().putBoolean(PREF_KEY_FULLSCREEN, enabled).apply()

            Handler(Looper.getMainLooper()).post {
                val list = synchronized(activeActivities) { activeActivities.toList() }
                list.forEach { activity ->
                    try {
                        if (!activity.isFinishing && !activity.isDestroyed) {
                            activity.applyFullscreen(enabled)
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
        }

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
