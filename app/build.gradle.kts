plugins {
    id("com.android.application")
}

android {
    namespace = "dev.ttmlonger"
    compileSdk = 34

    defaultConfig {
        applicationId = "dev.ttmlonger.app"
        minSdk = 24
        targetSdk = 34
        versionCode = 3
        versionName = "0.3.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Подпись debug-ключом: APK ставится сразу, а LSPatch всё равно переподписывает TikTok.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        encoding = "UTF-8"
    }
}

dependencies {
    // Только для компиляции: в APK не попадает, настоящие классы даёт Xposed/LSPatch в рантайме.
    compileOnly(project(":xposed-stub"))
}
