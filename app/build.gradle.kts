plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "app.drivescape"
    compileSdk = 35

    // AAOS Car API (CarPropertyManager: 속도·기어, CarUxRestrictionsManager: 주행 제한)
    useLibrary("android.car")

    defaultConfig {
        applicationId = "app.drivescape"
        minSdk = 28
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // 공모전 시연용: 별도 키스토어가 준비되기 전까지 debug 키로 서명해 설치 가능한 release APK를 만든다.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

tasks.withType<Test>().configureEach {
    // 오프라인 렌더 테스트의 디버그 시점 (예: -Pds.debugAt=31.88)
    project.findProperty("ds.debugAt")?.let { systemProperty("ds.debugAt", it.toString()) }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("ai.pleos.playground:Vehicle:2.3.2")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.media:media:1.7.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
