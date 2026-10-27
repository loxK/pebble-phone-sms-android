plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "io.github.loxk.pebblephone"
    compileSdk {
        version = release(37) {
            minorApiLevel = 2
        }
    }

    defaultConfig {
        applicationId = "io.github.loxk.pebblephone"
        // MANAGE_ONGOING_CALLS for companion watch apps arrived with Android 12.
        minSdk = 31
        targetSdk = 37
        // F-Droid reads both as literals. The release workflow checks that 1.2.3 has the code 10203.
        versionCode = 10000
        versionName = "1.0.0"
    }

    // The release workflow passes the keystore, decoded from the repository secrets, through
    // the environment. Without it the release APK comes out unsigned.
    val keystore = System.getenv("SIGNING_KEYSTORE")
    if (keystore != null) {
        signingConfigs.create("release") {
            storeFile = file(keystore)
            storePassword = System.getenv("SIGNING_STORE_PASSWORD")
            keyAlias = System.getenv("SIGNING_KEY_ALIAS")
            keyPassword = System.getenv("SIGNING_KEY_PASSWORD")
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
        }
        release {
            signingConfig = signingConfigs.findByName("release")
            // The state of the git checkout would differ between builds of the same commit.
            vcsInfo.include = false
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
}

kotlin {
    jvmToolchain(21)
}

// F-Droid rebuilds each release and publishes the APK signed here only when its own build is
// identical: the ART profile, which comes out differently from one machine to the next, stays out.
tasks.configureEach {
    if (name.contains("ArtProfile")) enabled = false
}

/** Ships the CHANGELOG.md of the repository as an asset, read by the About screen. */
abstract class CopyChangelog : DefaultTask() {
    @get:InputFile
    abstract val source: RegularFileProperty

    @get:OutputDirectory
    abstract val output: DirectoryProperty

    @TaskAction
    fun copy() {
        source.get().asFile.copyTo(output.get().file("CHANGELOG.md").asFile, overwrite = true)
    }
}

val copyChangelog = tasks.register<CopyChangelog>("copyChangelog") {
    source = rootProject.layout.projectDirectory.file("CHANGELOG.md")
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(copyChangelog, CopyChangelog::output)
    }
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.pebblekit)

    testImplementation(libs.kotlin.test)
}
