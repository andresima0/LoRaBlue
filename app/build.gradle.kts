plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.android.lorablue"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.android.lorablue"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    // Paho's MQTT client packages a duplicate META-INF license/notice file
    // that collides with other libraries during packaging. Without this
    // exclusion the build fails at the merge step with a "More than one
    // file was found with OS independent path" error.
    packaging {
        resources {
            excludes += "META-INF/NOTICE.md"
            excludes += "META-INF/LICENSE.md"
        }
    }
}

dependencies {
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    implementation("androidx.activity:activity-ktx:1.8.0")
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.7.0")

    // Required by MqttConfigDialog (DialogFragment + supportFragmentManager)
    implementation("androidx.fragment:fragment-ktx:1.6.2")

    // Konker MQTT publishing — same Paho client used by the wifiradar
    // reference app. org.eclipse.paho.client.mqttv3 alone is enough for a
    // plain TCP MQTT client (MqttPublisher uses MemoryPersistence, no
    // android.service component, so the android.service artifact is not
    // needed here).
    implementation("org.eclipse.paho:org.eclipse.paho.client.mqttv3:1.2.5")
}