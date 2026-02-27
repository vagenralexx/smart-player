plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.exemplo.musicplayer"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.exemplo.musicplayer"
        minSdk = 24
        targetSdk = 35
        versionCode = 2
        versionName = "1.1.0"   // <-- deve coincidir com a tag do GitHub (v1.1.0)
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Ler credenciais do keystore em local (ficheiro) ou no CI (variáveis de ambiente)
    val keystoreFile   = rootProject.file("app/smartplayer.jks")
    val storePass      = System.getenv("KEYSTORE_PASSWORD") ?: "SmartPlayer2026!"
    val releaseAlias   = System.getenv("KEY_ALIAS")         ?: "smartplayer"
    val keyPassEnv     = System.getenv("KEY_PASSWORD")      ?: "SmartPlayer2026!"

    signingConfigs {
        create("release") {
            if (keystoreFile.exists()) {
                storeFile          = keystoreFile
                storePassword      = storePass
                keyAlias           = releaseAlias
                keyPassword        = keyPassEnv
                enableV1Signing    = true   // compatibilidade Android < 7
                enableV2Signing    = true   // Android 7+
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    // Material Design Components — obrigatório para o tema XML (Theme.Material3.*)
    implementation(libs.material)

    // AndroidX Core e Lifecycle
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)

    // Compose BOM — garante versões compatíveis entre si
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)

    // Media3 — motor de reprodução
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.session)
    implementation(libs.androidx.media3.ui)

    // Coil — carrega capas de álbum de forma eficiente
    implementation(libs.coil.compose)

    // Room — banco de dados local para playlists e favoritos
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // Palette — cores dinâmicas a partir da capa do álbum
    implementation(libs.androidx.palette)

    // Coroutines
    implementation(libs.kotlinx.coroutines.android)

    debugImplementation(libs.androidx.ui.tooling)
}
