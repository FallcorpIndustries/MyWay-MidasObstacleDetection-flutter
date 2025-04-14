// android/app/build.gradle.kts

// Imports nécessaires pour les classes Java standard utilisées ci-dessous
import java.util.Properties
import java.io.FileInputStream

plugins {
    id("com.android.application")
    id("kotlin-android")
    // Le plugin Flutter doit être appliqué après les plugins Android et Kotlin.
    id("dev.flutter.flutter-gradle-plugin")
}

// Fonction standard pour lire local.properties
// Utilise les types courts 'Properties' et 'FileInputStream' grâce aux imports ci-dessus
fun localProperties(): Properties {
    val properties = Properties()
    val localPropertiesFile = rootProject.file("local.properties")
    if (localPropertiesFile.exists()) {
        // Utilisation de FileInputStream sans le préfixe 'java.io.'
        properties.load(FileInputStream(localPropertiesFile))
    } else {
        // Optionnel : Log ou message si le fichier n'existe pas
         println("Warning: local.properties file not found.")
    }
    return properties
}

android {
    // Utilisation de l'opérateur d'affectation '='
    namespace = localProperties().getProperty("flutter.namespace", "com.example.assistive_perception_app") // Vérifiez que c'est le bon namespace
    compileSdk = 35 // Défaut commun, ou version que vous ciblez
    ndkVersion = "27.0.12077973" // Lecture depuis l'objet flutter

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8 // Défaut standard Flutter
        targetCompatibility = JavaVersion.VERSION_1_8 // Défaut standard Flutter
    }

    kotlinOptions {
        jvmTarget = "1.8" // Défaut standard Flutter
    }

    // sourceSets { // Décommentez et ajustez si votre code Kotlin/Java n'est pas dans le dossier par défaut
    //     getByName("main") {
    //          java.srcDirs("src/main/kotlin")
    //     }
    // }

    defaultConfig {
        applicationId = localProperties().getProperty("flutter.applicationId", "com.example.assistive_perception_app") // Vérifiez que c'est le bon ID
        // Utilisation de l'opérateur d'affectation '=' pour les entiers
        minSdk = 26         // Votre minSdkVersion requis
        targetSdk = 34      // Votre targetSdkVersion
        versionCode = 1
        versionName = "1.0" // Les String utilisent aussi '='
    }

    // Configuration de aaptOptions pour ne pas compresser tflite
    aaptOptions {
        // Syntaxe correcte pour ajouter à la liste noCompress en Kotlin DSL
        noCompress.add("tflite")
        noCompress.add("lite")
    }

    buildTypes {
        release {
            // TODO: Ajoutez votre propre config de signature pour le build release.
            // Utilise la config debug pour que 'flutter run --release' fonctionne pour l'instant.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    // Configuration de la compilation native externe avec CMake
    externalNativeBuild {
        cmake {
            // Utilisation de l'opérateur d'affectation '=' et de la fonction file()
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.31.6" // Ou la version CMake installée/requise (ex: "3.22.1", "3.31.6")
        }
    }
}

// Bloc Flutter standard
flutter {
    source = "../.."
}

// Bloc de dépendances standard (généralement à la fin)
dependencies {
    // Ligne souvent présente dans les projets Kotlin pour gérer les versions
    implementation(platform("org.jetbrains.kotlin:kotlin-bom:1.9.0")) // Version exemple, peut nécessiter ajustement
    implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8")
    // Aucune autre dépendance ajoutée par défaut par Flutter ici normalement
}