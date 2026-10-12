import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "app.nzip"
    compileSdk = 37
    compileSdkMinor = 2

    defaultConfig {
        applicationId = "app.nzip"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    signingConfigs {
        create("release") {
            // Credentials live in local.properties (gitignored, as is *.jks).
            val props = Properties()
            rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use(props::load)
            storeFile = rootProject.file(props.getProperty("nzip.keystore", "nzip-release.jks"))
            storePassword = props.getProperty("nzip.keystore.password", "")
            keyAlias = props.getProperty("nzip.key.alias", "nzip")
            keyPassword = props.getProperty("nzip.key.password", "")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.getByName("release")
        }
    }

    // One APK per ABI plus a universal one (GitHub Releases / Obtainium).
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a")
            isUniversalApk = true
        }
    }

    // lib7zz.so is an executable; it must be extracted to nativeLibraryDir to be exec-able.
    packaging {
        jniLibs.useLegacyPackaging = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }
}

val build7zz = tasks.register<Exec>("build7zz") {
    val script = rootProject.file("native/build-7zz.sh")
    commandLine(script)
    inputs.file(script)
    outputs.dir("src/main/jniLibs")
}
tasks.named("preBuild") { dependsOn(build7zz) }

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.compose.material3)
    implementation(libs.compose.icons)
    testImplementation("junit:junit:4.13.2")
}
