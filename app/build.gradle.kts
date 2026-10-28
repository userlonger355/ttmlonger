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
        versionCode = 12
        versionName = "0.7.1"
    }

    // Постоянный ключ лежит в репозитории: каждая сборка подписана одним и тем же ключом,
    // поэтому новая версия ставится поверх старой, без удаления. Ключ не секретный: он нужен
    // только для того, чтобы Android принял обновление, а LSPatch всё равно переподписывает TikTok.
    signingConfigs {
        create("ttm") {
            storeFile = file("ttmlonger.jks")
            storePassword = "ttmlonger"
            keyAlias = "ttmlonger"
            keyPassword = "ttmlonger"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("ttm")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("ttm")
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
