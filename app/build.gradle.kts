plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// En GitHub Actions el número de ejecución sube solo en cada push: se usa como versionCode.
// El actualizador de la app compara ese número con el del último Release.
val runNumber: Int? = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull()

// La llave de firma llega por variables de entorno (Secrets de GitHub); nunca va en el repo.
val keystorePath: String? = System.getenv("KEYSTORE_PATH")

android {
    namespace = "com.tvcampip"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.tvcampip"
        minSdk = 26
        targetSdk = 34
        versionCode = runNumber ?: 4
        versionName = if (runNumber != null) "0.5.$runNumber" else "0.5.0-local"
    }

    signingConfigs {
        if (keystorePath != null) {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (keystorePath != null) {
                signingConfig = signingConfigs.getByName("release")
            }
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

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-exoplayer-rtsp:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")
    implementation("com.google.zxing:core:3.5.3")
}
