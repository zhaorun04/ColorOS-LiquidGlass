plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "top.liquidglass.vector"
    compileSdk = 37
    defaultConfig {
        applicationId = "top.liquidglass.vector"
        minSdk = 28
        targetSdk = 28
        versionCode = 1920529104
        versionName = "1.9.205.288.104"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }
}

dependencies {
    compileOnly(project(":xposed-stubs"))
    implementation(project(":backdrop"))
    implementation(libs.kyant.shapes)
    implementation(libs.androidx.activity.compose)
    implementation("androidx.lifecycle:lifecycle-runtime:2.9.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel:2.9.4")
    implementation("androidx.savedstate:savedstate:1.3.3")
    implementation(libs.compose.foundation)
    implementation(libs.compose.ui)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
}
