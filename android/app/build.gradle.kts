plugins {
    id("com.android.application")
}

fun prop(name: String, fallback: String): String =
    (project.findProperty(name) as String?)?.takeIf { it.isNotBlank() } ?: fallback

val appId        = prop("appId", "com.example.webapp")
val appLabel     = prop("appLabel", "WebApp")
val versionNameV = prop("versionName", "1.0.0")
val versionCodeV = prop("versionCode", "1").toIntOrNull() ?: 1
val appIconRef   = prop("appIcon", "@android:drawable/sym_def_app_icon")

android {
    namespace = "com.example.webappshell"
    compileSdk = 34

    defaultConfig {
        applicationId = appId
        minSdk = 24
        targetSdk = 34
        versionCode = versionCodeV
        versionName = versionNameV

        manifestPlaceholders["appLabel"] = appLabel
        manifestPlaceholders["appIcon"]  = appIconRef
    }

    buildTypes {
        getByName("debug") {
            isMinifyEnabled = false
            isDebuggable = true
            // 使用默认 debug keystore 自动签名，产物可直接安装
        }
        getByName("release") {
            isMinifyEnabled = false
            isShrinkResources = false
            // 不配置 signingConfig，产物为 app-release-unsigned.apk
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources.excludes += setOf("META-INF/*.kotlin_module", "META-INF/LICENSE*")
    }

    lint {
        abortOnError = false
    }
}

dependencies {
    implementation("androidx.webkit:webkit:1.11.0")
}