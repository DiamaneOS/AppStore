import java.io.FileInputStream
import java.util.Base64
import java.util.Properties

val keystorePropertiesFile = rootProject.file("keystore.properties")
val useKeystoreProperties = keystorePropertiesFile.canRead()
val keystoreProperties = Properties()
if (useKeystoreProperties) {
    keystoreProperties.load(FileInputStream(keystorePropertiesFile))
}

// DiamaneOS: the repository metadata key (signify Ed25519 public key, the second line of
// apps.<version>.pub) comes from REPO_PUBLIC_KEY or from repo-public-key.pub. There is no
// fallback to another project's key: the build fails while neither holds a valid key.
fun repoPublicKey(): String {
    val file = rootProject.file("repo-public-key.pub")
    val key = System.getenv("REPO_PUBLIC_KEY")
        ?: file.readLines().filter { it.isNotBlank() && !it.startsWith("untrusted comment:") }.singleOrNull()
        ?: ""
    val decoded = try { Base64.getDecoder().decode(key) } catch (e: IllegalArgumentException) { null }
    if (decoded == null || decoded.size != 42 || decoded[0] != 'E'.code.toByte() || decoded[1] != 'd'.code.toByte()) {
        throw GradleException("No App Store repository key: put the signify public key in " +
            "repo-public-key.pub (the release key from the key ceremony) or set REPO_PUBLIC_KEY (test builds)")
    }
    return key
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
        // DiamaneOS: upstream version x 100 + DiamaneOS revision (36.1 = 3601), only ever rising
        versionCode = 3601
        versionName = "36.1"

        buildConfigField(String::class.java.name, "REPO_BASE_URL",
            "\"${System.getenv("REPO_BASE_URL") ?: "https://apps.diamaneos.de"}\"")

        buildConfigField(String::class.java.name, "REPO_PUBLIC_KEY", "\"${repoPublicKey()}\"")

        buildConfigField(String::class.java.name, "REPO_KEY_VERSION",
            "\"${System.getenv("REPO_KEY_VERSION") ?: "0"}\"")
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
