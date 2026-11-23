import java.net.URI
import java.security.MessageDigest
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}
val libboxVersion = "1.14.4"
val libboxSha256 = "d2685924a657705beda44e94c6dc499e781df1ad20d65474e3591ec2f8b96465"
val libboxFile = rootProject.file("app/libs/libbox.aar")
val downloadLibbox by tasks.registering {
    outputs.file(libboxFile)
    onlyIf { !libboxFile.exists() }
    doLast {
        libboxFile.parentFile.mkdirs()
        val url = "https://github.com/singbox-android/libbox/releases/download/$libboxVersion/libbox.aar"
        val temp = File.createTempFile("libbox", ".aar", libboxFile.parentFile)
        try {
            URI(url).toURL().openStream().use { input -> temp.outputStream().use { output -> input.copyTo(output) } }
            val digest = MessageDigest.getInstance("SHA-256")
            temp.inputStream().use { input ->
                val buffer = ByteArray(1024 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            check(actual == libboxSha256) { "libbox checksum mismatch: expected $libboxSha256, got $actual" }
            check(temp.renameTo(libboxFile)) { "Cannot install libbox.aar" }
        } finally { temp.delete() }
    }
}
tasks.named("preBuild") { dependsOn(downloadLibbox) }
android {
    namespace = "com.artvpn.app"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.artvpn.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 4
        versionName = "0.4.0"
        ndk {
            abiFilters += "arm64-v8a"
        }
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    packaging { jniLibs { useLegacyPackaging = true } }
}
dependencies {
    implementation(files(libboxFile))
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation(platform("androidx.compose:compose-bom:2025.01.00"))
    implementation("androidx.compose.animation:animation")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
