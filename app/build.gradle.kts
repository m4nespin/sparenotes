import java.net.URI
import java.security.MessageDigest
import java.util.Properties

plugins {
    id("com.android.application")
}

val releaseSigningFile = rootProject.file(".release-signing/keystore.properties")
val releaseSigning = Properties().apply {
    if (releaseSigningFile.isFile) releaseSigningFile.inputStream().use(::load)
}

android {
    namespace = "app.sparenotes"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.sparenotes"
        minSdk = 30
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    signingConfigs {
        if (releaseSigningFile.isFile) {
            create("release") {
                storeFile = rootProject.file(releaseSigning.getProperty("storeFile"))
                storePassword = releaseSigning.getProperty("storePassword")
                keyAlias = releaseSigning.getProperty("keyAlias")
                keyPassword = releaseSigning.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isDebuggable = false
            signingConfig = signingConfigs.findByName("release")
        }
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
            keepDebugSymbols += setOf("**/libmusl_loader.so", "**/libproton_drive_cli.so")
        }
    }
}

val protonCli = layout.projectDirectory.file("src/main/jniLibs/arm64-v8a/libproton_drive_cli.so")
val protonCliUrl = "https://proton.me/download/drive/cli/0.8.0/linux-arm64-musl/proton-drive"
val protonCliOriginalSha512 = "FB386CAB36BC346E8BAE1F3E79EFDD14810DE748E762A2C88F384016199FF7211304CC0EC4D220C260C67B83BBE4D3A8D4DD2A2EA0E93B9FDD25C1E42F448165"
val protonCliPatchedSha256 = "3987BB50B3B3D7AF801AC0ABAF740BCD7E188656B84BB57CF314D85D9EA6F093"

fun ByteArray.digest(algorithm: String): String = MessageDigest.getInstance(algorithm)
    .digest(this)
    .joinToString("") { "%02X".format(it) }

val prepareProtonCli by tasks.registering {
    description = "Downloads, verifies, and patches Proton Drive CLI for Android."
    outputs.file(protonCli)
    doLast {
        val destination = protonCli.asFile
        if (destination.isFile && destination.readBytes().digest("SHA-256") == protonCliPatchedSha256) return@doLast

        logger.lifecycle("Downloading Proton Drive CLI 0.8.0 (ARM64 musl)…")
        val bytes = URI(protonCliUrl).toURL().openStream().use { it.readBytes() }
        check(bytes.digest("SHA-512") == protonCliOriginalSha512) { "Proton CLI checksum mismatch" }

        val needle = "function Hf(J){let Z=QX1(J);if(!Z)return;".toByteArray()
        val replacement = "function Hf(J){return;".padEnd(needle.size).toByteArray()
        var match = -1
        for (offset in 0..bytes.size - needle.size) {
            var equal = true
            for (index in needle.indices) {
                if (bytes[offset + index] != needle[index]) {
                    equal = false
                    break
                }
            }
            if (equal) {
                check(match == -1) { "Multiple Proton browser-launch functions found" }
                match = offset
            }
        }
        check(match >= 0) { "Proton browser-launch function not found" }
        replacement.copyInto(bytes, match)
        check(bytes.digest("SHA-256") == protonCliPatchedSha256) { "Patched Proton CLI checksum mismatch" }

        destination.parentFile.mkdirs()
        destination.writeBytes(bytes)
    }
}

tasks.named("preBuild") {
    dependsOn(prepareProtonCli)
}

dependencies {
    implementation("com.google.zxing:core:3.5.4")
    testImplementation("junit:junit:4.13.2")
}
