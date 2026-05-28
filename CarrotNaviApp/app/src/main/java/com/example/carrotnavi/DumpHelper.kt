package com.example.carrotnavi

import android.util.Log

object DumpHelper {
    fun dump() {
        try {
            val audioClass = Class.forName("com.skt.tmap.engine.navigation.TmapNavigationAudio")
            for (m in audioClass.declaredMethods) {
                Log.e("DumpNavTest", "TmapNavigationAudio method: ${m.name}(${m.parameterTypes.joinToString { it.name }}) -> ${m.returnType.name}")
            }
            val innerClasses = audioClass.declaredClasses
            for (c in innerClasses) {
                Log.e("DumpNavTest", "TmapNavigationAudio inner class: ${c.name}")
                if (c.name.contains("AudioPlayCallback")) {
                    for (m in c.declaredMethods) {
                        Log.e("DumpNavTest", "AudioPlayCallback method: ${m.name}(${m.parameterTypes.joinToString { it.name }}) -> ${m.returnType.name}")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("DumpNavTest", "Dump failed", e)
        }
    }
}
