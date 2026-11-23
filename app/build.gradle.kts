plugins {
    id("com.android.application") version "8.13.2"
    id("org.jetbrains.kotlin.android") version "2.2.21"
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.21"
}

val releaseSigningNames = listOf(
    "ANDROID_KEYSTORE_FILE",
    "ANDROID_KEYSTORE_PASSWORD",
    "ANDROID_KEY_ALIAS",
    "ANDROID_KEY_PASSWORD",
)
val releaseSigning = releaseSigningNames.associateWith {
    providers.environmentVariable(it).orNull
}
val releaseSigningAvailable = releaseSigning.values.all { !it.isNullOrBlank() }

val validateReleaseSigning by tasks.registering {
    doLast {
        val missing = releaseSigning.filterValues { it.isNullOrBlank() }.keys
        check(missing.isEmpty()) {
            "Release signing requires " + missing.joinToString() +
                ". Run devbox run release with the signing environment."
        }
        check(file(requireNotNull(releaseSigning["ANDROID_KEYSTORE_FILE"])).isFile) {
            "The release keystore file is missing."
        }
    }
}

tasks.matching { it.name == "preReleaseBuild" }.configureEach {
    dependsOn(validateReleaseSigning)
}

android {
    namespace = "app.thumbrest"
    compileSdk = 36
    buildToolsVersion = "36.0.0"
    ndkVersion = "29.0.14206865"

    defaultConfig {
        applicationId = "app.thumbrest"
        minSdk = 29
        targetSdk = 36
        versionCode = providers.environmentVariable("THUMBREST_VERSION_CODE")
            .map(String::toInt).getOrElse(1)
        versionName = "0.2.0"
        ndk { abiFilters += "arm64-v8a" }
    }

    signingConfigs {
        if (releaseSigningAvailable) {
            create("thumbrestRelease") {
                storeFile = file(requireNotNull(releaseSigning["ANDROID_KEYSTORE_FILE"]))
                storePassword = releaseSigning["ANDROID_KEYSTORE_PASSWORD"]
                keyAlias = releaseSigning["ANDROID_KEY_ALIAS"]
                keyPassword = releaseSigning["ANDROID_KEY_PASSWORD"]
            }
        }
    }

    buildTypes {
        release {
            if (releaseSigningAvailable) {
                signingConfig = signingConfigs.getByName("thumbrestRelease")
            }
        }
    }

    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
}

kotlin { jvmToolchain(21) }
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.10.01"))
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("com.github.topjohnwu.libsu:core:6.0.0")
    implementation("com.github.topjohnwu.libsu:service:6.0.0")
}

dependencyLocking {
    lockAllConfigurations()
    // Kotlin's metadata-only redirect is absent in some AGP runtime resolution views.
    // The JVM kotlin-stdlib artifact remains locked.
    ignoredDependencies.add("org.jetbrains.kotlin:kotlin-stdlib-common")
}

// These selection checks use pure Kotlin and the existing toolchain, without
// loading Android classes or introducing a test runtime into the application.
tasks.register<JavaExec>("checkControllerChoice") {
    dependsOn("compileDebugUnitTestKotlin")
    mainClass.set("app.thumbrest.ControllerChoiceTestKt")
    classpath = files(
        layout.buildDirectory.dir("tmp/kotlin-classes/debugUnitTest"),
        layout.buildDirectory.dir("tmp/kotlin-classes/debug"),
        configurations.named("debugUnitTestRuntimeClasspath"),
    )
}
