import java.util.Properties
import java.io.FileInputStream

// It's good practice to read sensitive information or API keys from local.properties
// This file is typically not checked into version control.
val localProperties = Properties()
val localPropertiesFile = rootProject.file("local.properties")
if (localPropertiesFile.exists()) {
    localProperties.load(FileInputStream(localPropertiesFile))
}

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    // Add other plugins if needed, e.g., for dependency injection or navigation
}




android {
    namespace = "com.example.depthperceptionapp" // Replace with your actual package name
    compileSdk = 35 // Use a recent SDK version

    defaultConfig {
        applicationId = "com.example.depthperceptionapp" // Replace if needed
        minSdk = 27 // Required for NNAPI delegate (good baseline for CameraX/NDK)
        // Balances modern features with reasonable device reach. Lower APIs might
        // lack necessary NDK/Camera/ML features or have compatibility issues.
        targetSdk = 34 // Target the latest stable SDK
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // NDK Configuration
        ndk {
            // Specifies the ABI (Application Binary Interface) filters.
            // ABI defines how the application's native code interacts with the system.
            // 'arm64-v8a' is essential for modern 64-bit ARM devices like the Tab S9 FE.
            // Add 'armeabi-v7a' if you need to support older 32-bit ARM devices.
            // Add 'x86'/'x86_64' if you need to support emulators or x86-based devices (less common).
            // Including fewer ABIs reduces APK size.
            abiFilters += setOf("arm64-v8a") // Focus on the target device's architecture first
        }

        // Configuration for CMake build system used for native C/C++ code
        externalNativeBuild {
            cmake {
                // Pass arguments to CMake during configuration.
                // "-DANDROID_STL=c++_shared" links against the shared C++ standard library runtime.
                // This is generally recommended over the static version to avoid potential issues
                // if multiple native libraries in your app link statically to different versions.
                arguments += "-DANDROID_STL=c++_shared"

                // Set C++ compiler flags.
                // "-std=c++17" enables C++17 features, a modern standard.
                cppFlags += "-std=c++17"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false // Disable code shrinking for now (simplicity)
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            // Debug specific settings if needed
        }
    }

    compileOptions {
        // Set Java language compatibility for Java code used in the project or dependencies
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    //kotlinOptions {
        // Set the target JVM version for Kotlin code
        //jvmTarget = "1.8"

    //}

    // Enable View Binding (or Data Binding) for easier view access
    // Alternatives include Kotlin synthetic properties (deprecated) or findViewById.
    buildFeatures {
        viewBinding = true
    }

    // NDK Integration: Link Gradle to the native build script (CMakeLists.txt)
    externalNativeBuild {
        cmake {
            // Path to your CMakeLists.txt file relative to this build.gradle.kts file.
            path = file("src/main/cpp/CMakeLists.txt")
            // Specify the CMake version installed with Android Studio's SDK Manager.
            // Use a reasonably recent version. Check your SDK manager for installed versions.
            version = "3.22.1" // Example version, adjust if needed
        }
    }

    // Asset Packaging Options
    // Prevent compression of TFLite model files within the APK.
    // TFLite interpreter often requires the model file to be memory-mapped,
    // which works best with uncompressed files stored in the assets folder.
    aaptOptions {
        noCompress += "tflite"
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }

    // Packaging options might be needed if native libraries cause conflicts
    // packagingOptions {
    //     pickFirsts += listOf("lib/arm64-v8a/libc++_shared.so", ...) // Example
    // }
}

dependencies {

    // --- Core Android Jetpack Libraries ---
    implementation("androidx.core:core-ktx:1.13.0") // Kotlin extensions for core Android features
    implementation("androidx.appcompat:appcompat:1.6.1") // Backward compatibility for UI features
    implementation("com.google.android.material:material:1.11.0") // Material Design components
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation(libs.androidx.core.ktx) // Layout manager

    // --- CameraX Libraries ---
    // CameraX provides a simplified and lifecycle-aware API for camera operations.
    // Preferred over Camera2 API for ease of use in most cases.
    val cameraxVersion = "1.3.3" // Use the latest stable version
    // Core CameraX library
    implementation("androidx.camera:camera-core:${cameraxVersion}")
    // CameraX implementation using the Camera2 API backend
    implementation("androidx.camera:camera-camera2:${cameraxVersion}")
    // CameraX Lifecycle integration for automatic lifecycle management
    implementation("androidx.camera:camera-lifecycle:${cameraxVersion}")
    // CameraX View library, includes PreviewView for easy preview display
    implementation("androidx.camera:camera-view:${cameraxVersion}")
    // Optional: CameraX Extensions for device-specific effects (Bokeh, HDR, etc.) - Not used in Phase 1
    // implementation("androidx.camera:camera-extensions:${cameraxVersion}")

    // --- TensorFlow Lite Libraries ---
    // Option 1: TFLite Support Library (Recommended for easier preprocessing/postprocessing)
    val tfliteVersion = "0.4.4" // Use the latest stable version of the support library
    // TFLite Support Library: Provides helpful utilities like TensorImage, ImageProcessor, etc.
    implementation("org.tensorflow:tensorflow-lite-support:${tfliteVersion}")
    // TFLite Task Library (Vision): Higher-level APIs for common vision tasks (not used directly for MiDaS)
    // implementation("org.tensorflow:tensorflow-lite-task-vision:${tfliteVersion}")
    // TFLite Core Interpreter API (implicitly included by support library, but good to be aware of)
    implementation(libs.tensorflow.lite) // Or use a specific core version if needed

    // TFLite GPU Delegate Plugin (Optional for Phase 1, include for future use)
    // This plugin simplifies adding the GPU delegate.
    // Requires `tensorflow-lite-gpu` dependency to be included transitively or explicitly.
    // Note: GPU delegate performance with Float32 models can vary (see comments in DepthPredictor).
    implementation(libs.tensorflow.lite.gpu.delegate.plugin)
    // If not using the plugin, you'd include the GPU delegate directly:
    // implementation("org.tensorflow:tensorflow-lite-gpu:${tfliteVersion}") // Check compatibility if using different versions

    // --- Kotlin Coroutines ---
    // For managing background tasks asynchronously (e.g., inference, analysis)
    val coroutinesVersion = "1.7.3" // Use a recent stable version
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:${coroutinesVersion}")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:${coroutinesVersion}")

    // --- Testing Libraries (Optional but Recommended) ---
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")

    implementation(project(":opencv"))



}

// Configuration explicite pour toutes les tâches de compilation Kotlin dans ce module
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    kotlinOptions {
        jvmTarget = "1.8" // <- Définir la cible JVM ici
        // Vous pouvez ajouter d'autres options du compilateur Kotlin ici si besoin
        // freeCompilerArgs += "-Xopt-in=kotlin.RequiresOptIn"
    }
}