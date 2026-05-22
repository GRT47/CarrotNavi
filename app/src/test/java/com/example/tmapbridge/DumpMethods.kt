package com.example.tmapbridge

import com.tmapmobility.tmap.tmapsdk.ui.fragment.NavigationFragment
import org.junit.Test

class DumpMethods {
    @Test
    fun dump() {
        val methods = com.tmapmobility.tmap.tmapsdk.ui.util.TmapUISDK::class.java.methods
        for (m in methods) {
            println("METHOD: ${m.name}")
        }
        println("DONE DUMPING TmapUISDK")
    }
}
