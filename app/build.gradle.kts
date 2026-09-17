import java.util.Properties

plugins {
    alias(libs.plugins.agp.app)
    alias(libs.plugins.kotlin.compose)
}

/*
 * Release signing.
 *
 * The keystore is committed on purpose so every CI build is signed with the
 * same identity: a GitHub runner otherwise generates a throwaway debug key per
 * job, and two builds of the same app cannot upgrade-install over one another.
 * Only the key material is in the repository - the passwords come from
 * `keystore.properties` (local, git-ignored) or, in CI, from GitHub Secrets via
 * the ORG_GRADLE_PROJECT_OPENLOCK_RELEASE_* environment variables.
 *
 * Without the passwords nothing is signed and the build still works, which
 * keeps a fresh clone buildable by anyone.
 */
fun signingValue(name: String): String {
    val propsFile = rootProject.file("keystore.properties")
    if (propsFile.isFile) {
        val props = Properties()
        propsFile.inputStream().use { props.load(it) }
        props.getProperty(name)?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
    }
    return providers.gradleProperty(name).orNull?.trim().orEmpty()
}

val releaseStoreFile = "keystore/openlock-release.jks"
val releaseStorePassword = signingValue("OPENLOCK_RELEASE_STORE_PASSWORD")
val releaseKeyAlias = signingValue("OPENLOCK_RELEASE_KEY_ALIAS")
val releaseKeyPassword = signingValue("OPENLOCK_RELEASE_KEY_PASSWORD")
val hasReleaseSigning = releaseStorePassword.isNotEmpty() &&
    releaseKeyAlias.isNotEmpty() &&
    releaseKeyPassword.isNotEmpty()

android {
    namespace = "moe.openlock.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "moe.openlock.app"
        minSdk = 33
        targetSdk = 37
        // CI passes -PversionName (a tag, or the date-stamped fallback) so a test
        // build can be told apart from the last one. A plain local build keeps
        // the committed defaults.
        versionCode = providers.gradleProperty("versionCode").orNull?.toIntOrNull() ?: 1
        versionName = providers.gradleProperty("versionName").orNull ?: "0.0.1"
    }

    signingConfigs {
        create("release") {
            storeFile = rootProject.file(releaseStoreFile)
            storePassword = releaseStorePassword
            keyAlias = releaseKeyAlias
            keyPassword = releaseKeyPassword
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            // Signed with the release key too, so a test build and a release
            // build are interchangeable on the device. Without this the CI test
            // APK is signed by a fresh runner key every run and cannot be
            // installed over the previous one without uninstalling first.
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        // 21, not 17: miuix-nav is published built for JVM target 21, and Kotlin
        // refuses to inline its bytecode into a 17 target ("Cannot inline
        // bytecode built with JVM target 21"). Its `entry<T> {}` builder is
        // inline, so this is not optional.
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.androidx.biometric)

    implementation(libs.miuix.ui)
    implementation(libs.miuix.icons)
    implementation(libs.miuix.nav)
    implementation(libs.miuix.preference)
    implementation(libs.miuix.blur)
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}
