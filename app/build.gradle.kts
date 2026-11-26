import java.text.SimpleDateFormat
import java.util.Date

plugins {
    id("com.android.application")
}

// Keep Gradle output out of app/, which publish.ps1 mirrors into the backup
// repo and backup.ps1 packs into the source snapshot.
layout.buildDirectory.set(rootProject.layout.projectDirectory.dir(".gradle-build/app"))

android {
    namespace = "io.github.yingxingyswx_design.wechatglass"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.yingxingyswx_design.wechatglass"
        minSdk = 24
        targetSdk = 34
        versionCode = 12
        versionName = "1.3.4.release"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    signingConfigs {
        // Same debug certificate the module has always been signed with, so
        // the APK can update the copy already installed on the phone.
        create("moduleDebug") {
            storeFile = rootProject.file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            isDebuggable = false
            signingConfig = signingConfigs.getByName("moduleDebug")
        }
    }

    buildFeatures {
        buildConfig = true
    }

    packaging {
        resources.excludes += setOf("META-INF/*.kotlin_module")
    }
}

dependencies {
    compileOnly(files("libs/api-82.jar"))
    compileOnly("androidx.recyclerview:recyclerview:1.3.2")
}

val copyApk = tasks.register<Copy>("copyApk") {
    from(layout.buildDirectory.file("outputs/apk/release/app-release.apk"))
    into(rootProject.layout.projectDirectory.dir("out"))
    rename { "wechat-glass-1.3.4.apk" }
}

// Every release ships the source archive next to the APK.
val sourceZip = tasks.register<Zip>("sourceZip") {
    from(rootProject.file("app/src")) {
        into("app/src")
    }
    from(rootProject.file("gradle")) {
        into("gradle")
    }
    from(rootProject.file("docs")) {
        into("docs")
    }
    listOf("build.gradle.kts", "settings.gradle.kts", "gradle.properties", "gradlew",
        "gradlew.bat", "README.md", "HANDOFF.md", "VERSION.md",
        "THIRD_PARTY_NOTICES.md").forEach { name ->
        from(rootProject.file(name))
    }
    archiveFileName.set("wechat-glass-1.3.4-source.zip")
    destinationDirectory.set(rootProject.layout.projectDirectory.dir("out"))
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

// The version also lives in ModuleVersion.java (build.ps1 has no BuildConfig),
// so both must agree or the build stops.
val verifyModuleVersion = tasks.register("verifyModuleVersion") {
    doLast {
        val expected = android.defaultConfig.versionName
        val expectedCode = android.defaultConfig.versionCode
        val source = rootProject.file(
            "app/src/main/java/com/wechatglass/ModuleVersion.java").readText()
        val name = Regex("NAME\\s*=\\s*\"([^\"]+)\"").find(source)?.groupValues?.get(1)
        val code = Regex("CODE\\s*=\\s*(\\d+)").find(source)?.groupValues?.get(1)?.toInt()
        if (name != expected || code != expectedCode) {
            throw GradleException("ModuleVersion.java (/) does not match" +
                " build.gradle.kts (/)")
        }
    }
}
tasks.matching { it.name == "assembleRelease" }.configureEach {
    dependsOn(verifyModuleVersion)
    finalizedBy(copyApk)
    // Hard rule: a successful build must be backed up into the git repo.
    // doLast only runs when the task actually succeeded, so a failed compile
    // never commits a half state.
    doLast {
        val script = rootProject.file("publish.ps1")
        if (!script.exists()) {
            throw GradleException("publish.ps1 not found: ${script.absolutePath}")
        }
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm").format(Date())
        val appVersion = android.defaultConfig.versionName ?: "unknown"
        val message = "build: 自动备份 $appVersion @ $stamp"
        println("backup -> C:\\git\\wechat_chat_ui   ($message)")
        val process = ProcessBuilder(
            "powershell", "-NoProfile", "-ExecutionPolicy", "Bypass",
            "-File", script.absolutePath, "-Message", message
        ).directory(rootProject.projectDir).inheritIO().start()
        if (process.waitFor() != 0) {
            throw GradleException("backup to C:\\git\\wechat_chat_ui failed")
        }
    }
}
