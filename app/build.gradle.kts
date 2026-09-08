plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.halovoid.lncrawlersources"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.halovoid.lncrawlersources"
        minSdk = 24
        targetSdk = 37
        versionCode = 21
        versionName = "1.0.20"

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
    compileOnly(libs.lncrawler)
}