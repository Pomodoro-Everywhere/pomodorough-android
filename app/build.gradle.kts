plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
}

val apiBaseUrl = providers.gradleProperty("POMODOROUGH_API_BASE_URL")
    .orElse("https://pomodorough.egigoka.me/api/v1")
val googleServerClientId = providers.gradleProperty("POMODOROUGH_GOOGLE_SERVER_CLIENT_ID")
    .orElse("614768274539-5jrk37jie6415babe51ae4qiupif0m7v.apps.googleusercontent.com")
val releaseStoreFile = providers.gradleProperty("POMODOROUGH_RELEASE_STORE_FILE")
    .orElse(providers.environmentVariable("POMODOROUGH_RELEASE_STORE_FILE"))
val releaseStorePassword = providers.gradleProperty("POMODOROUGH_RELEASE_STORE_PASSWORD")
    .orElse(providers.environmentVariable("POMODOROUGH_RELEASE_STORE_PASSWORD"))
val releaseKeyAlias = providers.gradleProperty("POMODOROUGH_RELEASE_KEY_ALIAS")
    .orElse(providers.environmentVariable("POMODOROUGH_RELEASE_KEY_ALIAS"))
val releaseKeyPassword = providers.gradleProperty("POMODOROUGH_RELEASE_KEY_PASSWORD")
    .orElse(providers.environmentVariable("POMODOROUGH_RELEASE_KEY_PASSWORD"))
val releaseSigningValues = listOf(
    releaseStoreFile,
    releaseStorePassword,
    releaseKeyAlias,
    releaseKeyPassword,
)
val releaseSigningConfigured = releaseSigningValues.all { it.isPresent }

check(releaseSigningValues.none { it.isPresent } || releaseSigningConfigured) {
    "Set all POMODOROUGH_RELEASE_* signing values or none of them."
}

android {
    namespace = "me.egigoka.pomodorough"
    compileSdk = 36
    buildToolsVersion = "36.0.0"
    testBuildType = providers.gradleProperty("pomodorough.testBuildType").orElse("debug").get()
    val requestedTestBuildType = testBuildType

    defaultConfig {
        applicationId = "me.egigoka.pomodorough"
        minSdk = 26
        targetSdk = 36
        versionCode = 25
        versionName = "0.10.0"

        testInstrumentationRunner = if (requestedTestBuildType == "release") {
            "me.egigoka.pomodorough.releaseiroh.ReleaseIrohSmokeInstrumentation"
        } else "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "API_BASE_URL", "\"${apiBaseUrl.get().trimEnd('/')}\"")
        buildConfigField("String", "GOOGLE_SERVER_CLIENT_ID", "\"${googleServerClientId.get()}\"")
    }

    signingConfigs {
        if (releaseSigningConfigured) {
            create("release") {
                storeFile = file(releaseStoreFile.get())
                storePassword = releaseStorePassword.get()
                keyAlias = releaseKeyAlias.get()
                keyPassword = releaseKeyPassword.get()
            }
        }
    }

    buildTypes {
        debug {
            isPseudoLocalesEnabled = true
        }
        release {
            if (releaseSigningConfigured) {
                signingConfig = signingConfigs.getByName("release")
            }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            testProguardFiles("android-test-proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    kotlinOptions {
        jvmTarget = "21"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }

    sourceSets {
        val sharedTestSources = "src/sharedTest/java"
        getByName("test").java.srcDir(sharedTestSources)
        getByName("androidTest").java.srcDir(sharedTestSources)
        getByName("androidTest").assets.srcDir("$projectDir/schemas")
        getByName("test").resources.srcDir("src/main/assets")
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.05.01")

    implementation(composeBom)
    androidTestImplementation(composeBom)
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.material3:material3:1.4.0-alpha15")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")

    implementation("androidx.credentials:credentials:1.3.0")
    implementation("androidx.credentials:credentials-play-services-auth:1.3.0")
    implementation("com.google.android.libraries.identity.googleid:googleid:1.1.1")

    implementation("androidx.room:room-runtime:2.7.2")
    implementation("androidx.room:room-ktx:2.7.2")
    ksp("androidx.room:room-compiler:2.7.2")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0")
    implementation("com.dylibso.chicory:runtime:1.7.5")
    implementation("computer.iroh:iroh-android:1.1.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:okhttp-sse:4.12.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.1")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation("androidx.room:room-testing:2.7.2")
    androidTestImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.1")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4-accessibility")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}
