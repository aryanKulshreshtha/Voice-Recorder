import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.io.FileInputStream
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import org.jetbrains.kotlin.konan.properties.Properties

plugins {
    alias(libs.plugins.android)
    alias(libs.plugins.ksp)
    alias(libs.plugins.detekt)
}

val keystorePropertiesFile: File = rootProject.file("keystore.properties")
val keystoreProperties = Properties()
if (keystorePropertiesFile.exists()) {
    keystoreProperties.load(FileInputStream(keystorePropertiesFile))
}

fun hasSigningVars(): Boolean {
    return providers.environmentVariable("SIGNING_KEY_ALIAS").orNull != null
            && providers.environmentVariable("SIGNING_KEY_PASSWORD").orNull != null
            && providers.environmentVariable("SIGNING_STORE_FILE").orNull != null
            && providers.environmentVariable("SIGNING_STORE_PASSWORD").orNull != null
}

base {
    val versionCode = project.property("VERSION_CODE").toString().toInt()
    archivesName = "voicerecorder-$versionCode"
}

android {
    compileSdk = project.libs.versions.app.build.compileSDKVersion.get().toInt()

    defaultConfig {
        applicationId = project.property("APP_ID").toString()
        minSdk = project.libs.versions.app.build.minimumSDK.get().toInt()
        targetSdk = project.libs.versions.app.build.targetSDK.get().toInt()
        versionName = project.property("VERSION_NAME").toString()
        versionCode = project.property("VERSION_CODE").toString().toInt()
        vectorDrawables.useSupportLibrary = true
    }

    signingConfigs {
        if (keystorePropertiesFile.exists()) {
            register("release") {
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
                storeFile = file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
            }
        } else if (hasSigningVars()) {
            register("release") {
                keyAlias = providers.environmentVariable("SIGNING_KEY_ALIAS").get()
                keyPassword = providers.environmentVariable("SIGNING_KEY_PASSWORD").get()
                storeFile = file(providers.environmentVariable("SIGNING_STORE_FILE").get())
                storePassword = providers.environmentVariable("SIGNING_STORE_PASSWORD").get()
            }
        } else {
            logger.warn("Warning: No signing config found. Build will be unsigned.")
        }
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (keystorePropertiesFile.exists() || hasSigningVars()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    flavorDimensions.add("variants")
    productFlavors {
        // AvatarSDK's own manifest requires minSdk 31; foss doesn't ship the SDK so it keeps the
        // project-wide default minSdk.
        register("core") { minSdk = 31 }
        register("foss")
        register("gplay") { minSdk = 31 }
    }

    sourceSets {
        getByName("main").java.directories.add("src/main/kotlin")
        // Real AvatarSDK-backed implementation.
        getByName("core").java.directories.add("src/core/kotlin")
        getByName("gplay").java.directories.add("src/gplay/kotlin")
        // No-op stand-in so the foss flavor never depends on AvatarSDK.
        getByName("foss").java.directories.add("src/foss/kotlin")
    }

    androidResources {
        @Suppress("UnstableApiUsage")
        generateLocaleConfig = true
        // AvatarSDK extracts libDI_plugin.so from assets at runtime; it must not be compressed
        // into the APK for that to work.
        noCompress += "so"
    }

    // AvatarSDK's QNN backend/model/skel libraries are dlopen'd by explicit filesystem path, not
    // loaded via System.loadLibrary — that only works if they're physically extracted onto disk.
    // AGP's modern default (useLegacyPackaging = false) instead mmaps native libs directly from
    // inside the APK and leaves nativeLibraryDir empty, which breaks those dlopen() calls.
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    compileOptions {
        val currentJavaVersionFromLibs = JavaVersion.valueOf(libs.versions.app.build.javaVersion.get())
        sourceCompatibility = currentJavaVersionFromLibs
        targetCompatibility = currentJavaVersionFromLibs
    }

    dependenciesInfo {
        includeInApk = false
    }

    tasks.withType<KotlinCompile> {
        compilerOptions.jvmTarget.set(
            JvmTarget.fromTarget(project.libs.versions.app.build.kotlinJVMTarget.get())
        )
    }

    namespace = project.property("APP_ID").toString()

    lint {
        checkReleaseBuilds = false
        abortOnError = true
        warningsAsErrors = false
        baseline = file("lint-baseline.xml")
        lintConfig = rootProject.file("lint.xml")
    }

    bundle {
        language {
            enableSplit = false
        }
    }
}

detekt {
    baseline = file("detekt-baseline.xml")
    config.setFrom("$rootDir/detekt.yml")
    buildUponDefaultConfig = true
    allRules = false
}

dependencies {
    implementation(libs.fossify.commons)
    implementation(libs.eventbus)
    implementation(libs.audiorecordview)
    implementation(libs.androidx.documentfile)
    implementation(libs.androidx.swiperefreshlayout)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.tandroidlame)
    implementation(libs.autofittextview)
    detektPlugins(libs.compose.detekt)

    // AvatarSDK is a locally-generated AAR, not a published artifact — see the AvatarSDK repo's
    // own README/build for how to (re)produce sdk/build/outputs/aar/sdk-release.aar and drop it
    // in here as avatarsdk-release.aar. Only the core/gplay flavors ship it; foss stays free of
    // the SDK and the network permissions it brings in.
    "coreImplementation"(files("libs/avatarsdk-release.aar"))
    "gplayImplementation"(files("libs/avatarsdk-release.aar"))
}
