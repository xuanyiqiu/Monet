import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Kept out of git: apps/android/local.properties (key `monet.supabasePublishableKey`).
val localProps = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}

fun gitOutput(vararg args: String): String? {
    return try {
        val output = project.providers.exec {
            commandLine("git", *args)
            workingDir = project.rootProject.projectDir
            isIgnoreExitValue = true
        }
        val exitValue = output.result.get().exitValue
        val text = output.standardOutput.asText.get().trim()
        if (exitValue != 0 || text.isEmpty()) null else text
    } catch (e: Exception) {
        null
    }
}

fun signingProp(name: String): String? {
    val value = if (project.hasProperty(name)) {
        project.property(name)?.toString()
    } else {
        localProps.getProperty(name)
    }
    return value?.takeIf { it.isNotBlank() }
}

val releaseStoreFile: String? = signingProp("monet.release.storeFile")
if (releaseStoreFile != null && !file(releaseStoreFile).exists()) {
    throw GradleException("Release keystore not found: $releaseStoreFile")
}

val monetVersionCode: Int =
    project.providers.gradleProperty("monet.versionCode").orNull?.takeIf { it.isNotBlank() }?.toInt()
        ?: gitOutput("rev-list", "--count", "HEAD")?.toIntOrNull()
        ?: 1

val monetVersionName: String =
    project.providers.gradleProperty("monet.versionName").orNull?.takeIf { it.isNotBlank() }
        ?: gitOutput("describe", "--tags", "--match", "v[0-9]*", "--dirty=-dirty")?.removePrefix("v")
        ?: "0.1.0-dev"

android {
    namespace = "dev.monet.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.monet.app"
        minSdk = 26
        targetSdk = 35
        versionCode = monetVersionCode
        versionName = monetVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Production backend. Both URLs are public; the publishable key only identifies the project.
        buildConfigField("String", "API_BASE_URL", "\"https://monet-4uvusyivrq-ew.a.run.app\"")
        buildConfigField("String", "SUPABASE_URL", "\"https://enjtszhyaojcnqeriule.supabase.co\"")
        buildConfigField(
            "String",
            "SUPABASE_PUBLISHABLE_KEY",
            "\"${localProps.getProperty("monet.supabasePublishableKey", "")}\"",
        )
    }

    signingConfigs {
        if (releaseStoreFile != null) {
            create("release") {
                storeFile = file(releaseStoreFile)
                storePassword = signingProp("monet.release.storePassword")
                keyAlias = signingProp("monet.release.keyAlias")
                keyPassword = signingProp("monet.release.keyPassword")
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            if (releaseStoreFile != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
    splits {
        abi {
            isEnable = gradle.startParameter.taskNames.any { it.contains("Release", ignoreCase = true) }
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = true
        }
    }
    applicationVariants.all {
        val variant = this
        outputs.all outputLoop@ {
            if (variant.buildType.name != "release") return@outputLoop
            val output = this as com.android.build.gradle.internal.api.BaseVariantOutputImpl
            val abi = output.filters.find { it.filterType == "ABI" }?.identifier ?: "universal"
            output.outputFileName = "monet-$monetVersionName-$abi.apk"
        }
    }
    compileOptions {
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
    lint {
        abortOnError = true
        warningsAsErrors = false
    }
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.text.google.fonts)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.retrofit)
    implementation(libs.retrofit.serialization)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.mlkit.text.chinese)
    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.junit)
    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    testImplementation(libs.kotlinx.coroutines.test)
}

tasks.register("printVersion") {
    doLast {
        println("monet-version $monetVersionName $monetVersionCode")
    }
}

tasks.register("printSigning") {
    doLast {
        println("monet-signing ${releaseStoreFile != null}")
    }
}
