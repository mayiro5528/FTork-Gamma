import com.android.build.gradle.internal.api.BaseVariantOutputImpl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.security.MessageDigest
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// ---------- 버전 자동 증가 ----------
// 프로젝트 폴더의 version.properties 에 버전 번호가 있어요.
// 코드나 설정을 수정한 뒤 APK 를 만들면(assemble / bundle / install) 버전이 0.1씩 올라가요.
//   0.3 → 0.4 → ... → 0.9 → 1.0 → 1.1
// 아무것도 수정하지 않고 다시 빌드하면 버전은 그대로예요.
// Android Studio 의 Gradle Sync 만 할 때도 올라가지 않아요.
val appName = "FTolk-G"
val versionFile = rootProject.file("version.properties")
val versionProps = Properties().apply {
    if (versionFile.exists()) versionFile.inputStream().use { load(it) }
}
var buildNumber = versionProps.getProperty("VERSION_CODE", "1").toInt()

/** app/src 아래 모든 파일과 빌드 설정 파일의 지문(SHA-256). 하나라도 바뀌면 값이 달라져요. */
fun sourceFingerprint(): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val files = fileTree("src").files + listOf(
        file("build.gradle.kts"), file("proguard-rules.pro"),
        rootProject.file("build.gradle.kts"), rootProject.file("settings.gradle.kts"),
        rootProject.file("gradle.properties"),
    ).filter { it.exists() }
    files.sortedBy { it.relativeTo(rootDir).invariantSeparatorsPath }.forEach { f ->
        digest.update(f.relativeTo(rootDir).invariantSeparatorsPath.toByteArray())
        digest.update(f.readBytes())
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

val isPackagingBuild = gradle.startParameter.taskNames.any { task ->
    val name = task.substringAfterLast(':')
    listOf("assemble", "bundle", "install", "package").any { name.startsWith(it) }
}
if (isPackagingBuild) {
    val fingerprint = sourceFingerprint()
    if (fingerprint != versionProps.getProperty("SOURCE_HASH")) {
        buildNumber += 1
        versionProps.setProperty("VERSION_CODE", buildNumber.toString())
        versionProps.setProperty("SOURCE_HASH", fingerprint)
        versionFile.outputStream().use {
            versionProps.store(it, "FTolk-G version. VERSION_CODE 3 = v0.3. Goes up by 1 (= +0.1) when sources change.")
        }
    }
}
// 버전 번호 3 → "0.3", 10 → "1.0", 12 → "1.2"
val appVersionName = "${buildNumber / 10}.${buildNumber % 10}"

android {
    namespace = "com.ftork.gamma"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.ftork.gamma"
        minSdk = 24
        targetSdk = 35
        versionCode = buildNumber
        versionName = appVersionName
        // 폰용 CPU 만 넣어서 APK 크기를 줄여요. (글자 인식 모델이 CPU 종류마다 들어가요)
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }

    // 배포용 서명 키. 키와 비밀번호는 프로젝트 밖(D:\A\FTork-Gamma-signing)에 있어서 GitHub 에 올라가지 않아요.
    // 다른 위치에 두려면 환경 변수 FTORK_SIGNING_PROPS 에 keystore.properties 경로를 넣으세요.
    val signingPropsFile = File(
        System.getenv("FTORK_SIGNING_PROPS") ?: rootDir.resolve("../FTork-Gamma-signing/keystore.properties").path
    )
    if (signingPropsFile.exists()) {
        val sp = Properties().apply { signingPropsFile.inputStream().use { load(it) } }
        signingConfigs.create("release") {
            storeFile = file(sp.getProperty("storeFile"))
            storePassword = sp.getProperty("storePassword")
            keyAlias = sp.getProperty("keyAlias")
            keyPassword = sp.getProperty("keyPassword")
        }
    }

    // 출력 파일 이름: FTolk-G-v0.6-release.apk
    applicationVariants.all {
        val variant = this
        outputs.all {
            (this as BaseVariantOutputImpl).outputFileName =
                "$appName-v${variant.versionName}-${variant.buildType.name}.apk"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            signingConfig = signingConfigs.findByName("release")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        buildConfig = true // 설정 화면 "앱 정보"에 버전을 보여 주려고 켰어요.
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    // 화면: FTalk Beta 의 index.html 을 WebView 로 띄워요.
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.webkit:webkit:1.12.1")
    implementation("androidx.exifinterface:exifinterface:1.3.7") // 사진 방향 바로잡기 (Android 8 이하)

    // 기기 안 번역 + 언어 감지 (Google ML Kit, 무료, API 키 불필요)
    implementation("com.google.mlkit:translate:17.0.3")
    implementation("com.google.mlkit:language-id:17.0.6")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.9.0")

    // 사진 번역: 사진 속 글자 인식 (라틴=영어·베트남어, 한국어, 중국어)
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("com.google.mlkit:text-recognition-korean:16.0.1")
    implementation("com.google.mlkit:text-recognition-chinese:16.0.1")

    // 실시간 카메라 번역: 미리보기 · 장면 분석 · 고화질 촬영 (CameraX)
    val cameraX = "1.4.1"
    implementation("androidx.camera:camera-core:$cameraX")
    implementation("androidx.camera:camera-camera2:$cameraX")
    implementation("androidx.camera:camera-lifecycle:$cameraX")
    implementation("androidx.camera:camera-view:$cameraX")
}
