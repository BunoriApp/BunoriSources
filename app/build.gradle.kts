plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.halovoid.bunorisources"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.halovoid.bunorisources"
        minSdk = 24
        targetSdk = 37
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    testImplementation(libs.junit)
    implementation(libs.jsoup)
    implementation(libs.okhttp)
    compileOnly(libs.extension.api)
    compileOnly(libs.lncrawler)
    testImplementation(libs.extension.api)
    testImplementation(libs.lncrawler)
}

tasks.register<Exec>("packageExtensions") {
    group = "bunori"
    description = "Compiles Kotlin sources and packages all extensions into .bext archives with repo/index.json"
    dependsOn("compileReleaseKotlin")
    workingDir = rootProject.rootDir
    environment("PATH", System.getenv("PATH") ?: "")
    environment("JAVA_HOME", System.getProperty("java.home"))
    commandLine("python3", "tools/package_extensions.py")
}