import com.android.build.OutputFile
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.androidx.baselineprofile)
}

val diagnosticBuildSha = providers.environmentVariable("GITHUB_SHA").orNull
    ?.take(12)
    ?: runCatching {
        providers.exec {
            commandLine("git", "rev-parse", "--short=12", "HEAD")
            isIgnoreExitValue = true
        }.standardOutput.asText.get().trim()
    }.getOrDefault("unknown").ifBlank { "unknown" }
val appVersionCode = 58
val appVersionName = "0.1.57"
val diagnosticsUploadUrl = providers.gradleProperty("diagnosticsUploadUrl").orNull
    ?: providers.environmentVariable("DIAGNOSTICS_UPLOAD_URL").orNull
    ?: "https://anilili-diagnostics.anilili.workers.dev"

val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

android {
    namespace = "com.anilili"
    compileSdk = 36
    buildToolsVersion = "35.0.1"

    defaultConfig {
        applicationId = "com.miruronative"
        // Fire OS 5 devices (including the 1st/2nd-gen Fire TV Sticks) report API 22.
        minSdk = 22
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersionName
        buildConfigField("String", "GIT_SHA", "\"$diagnosticBuildSha\"")
        buildConfigField("String", "DIAGNOSTICS_UPLOAD_URL", "\"$diagnosticsUploadUrl\"")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            val storePath = keystoreProperties.getProperty("storeFile")
            if (storePath != null) {
                storeFile = rootProject.file(storePath)
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            manifestPlaceholders["usesCleartextTraffic"] = "true"
            // TEMP: install alongside the release build as com.miruronative.debug so testing
            // never collides with (and forces an uninstall of) the release's LibraryStore data.
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("release")
            manifestPlaceholders["usesCleartextTraffic"] = "false"
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    // One codebase, two shipped apps. The split is a build variant rather than a fork: every
    // defect found so far (the download-scheduler crash, the progress-attribution race, the
    // AniList pacing regression) lived in shared logic and would have had to be fixed twice in
    // two repositories. What genuinely differs is what each form factor should even contain —
    // Google Cast is meaningless on a TV that *is* the cast target, and on Fire TV its Play
    // Services shell throws on every service start ("No acceptable module ... dynamite").
    //
    // applicationId is deliberately identical across flavors: changing it would orphan every
    // existing install from its updates and its saved library.
    flavorDimensions += "form"
    productFlavors {
        create("mobile") {
            dimension = "form"
            isDefault = true
        }
        create("tv") {
            dimension = "form"
        }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    testOptions {
        unitTests {
            // Production code logs through DiagnosticsLog, which reads SystemClock. Without this,
            // any unit test that walks a logging path dies on "not mocked" instead of asserting.
            isReturnDefaultValues = true
        }
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }

    // Fire TV hardware is ARM. Publish a small APK for each ARM generation and retain a
    // universal APK for users who do not know which one their device needs.
    splits {
        abi {
            isEnable = true
            reset()
            include("armeabi-v7a", "arm64-v8a")
            isUniversalApk = true
        }
    }

    applicationVariants.all {
        outputs.all {
            val output = this as com.android.build.gradle.internal.api.BaseVariantOutputImpl
            val abi = output.getFilter(OutputFile.ABI)
            val buildTypeSuffix = if (buildType.name == "release") "" else "-${buildType.name}"
            // Updaters in v0.1.32 and earlier install the release's first .apk asset, and
            // GitHub orders assets by name: '.' sorts before '_', so "Anilili.apk" (universal,
            // runs on every ABI) must precede "Anilili_<abi>.apk". Never name splits with '-';
            // '-' sorts before '.' and legacy TVs would fetch an incompatible split.
            //
            // The mobile flavor keeps the historical names exactly, because every install out
            // there is already looking for them. TV assets take an extra "_tv" segment, which
            // sorts after all of the mobile names, so a legacy updater still lands on the
            // universal mobile APK rather than a build meant for a different form factor.
            val formSuffix = if (flavorName == "tv") "_tv" else ""
            output.outputFileName = if (abi == null) {
                "Anilili$buildTypeSuffix$formSuffix.apk"
            } else {
                "Anilili$buildTypeSuffix${formSuffix}_$abi.apk"
            }
        }
    }
}

// Crash reports arrive obfuscated, so every shipped build's mapping has to be kept. With the
// mobile/TV flavors there are two of them per release, and the flavor has to be in the filename
// or the second one silently overwrites the first — a report from a TV would then be deobfuscated
// against the phone build and produce plausible, wrong class names.
listOf("mobile", "tv").forEach { flavor ->
    val variant = "${flavor}Release"
    val archiveTask = tasks.register<Copy>("archive${variant.replaceFirstChar(Char::uppercase)}Mapping") {
        dependsOn("minify${variant.replaceFirstChar(Char::uppercase)}WithR8")
        from(layout.buildDirectory.file("outputs/mapping/$variant/mapping.txt"))
        into(layout.buildDirectory.dir("outputs/mapping-archive"))
        rename(
            "mapping.txt",
            "mapping-$flavor-v$appVersionName-$appVersionCode-$diagnosticBuildSha.txt",
        )
    }
    tasks.matching { it.name == "assemble${variant.replaceFirstChar(Char::uppercase)}" }
        .configureEach { finalizedBy(archiveTask) }
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.0.3")

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.compose.material)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    debugImplementation(libs.androidx.ui.tooling)

    implementation(libs.androidx.navigation.compose)

    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.exoplayer.hls)
    implementation(libs.androidx.media3.ui)
    implementation(libs.androidx.media3.session)
    implementation(libs.androidx.media3.datasource)
    implementation(libs.androidx.media3.datasource.cronet)
    implementation(libs.androidx.media3.database)
    // Cast is mobile-only. It also drags in play-services-cast-framework and mediarouter, so
    // scoping it here is what actually makes the TV build smaller, not just tidier.
    "mobileImplementation"(libs.androidx.media3.cast)
    implementation(libs.androidx.media3.transformer)
    implementation(libs.play.services.cronet)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.webkit)
    implementation(libs.androidx.fragment.ktx)

    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.okio)
    implementation(libs.jsoup)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.coil.compose)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.tvprovider)
    implementation(libs.androidx.profileinstaller)
    // Schnorr (BIP-340) verification for the Nostr update-manifest fallback in UpdateManager.
    implementation(libs.acinq.secp256k1.android)
    baselineProfile(project(":benchmark"))
    testImplementation(libs.junit)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.acinq.secp256k1.jvm)
    androidTestImplementation("androidx.test:core-ktx:1.6.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
