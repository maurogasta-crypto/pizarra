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
        versionName = "pizarra-8"
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
    testOptions {
        unitTests.isIncludeAndroidResources = true
        // Robolectric baja su Android por su cuenta y el proxy de una sesión
        // lo frena (429): lo baja Gradle (abajo) y Robolectric lo usa sin red.
        unitTests.all {
            it.systemProperty("robolectric.offline", "true")
            it.systemProperty("robolectric.dependency.dir", layout.buildDirectory.dir("robolectric").get().asFile.path)
            it.dependsOn("copiarAndroidRobolectric")
        }
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
    // pizarra-3: abrir la app de verdad en el banco (sin teléfono) y tocar sus
    // botones. Sólo para las pruebas: no entra al APK.
    testImplementation("org.robolectric:robolectric:4.14.1")
}

// El Android que corre Robolectric en el banco (sdk 35), bajado por Gradle.
val androidRobolectric: Configuration by configurations.creating
dependencies { androidRobolectric("org.robolectric:android-all-instrumented:15-robolectric-12650502-i7") }
tasks.register<Copy>("copiarAndroidRobolectric") {
    from(androidRobolectric)
    into(layout.buildDirectory.dir("robolectric"))
}
