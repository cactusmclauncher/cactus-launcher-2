import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.gradle.api.tasks.Exec
import java.util.Properties

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

kotlin {
    jvmToolchain(21)
    // Desktop only: Windows, macOS, Linux.
    jvm("desktop")

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(compose.runtime)
                implementation(compose.foundation)
                implementation(compose.ui)
                implementation(compose.components.resources)
            }
        }
        val desktopMain by getting {
            dependencies {
                // -PallPlatforms bundles Skia for every desktop OS, so one JAR runs on Windows, macOS and Linux.
                if (providers.gradleProperty("allPlatforms").isPresent) {
                    implementation(compose.desktop.windows_x64)
                    implementation(compose.desktop.macos_x64)
                    implementation(compose.desktop.macos_arm64)
                    implementation(compose.desktop.linux_x64)
                    implementation(compose.desktop.linux_arm64)
                } else {
                    implementation(compose.desktop.currentOs)
                }
                implementation(project(":gamecore"))
            }
        }
    }
}

val launcherVersion = providers.gradleProperty("launcherVersion").orElse("2.0.0").map { it.removePrefix("v") }
val launcherRepository = providers.gradleProperty("updateRepository")
    .orElse(providers.environmentVariable("GITHUB_REPOSITORY"))
    .orElse("cactusmclauncher/cactus-launcher-2")
val launcherBuildInfoDir = layout.buildDirectory.dir("generated/launcherBuildInfo")
val generateLauncherBuildInfo by tasks.registering {
    inputs.property("launcherVersion", launcherVersion)
    inputs.property("launcherRepository", launcherRepository)
    outputs.dir(launcherBuildInfoDir)
    doLast {
        val version = launcherVersion.get()
        val repository = launcherRepository.get()
        require(version.matches(Regex("\\d+\\.\\d+\\.\\d+"))) { "launcherVersion must use major.minor.patch" }
        require(repository.isEmpty() || repository.matches(Regex("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+"))) {
            "updateRepository must use owner/repository"
        }
        val resource = launcherBuildInfoDir.get().file("cactus-build.properties").asFile
        resource.parentFile.mkdirs()
        Properties().apply {
            setProperty("version", version)
            setProperty("repository", repository)
        }.store(resource.outputStream(), null)
    }
}
kotlin.sourceSets.getByName("desktopMain").resources.srcDir(launcherBuildInfoDir)
tasks.matching { it.name == "desktopProcessResources" }.configureEach {
    dependsOn(generateLauncherBuildInfo)
}

// The Core Audio tap bridge is compiled and bundled only on macOS hosts. The
// helper runs out-of-process so its realtime IOProc never blocks the Compose UI.
val macAudioHelperDir = layout.buildDirectory.dir("generated/resources/macosAudioMixer")
val macAudioHelperBundle = macAudioHelperDir.map { it.dir("CactusAudioMixer.app") }
val macAudioHelperSource = layout.projectDirectory.file("../gamecore/src/main/native/macos/CactusAudioMixer.swift")
val macAudioHelperNotice = layout.projectDirectory.file("../gamecore/src/main/native/macos/NOTICE.md")
val isMacHost = System.getProperty("os.name", "").lowercase().contains("mac")
if (isMacHost) {
    kotlin.sourceSets.getByName("desktopMain").resources.srcDir(macAudioHelperDir)
    val compileMacAudioHelper by tasks.registering(Exec::class) {
        val appBundle = macAudioHelperBundle.get().asFile
        val executable = appBundle.resolve("Contents/MacOS/CactusAudioMixer")
        val compatibleSdk = listOf(
            "/Library/Developer/CommandLineTools/SDKs/MacOSX15.4.sdk",
            "/Applications/Xcode.app/Contents/Developer/Platforms/MacOSX.platform/Developer/SDKs/MacOSX15.4.sdk",
        ).firstOrNull { file(it).isDirectory }
        val arch = if (System.getProperty("os.arch").lowercase() in setOf("aarch64", "arm64")) "arm64" else "x86_64"
        inputs.file(macAudioHelperSource)
        inputs.file(macAudioHelperNotice)
        outputs.dir(appBundle)
        doFirst {
            executable.parentFile.mkdirs()
            val noticeFile = appBundle.resolve("Contents/Resources/THIRD_PARTY_NOTICES.txt")
            noticeFile.parentFile.mkdirs()
            noticeFile.writeText(macAudioHelperNotice.asFile.readText())
            appBundle.resolve("Contents/Info.plist").writeText("""
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
                <plist version="1.0"><dict>
                  <key>CFBundleIdentifier</key><string>com.cactuslauncher.audiohelper</string>
                  <key>CFBundleName</key><string>Cactus Audio Mixer</string>
                  <key>CFBundleExecutable</key><string>CactusAudioMixer</string>
                  <key>CFBundlePackageType</key><string>APPL</string>
                  <key>LSUIElement</key><true/>
                  <key>NSAudioCaptureUsageDescription</key><string>Лаунчер обрабатывает звук выбранных приложений, чтобы независимо регулировать их громкость.</string>
                </dict></plist>
            """.trimIndent())
        }
        commandLine(
            buildList {
                addAll(listOf("swiftc", "-O", "-target", "$arch-apple-macosx14.2"))
                if (compatibleSdk != null) addAll(listOf("-sdk", compatibleSdk))
                addAll(listOf(macAudioHelperSource.asFile.absolutePath,
            "-framework", "CoreAudio", "-framework", "AppKit", "-o", executable.absolutePath,
                ))
            },
        )
        doLast {
            exec { commandLine("codesign", "--force", "--deep", "--sign", "-", appBundle.absolutePath) }
        }
    }
    // Now Playing helper: compiled here instead of running `/usr/bin/swift script.swift` on the user's Mac,
    // which needs Xcode Command Line Tools and prompts to install them when missing.
    val nowPlayingSource = layout.projectDirectory.file("../gamecore/src/main/native/macos/CactusNowPlaying.swift")
    val nowPlayingDir = layout.buildDirectory.dir("generated/resources/macosNowPlaying")
    kotlin.sourceSets.getByName("desktopMain").resources.srcDir(nowPlayingDir)
    val compileNowPlayingHelper by tasks.registering(Exec::class) {
        val executable = nowPlayingDir.get().file("CactusNowPlaying").asFile
        val compatibleSdk = listOf(
            "/Library/Developer/CommandLineTools/SDKs/MacOSX15.4.sdk",
            "/Applications/Xcode.app/Contents/Developer/Platforms/MacOSX.platform/Developer/SDKs/MacOSX15.4.sdk",
        ).firstOrNull { file(it).isDirectory }
        val arch = if (System.getProperty("os.arch").lowercase() in setOf("aarch64", "arm64")) "arm64" else "x86_64"
        inputs.file(nowPlayingSource)
        outputs.file(executable)
        doFirst { executable.parentFile.mkdirs() }
        commandLine(
            buildList {
                addAll(listOf("swiftc", "-O", "-target", "$arch-apple-macosx14.2"))
                if (compatibleSdk != null) addAll(listOf("-sdk", compatibleSdk))
                addAll(listOf(nowPlayingSource.asFile.absolutePath, "-framework", "Foundation", "-o", executable.absolutePath))
            },
        )
        doLast {
            exec { commandLine("codesign", "--force", "--sign", "-", executable.absolutePath) }
        }
    }
    tasks.matching { it.name == "desktopProcessResources" }.configureEach {
        dependsOn(compileMacAudioHelper)
        dependsOn(compileNowPlayingHelper)
    }
}

compose.resources {
    packageOfResClass = "com.cactuslauncher.resources"
}

compose.desktop {
    application {
        mainClass = "com.cactuslauncher.MainKt"
        // macOS: native title bar follows the system dark/light appearance.
        jvmArgs += listOf("-Dapple.awt.application.appearance=system")
        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "CactusLauncher"
            packageVersion = launcherVersion.get()
            // JNDI DNS for the _minecraft._tcp SRV lookup of servers.
            modules("java.naming", "jdk.naming.dns")
        }
    }
}

// One JAR for Windows, macOS and Linux: ./gradlew :composeApp:packageUniversalJar -PallPlatforms
val packageUniversalJar by tasks.registering(Copy::class) {
    dependsOn("packageUberJarForCurrentOS")
    doFirst { require(providers.gradleProperty("allPlatforms").isPresent) { "Run with -PallPlatforms" } }
    from(layout.buildDirectory.dir("compose/jars")) { include("*.jar") }
    into(layout.buildDirectory.dir("compose/universal"))
    rename { "CactusLauncher-${launcherVersion.get()}-universal.jar" }
}
