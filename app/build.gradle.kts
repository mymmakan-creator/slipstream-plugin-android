import com.nishtahir.CargoBuildTask
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.io.IOException

plugins {
    id("com.android.application")
    id("org.mozilla.rust-android-gradle.rust-android")
    kotlin("android")
}

val minSdkVersion = 23
val cargoProfile = (findProperty("CARGO_PROFILE") as String?) ?: run {
    val isRelease = gradle.startParameter.taskNames.any { it.contains("Release", ignoreCase = true) }
    if (isRelease) "release" else "debug"
}

fun abiFromTarget(target: String): String = when {
    target.startsWith("aarch64") -> "arm64-v8a"
    target.startsWith("armv7") || target.startsWith("arm") -> "armeabi-v7a"
    target.startsWith("i686") -> "x86"
    target.startsWith("x86_64") -> "x86_64"
    else -> target
}

android {
    val javaVersion = JavaVersion.VERSION_1_8
    namespace = "be.mygod.shadowsocks.plugin.slipstream"
    compileSdk = 36
    compileOptions {
        sourceCompatibility = javaVersion
        targetCompatibility = javaVersion
    }

    defaultConfig {
        applicationId = "be.mygod.shadowsocks.plugin.slipstream"
        minSdk = minSdkVersion
        targetSdk = 36
        versionCode = 1
        versionName = "0.0.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isShrinkResources = true
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
        }
    }
    ndkVersion = "27.2.12479018"
    packagingOptions.jniLibs.useLegacyPackaging = true
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_1_8)
    }
}

cargo {
    module = "src/main/rust/slipstream-rust"
    libname = "slipstream"
    targets = listOf("arm", "arm64", "x86", "x86_64")
    profile = cargoProfile
    extraCargoBuildArguments = listOf(
        "-p", "slipstream-client",
        "--bin", "slipstream-client",
        "--features", "openssl-vendored,picoquic-minimal-build",
    )
    exec = { spec, toolchain ->
        run {
            try {
                Runtime.getRuntime().exec(arrayOf("python3", "-V"))
                spec.environment("RUST_ANDROID_GRADLE_PYTHON_COMMAND", "python3")
                project.logger.lifecycle("Python 3 detected.")
            } catch (_: IOException) {
                project.logger.lifecycle("No python 3 detected.")
                try {
                    Runtime.getRuntime().exec(arrayOf("python", "-V"))
                    spec.environment("RUST_ANDROID_GRADLE_PYTHON_COMMAND", "python")
                    project.logger.lifecycle("Python detected.")
                } catch (e: IOException) {
                    throw GradleException("No any python version detected. You should install the python first to compile project.", e)
                }
            }
            spec.environment(
                "RUST_ANDROID_GRADLE_CC_LINK_ARG",
                "-Wl,-z,max-page-size=16384,-soname,lib$libname.so"
            )
            spec.environment(
                "RUST_ANDROID_GRADLE_LINKER_WRAPPER_PY",
                "$projectDir/src/main/rust/linker-wrapper.py"
            )
            spec.environment(
                "RUST_ANDROID_GRADLE_TARGET",
                "target/${toolchain.target}/$cargoProfile/lib$libname.so"
            )
            val abi = abiFromTarget(toolchain.target)
            spec.environment("ANDROID_NDK_HOME", android.ndkDirectory.absolutePath)
            spec.environment("ANDROID_ABI", abi)
            spec.environment("ANDROID_PLATFORM", "android-$minSdkVersion")
            spec.environment(
                "PICOQUIC_BUILD_DIR",
                "$projectDir/src/main/rust/slipstream-rust/.picoquic-build/$abi"
            )
            spec.environment("PICOQUIC_AUTO_BUILD", "1")
            spec.environment("BUILD_TYPE", if (cargoProfile == "release") "Release" else "Debug")
            val toolchainPrebuilt = android.ndkDirectory
                .resolve("toolchains/llvm/prebuilt")
                .listFiles()
                ?.firstOrNull { it.isDirectory }
            val toolchainBin = toolchainPrebuilt?.resolve("bin")
            if (toolchainBin != null) {
                spec.environment("AR", toolchainBin.resolve("llvm-ar").absolutePath)
                spec.environment("RANLIB", toolchainBin.resolve("llvm-ranlib").absolutePath)
            }
        }
    }
}

tasks.withType<CargoBuildTask>().configureEach {
    doNotTrackState("Cargo builds are externally cached; always run.")
}

tasks.whenTaskAdded {
    when (name) {
        "mergeDebugJniLibFolders", "mergeReleaseJniLibFolders" -> {
            dependsOn("cargoBuild")
            // Track Rust JNI output without adding a second source set (avoids duplicate resources).
            inputs.dir(layout.buildDirectory.dir("rustJniLibs/android"))
        }
    }
}

tasks.register<Exec>("cargoClean") {
    executable("cargo")
    args("clean")
    workingDir("$projectDir/${cargo.module}")
}
tasks.named("clean") {
    dependsOn("cargoClean")
}

dependencies {
    implementation("androidx.preference:preference:1.2.1")
    implementation("com.github.shadowsocks:plugin:2.0.1")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
}
