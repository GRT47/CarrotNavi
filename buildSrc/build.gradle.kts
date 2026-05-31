plugins {
    `kotlin-dsl`
}

repositories {
    google()
    mavenCentral()
}

dependencies {
    implementation("com.android.tools.build:gradle:9.0.1")
    implementation("org.ow2.asm:asm:9.6")
}

gradlePlugin {
    plugins {
        create("audioFocusInterceptor") {
            id = "com.example.carrotnavi.audiofocus"
            implementationClass = "com.example.carrotnavi.plugin.AudioFocusInterceptorPlugin"
        }
    }
}
