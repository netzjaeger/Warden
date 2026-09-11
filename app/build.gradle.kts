import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "app.anonymous.warden"
    compileSdk = 36

    signingConfigs {
        create("releaseSignature") {
            storeFile = file(project.findProperty("StoreFile")?.toString() ?: "warden-release-key.keystore")
            storePassword = (project.findProperty("WardenStorePassword") as? String) ?: System.getenv("WARDEN_STORE_PASS")
            keyPassword = (project.findProperty("WardenKeyPassword") as? String) ?: System.getenv("WARDEN_KEY_PASS")
            keyAlias = (project.findProperty("WardenKeyAlias") as? String) ?: System.getenv("WARDEN_KEY_ALIAS") ?: "warden"
        }
    }

    defaultConfig {
        applicationId = "app.anonymous.warden"
        minSdk = 26
        targetSdk = 36

        versionCode = (project.findProperty("AppVersionCode") as? String)?.toIntOrNull() ?: 1
        versionName = (project.findProperty("AppVersionName") as? String) ?: "1.0"

        val userWhitelist = System.getenv("WARDEN_WHITELIST") ?: (project.findProperty("WardenWhitelist") as? String) ?: "app.anonymous.warden,com.android.settings"
        val urlBlocklist = System.getenv("WARDEN_URL_BLOCKLIST") ?: (project.findProperty("WardenUrlBlocklist") as? String) ?: ""
        val userBatteryFlags = System.getenv("WARDEN_BATTERY_FLAGS") ?: (project.findProperty("WardenBatteryFlags") as? String) ?: "advertise_is_enabled=true"
        val systemDns = System.getenv("WARDEN_SYSTEM_DNS") ?: (project.findProperty("WardenSystemDns") as? String) ?: "dns.google"
        val browserDns = System.getenv("WARDEN_BROWSER_DNS") ?: (project.findProperty("WardenBrowserDns") as? String) ?: "dns.google"
        val chromiumPackages = System.getenv("WARDEN_CHROMIUM_PACKAGES") ?: (project.findProperty("WardenChromiumPackages") as? String) ?: "com.android.chrome"
        val chromiumPolicies = System.getenv("WARDEN_CHROMIUM_POLICIES") ?: (project.findProperty("WardenChromiumPolicies") as? String) ?: ""

        val urlAllowlist = System.getenv("WARDEN_URL_ALLOWLIST") ?: (project.findProperty("WardenUrlAllowlist") as? String) ?: ""
        val localeLock = System.getenv("WARDEN_LOCALE_LOCK") ?: (project.findProperty("WardenLocaleLock") as? String) ?: ""
        val managedSettings = System.getenv("WARDEN_MANAGED_SETTINGS") ?: (project.findProperty("WardenManagedSettings") as? String) ?: ""
        val nightLightStartMs = System.getenv("WARDEN_NIGHT_LIGHT_START_MS") ?: (project.findProperty("WardenNightLightStartMs") as? String) ?: ""
        val nightLightEndMs = System.getenv("WARDEN_NIGHT_LIGHT_END_MS") ?: (project.findProperty("WardenNightLightEndMs") as? String) ?: ""
        val nightLightTemp = System.getenv("WARDEN_NIGHT_LIGHT_TEMP") ?: (project.findProperty("WardenNightLightTemp") as? String) ?: ""

        if (urlBlocklist.isBlank()) {
            logger.warn("WARNING: URL_BLOCKLIST is empty — this build's native Chromium URL blocking will be completely inert.")
        }

        val quote: (String) -> String = { "\"" + it.replace("\\", "\\\\").replace("\"", "\\\"") + "\"" }

        buildConfigField("String", "APP_WHITELIST", quote(userWhitelist))
        buildConfigField("String", "URL_BLOCKLIST", quote(urlBlocklist))
        buildConfigField("String", "URL_ALLOWLIST", quote(urlAllowlist))
        buildConfigField("String", "BATTERY_SAVER_FLAGS", quote(userBatteryFlags))
        buildConfigField("String", "PRIVATE_DNS", quote(systemDns))
        buildConfigField("String", "BROWSER_PRIVATE_DNS", quote(browserDns))
        buildConfigField("String", "LOCALE_LOCK", quote(localeLock))
        buildConfigField("String", "MANAGED_SETTINGS", quote(managedSettings))
        buildConfigField("String", "CHROMIUM_PACKAGES", quote(chromiumPackages))
        buildConfigField("String", "CHROMIUM_POLICIES", quote(chromiumPolicies))
        buildConfigField("String", "NIGHT_LIGHT_START_MS", quote(nightLightStartMs))
        buildConfigField("String", "NIGHT_LIGHT_END_MS", quote(nightLightEndMs))
        buildConfigField("String", "NIGHT_LIGHT_TEMP", quote(nightLightTemp))
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            isDebuggable = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("releaseSignature")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    buildFeatures {
        buildConfig = true
        aidl = false
        resValues = false
        shaders = false
        renderScript = false
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
        disable += listOf("MissingTranslation", "GoogleAppIndexingWarning")
    }
}

kotlin {
    compilerOptions { jvmTarget = JvmTarget.JVM_21 }
}

androidComponents {
    beforeVariants(selector().withBuildType("debug")) { variantBuilder ->
        variantBuilder.enable = false
    }
}

dependencies {
    implementation(libs.coroutines.android)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.annotation)
}
