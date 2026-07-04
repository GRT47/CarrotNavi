package com.example.carrotnavi

import com.kakaomobility.knsdk.guidance.knguidance.KNGuidance

object ReflectionTest {
    fun getMethods(): List<String> {
        return KNGuidance::class.java.methods.map { it.name }
    }
}
