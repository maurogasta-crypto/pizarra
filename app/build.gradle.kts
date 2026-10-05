import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// La firma propia llega por GitHub Secrets (FIRMA_*), igual que en la app de
// la Hilux: el workflow escribe `key.properties` y el keystore. Si no están,
// se firma con la clave de depuración y el workflow lo avisa. El keystore no
// entra nunca a este repositorio, que es público.
val firma = Properties().apply {
    val f = rootProject.file("key.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.maurogasta.pizarra"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.maurogasta.pizarra"
        minSdk = 26
        targetSdk = 35
        // El número de corrida de GitHub: sube en cada tanda y nunca retrocede,
        // que es lo que Android exige para instalar ENCIMA de la anterior.
        versionCode = (project.findProperty("codigoVersion") as String?)?.toIntOrNull() ?: 1
        versionName = "pizarra-2"
    }

    signingConfigs {
        if (firma.getProperty("storeFile") != null) {
            create("propia") {
                storeFile = rootProject.file("app/" + firma.getProperty("storeFile"))
                storePassword = firma.getProperty("storePassword")
                keyAlias = firma.getProperty("keyAlias")
                keyPassword = firma.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("propia") ?: signingConfigs.getByName("debug")
        }
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
    // Sin dependencias en la app, a propósito: red con HttpURLConnection y
    // JSON con org.json, que vienen con Android. Menos cosas que actualizar
    // y nada que bajar en el teléfono.
    testImplementation("junit:junit:4.13.2")
    // En las pruebas de la JVM org.json es un esqueleto vacío: ésta es la real.
    testImplementation("org.json:json:20240303")
}
