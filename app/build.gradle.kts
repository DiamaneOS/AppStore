import java.io.FileInputStream
import java.util.Properties

val keystorePropertiesFile = rootProject.file("keystore.properties")
val useKeystoreProperties = keystorePropertiesFile.canRead()
val keystoreProperties = Properties()
if (useKeystoreProperties) {
    keystoreProperties.load(FileInputStream(keystorePropertiesFile))
}

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.google.devtools.ksp)
    alias(libs.plugins.androidx.navigation.safeargs)
    id("kotlin-parcelize")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

android {
    if (useKeystoreProperties) {
        signingConfigs {
            create("release") {
                storeFile = rootProject.file(keystoreProperties["storeFile"]!!)
                storePassword = keystoreProperties["storePassword"] as String
                keyAlias = keystoreProperties["keyAlias"] as String
                keyPassword = keystoreProperties["keyPassword"] as String
                enableV4Signing = true
            }
        }
    }

    compileSdk = 36
    buildToolsVersion = "36.1.0"

    namespace = "app.grapheneos.apps"

    defaultConfig {
        applicationId = "app.grapheneos.apps"
        minSdk = 31
        targetSdk = 36
        // Upstream version x 100 + DiamaneOS revision (36.1 = 3601), only ever rising
        versionCode = 3601
        versionName = "36.1"

        // The build supplies the catalog key: the release key for official builds, a test key
        // for test builds. There is no default, and GrapheneOS's key is refused.
        val catalogKey = System.getenv("REPO_PUBLIC_KEY") ?: ""
        val catalogVersion = System.getenv("REPO_KEY_VERSION") ?: "0"
        // Test builds may use the staging server instead of apps.diamaneos.de and apps-na.
        val catalogServer = System.getenv("REPO_SERVER") ?: "production"
        require(catalogKey.isEmpty() || catalogKey.matches(Regex("[A-Za-z0-9+/]{56}")))
        require(catalogKey != "RWQtZwEu1br1lMh911L3yPOs97cQb9LOks/ALBbqGl21ul695ocWR/ir")
        require(catalogVersion.matches(Regex("0|[1-9][0-9]{0,5}")))
        require(catalogServer == "production" || catalogServer == "staging")
        buildConfigField(String::class.java.name, "REPO_PUBLIC_KEY", "\"$catalogKey\"")
        buildConfigField(String::class.java.name, "REPO_KEY_VERSION", "\"$catalogVersion\"")
        buildConfigField("boolean", "REPO_STAGING", (catalogServer == "staging").toString())
    }

    buildTypes {
        getByName("release") {
            isShrinkResources = true
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (useKeystoreProperties) {
                signingConfig = signingConfigs.getByName("release")
            }
        }

        getByName("debug") {
            applicationIdSuffix = ".debug"
        }
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    androidResources {
        localeFilters += listOf("en")
    }

    packaging {
        resources.excludes.addAll(listOf(
            "META-INF/versions/*/OSGI-INF/MANIFEST.MF",
            "org/bouncycastle/pqc/**.properties",
            "org/bouncycastle/x509/**.properties",
        ))
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.androidx.navigation.fragment.ktx)
    implementation(libs.androidx.navigation.ui.ktx)
    implementation(libs.androidx.preference.ktx)
    implementation(libs.androidx.swiperefreshlayout)

    implementation(libs.androidx.lifecycle.viewmodel)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)

    implementation(libs.material)

    implementation(libs.bcprov.jdk18on)

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlin.retry)
    implementation(libs.kotlin.retry.result)

    implementation(libs.glide.core)
    ksp(libs.glide.ksp)
}
