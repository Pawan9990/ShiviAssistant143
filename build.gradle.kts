plugins {
    id("com.android.application")
    kotlin("android") version "1.9.0"  // Add explicit Kotlin version
}

android {
    compileSdk = 34
    namespace = "com.example.shiviassistant143"  // Add this line
    
    defaultConfig {
        applicationId = "com.example.shiviassistant143"
        minSdk = 21
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }
    
    // ... rest of your config
}
