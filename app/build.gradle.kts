plugins {
    id("com.android.application")
}

android {
    namespace = "com.sinanjams.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.sinanjams.app"
        minSdk = 24
        targetSdk = 35
        versionCode = 10
        versionName = "2.1.0"
    }

    signingConfigs {
        create("release") {
            storeFile = rootProject.file("keystore/altyazi-araci-release.jks")
            storePassword = "altyaziaraci"
            keyAlias = "altyazi-araci"
            keyPassword = "altyaziaraci"
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("release")
        }
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }
}
