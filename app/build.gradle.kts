plugins {
    id("com.android.application")
}

android {
    namespace = "es.canteramadrid.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "es.canteramadrid.app"
        minSdk = 24
        targetSdk = 35
        // GitHub Actions pasa el número de compilación: cada APK nueva se instala encima de la anterior.
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = "0.1." + (System.getenv("GITHUB_RUN_NUMBER") ?: "0")
    }

    signingConfigs {
        create("fija") {
            // Clave fija en el repo para poder actualizar sin desinstalar.
            // Antes de publicar en Google Play, cámbiala por una clave privada guardada como secreto.
            storeFile = rootProject.file("keystore/cantera.keystore")
            storePassword = "android"
            keyAlias = "cantera"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("fija")
        }
        debug {
            signingConfig = signingConfigs.getByName("fija")
        }
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("androidx.webkit:webkit:1.12.1")
    implementation("androidx.core:core:1.13.1")
}
