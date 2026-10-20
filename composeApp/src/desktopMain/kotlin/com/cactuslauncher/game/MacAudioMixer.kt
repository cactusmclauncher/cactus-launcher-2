package com.cactuslauncher.game

import com.cactuslauncher.model.AudioSession
import com.cactuslauncher.model.TrackSnapshot
import androidx.compose.ui.graphics.ImageBitmap
import org.jetbrains.skia.Image
import androidx.compose.ui.graphics.toComposeImageBitmap
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.net.HttpURLConnection

/** Bridge to the bundled Core Audio helper. All requests are serialized over one local process. */
internal class MacAudioMixer {
    private var cachedArtworkUrl: String? = null
    private var cachedArtwork: ImageBitmap? = null
    private var process: Process? = null
    private var input: BufferedWriter? = null
    private var output: BufferedReader? = null
    @Volatile private var metadataProcess: Process? = null
    @Volatile private var playerEnabled = true
    private var metadataInput: BufferedWriter? = null
    private var metadataOutput: BufferedReader? = null
    private val metadataLock = Any()
    private var cachedSystemArtworkData: String? = null
    private var cachedSystemArtwork: ImageBitmap? = null
    private var nextFallbackAt = 0L
    private var fallbackTrack: TrackSnapshot? = null

    init {
        Runtime.getRuntime().addShutdownHook(Thread { synchronized(this) {
            closeProcess()
            metadataProcess?.destroyForcibly()
        } })
    }

    @Synchronized
    fun sessions(): List<AudioSession> {
        ensureStarted()
        input!!.apply { write("LIST"); newLine(); flush() }
        val result = mutableListOf<AudioSession>()
        while (true) {
            val line = output!!.readLine() ?: failProcess()
            val fields = line.split('\t')
            when (fields.firstOrNull()) {
                "END" -> return result
                "ERROR" -> throw IOException(decode(fields.getOrNull(1)))
                "SESSION" -> if (fields.size == 5) {
                    result += AudioSession(
                        id = fields[1],
                        name = decode(fields[2]),
                        volume = fields[3].toFloatOrNull()?.coerceIn(0f, 1f) ?: 1f,
                        muted = fields[4].toBoolean(),
                    )
                }
            }
        }
    }

    @Synchronized
    fun setVolume(id: String, volume: Float) = command("VOLUME\t${validId(id)}\t${volume.coerceIn(0f, 1f)}")

    @Synchronized
    fun setMuted(id: String, muted: Boolean) = command("MUTE\t${validId(id)}\t$muted")

    @Synchronized
    fun controlTrack(trackCommand: TrackCommand) {
        if (!playerEnabled) return
        val key = when (trackCommand) {
            TrackCommand.PLAY_PAUSE -> 16
            TrackCommand.NEXT -> 17
            TrackCommand.PREVIOUS -> 18
        }
        command("TRACK\t$key")
    }

    fun currentTrack(): TrackSnapshot? = synchronized(metadataLock) {
        if (!playerEnabled) return@synchronized null
        val systemTrack = readSystemTrack()
        if (!playerEnabled) return@synchronized null
        // A paused system session is valid too; do not scan every browser.
        if (systemTrack != null) return@synchronized systemTrack
        val now = System.nanoTime()
        if (now < nextFallbackAt) return@synchronized fallbackTrack
        nextFallbackAt = now + java.util.concurrent.TimeUnit.SECONDS.toNanos(15)
        val spotifyScript = """
            if not (application "Spotify" is running) then return ""
            tell application "Spotify"
              if player state is stopped then return ""
              set t to current track
              return (name of t as text) & tab & (artist of t as text) & tab & (player position as text) & tab & ((duration of t) / 1000 as text) & tab & (player state as text) & tab & (artwork url of t as text)
            end tell
        """.trimIndent()
        val spotifyTrack = runCatching { runAppleScript(spotifyScript) }.getOrNull().orEmpty()
        val spotifyPlaying = spotifyTrack.split('\t').getOrNull(4) == "playing"
        val browserApps = if (spotifyPlaying) emptyList() else listOf("Helium", "Safari", "Google Chrome", "Microsoft Edge", "Brave Browser", "Vivaldi")
        var heliumError: IOException? = null
        val browserTracks = browserApps.mapNotNull { browser ->
            if (!playerEnabled) return@mapNotNull null
            try {
                readBrowserTrack(browser)
            } catch (error: IOException) {
                if (browser == "Helium" && !isApplicationNotRunning(error.message.orEmpty())) heliumError = error
                null
            }
        }
        if (!playerEnabled) return@synchronized null
        val browserTrack = browserTracks.firstOrNull { track ->
            track.substringAfter('\t').split('\t').getOrNull(4)?.let(::decodeMetadata) == "playing"
        } ?: browserTracks.firstOrNull()
        val output = when {
            browserTrack?.substringAfter('\t')?.split('\t')?.getOrNull(4)?.let(::decodeMetadata) == "playing" -> browserTrack
            spotifyTrack.isNotBlank() -> spotifyTrack
            else -> browserTrack.orEmpty()
        }
        if (output.isBlank()) {
            fallbackTrack = null
            heliumError?.let { throw it }
            return@synchronized null
        }
        val fromBrowser = output.startsWith("BROWSER\t")
        val fields = output.removePrefix("BROWSER\t").split('\t').map { if (fromBrowser) decodeMetadata(it) else it }
        if (fields.size < 6) return@synchronized null
        val artUrl = fields[5].replaceFirst("http://", "https://")
        val artwork = runCatching { loadArtwork(artUrl) }.getOrNull()
        TrackSnapshot(
            title = fields[0],
            artist = fields[1],
            positionSeconds = fields[2].toDoubleOrNull()?.toInt()?.coerceAtLeast(0) ?: 0,
            durationSeconds = fields[3].toDoubleOrNull()?.toInt()?.coerceAtLeast(0) ?: 0,
            isPlaying = fields[4].equals("playing", ignoreCase = true),
            artwork = artwork,
        ).also { fallbackTrack = it }
    }

    fun setPlayerEnabled(enabled: Boolean) {
        playerEnabled = enabled
        if (enabled) return
        // Interrupt an in-flight read before waiting for the metadata lock.
        metadataProcess?.destroyForcibly()
        synchronized(metadataLock) {
            metadataProcess?.destroyForcibly()
            runCatching { metadataInput?.close() }
            runCatching { metadataOutput?.close() }
            metadataProcess = null
            metadataInput = null
            metadataOutput = null
            cachedSystemArtworkData = null
            cachedSystemArtwork = null
            cachedArtworkUrl = null
            cachedArtwork = null
            fallbackTrack = null
            nextFallbackAt = 0L
        }
    }

    private fun readSystemTrack(): TrackSnapshot? = runCatching {
        if (!playerEnabled) return@runCatching null
        if (metadataProcess?.isAlive != true) {
            metadataInput?.close()
            metadataOutput?.close()
            val script = Files.createTempFile("cactus-now-playing-", ".swift")
            try {
                val resource = javaClass.getResourceAsStream("/NowPlaying.swift")
                    ?: throw IOException("Не найден скрипт Now Playing.")
                resource.use { Files.copy(it, script, java.nio.file.StandardCopyOption.REPLACE_EXISTING) }
                script.toFile().deleteOnExit()
                metadataProcess = ProcessBuilder("/usr/bin/swift", script.toString())
                    .redirectError(ProcessBuilder.Redirect.DISCARD).start().also {
                        metadataInput = it.outputStream.bufferedWriter(StandardCharsets.UTF_8)
                        metadataOutput = it.inputStream.bufferedReader(StandardCharsets.UTF_8)
                    }
            } catch (error: Exception) {
                Files.deleteIfExists(script)
                throw error
            }
        }
        metadataInput!!.apply { write("NOW_PLAYING"); newLine(); flush() }
        val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(25)
        while (!metadataOutput!!.ready()) {
            if (metadataProcess?.isAlive != true || System.nanoTime() >= deadline) {
                metadataProcess?.destroyForcibly()
                throw IOException("Скрипт Now Playing не ответил вовремя.")
            }
            Thread.sleep(20)
        }
        val fields = (metadataOutput!!.readLine() ?: throw IOException("Скрипт Now Playing завершился.")).split('\t')
        if (fields.firstOrNull() != "NOW_PLAYING" || fields.size != 7) return@runCatching null
        if (fields[6] != cachedSystemArtworkData) {
            cachedSystemArtwork = fields[6].takeIf { it.isNotBlank() }?.let { data ->
                runCatching { Image.makeFromEncoded(Base64.getDecoder().decode(data)).toComposeImageBitmap() }.getOrNull()
            }
            cachedSystemArtworkData = fields[6]
        }
        TrackSnapshot(
            title = decode(fields[1]), artist = decode(fields[2]),
            positionSeconds = fields[3].toDoubleOrNull()?.toInt()?.coerceAtLeast(0) ?: 0,
            durationSeconds = fields[4].toDoubleOrNull()?.toInt()?.coerceAtLeast(0) ?: 0,
            isPlaying = fields[5] == "playing", artwork = cachedSystemArtwork,
        )
    }.getOrNull()

    private fun readBrowserTrack(browser: String): String? {
        val javascript = """
            (()=>{const m=navigator.mediaSession?.metadata;const a=[...document.querySelectorAll('audio,video')];const v=a.find(x=>!x.paused&&!x.ended)||a.find(x=>x.currentTime>0);if(!m&&!v)return '';const art=m?.artwork?.[0]?.src||'';const playing=v?!v.paused&&!v.ended:navigator.mediaSession?.playbackState==='playing';const values=[m?.title||document.title||'',m?.artist||'',v?.currentTime||0,Number.isFinite(v?.duration)?v.duration:0,playing?'playing':'paused',art];return values.map(x=>encodeURIComponent(String(x))).join(String.fromCharCode(9))})()
        """.trimIndent().replace("\n", " ")
        val escapedJavascript = javascript.replace("\\", "\\\\").replace("\"", "\\\"")
        val appTarget = if (browser == "Helium") "application id \"net.imput.helium\"" else "application \"$browser\""
        val script = if (browser == "Safari") """
            if not ($appTarget is running) then return ""
            tell $appTarget
              set fallbackText to ""
              repeat with w in windows
                repeat with t in tabs of w
                  try
                    set resultText to do JavaScript "$escapedJavascript" in t
                    if resultText contains (tab & "playing" & tab) then return resultText
                    if fallbackText is "" and resultText is not "" then set fallbackText to resultText
                  end try
                end repeat
              end repeat
              return fallbackText
            end tell
            return ""
        """.trimIndent() else """
            if not ($appTarget is running) then return ""
            tell $appTarget
              set fallbackText to ""
              repeat with w in windows
                repeat with t in tabs of w
                  try
                    set resultText to execute t javascript "$escapedJavascript"
                    if resultText contains (tab & "playing" & tab) then return resultText
                    if fallbackText is "" and resultText is not "" then set fallbackText to resultText
                  end try
                end repeat
              end repeat
              return fallbackText
            end tell
            return ""
        """.trimIndent()
        return runAppleScript(script).takeIf { it.isNotBlank() }?.let { "BROWSER\t$it" }
    }

    private fun runAppleScript(script: String): String {
        val process = ProcessBuilder("/usr/bin/osascript", "-e", script).redirectErrorStream(true).start()
        if (!process.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)) {
            process.destroyForcibly()
            throw IOException("Не удалось получить данные активного медиаплеера.")
        }
        val output = process.inputStream.bufferedReader(StandardCharsets.UTF_8).readText().trim()
        if (process.exitValue() != 0) throw IOException(output.ifBlank { "AppleScript завершился с кодом ${process.exitValue()}." })
        return output
    }

    private fun isApplicationNotRunning(message: String): Boolean =
        message.contains("-600") || message.contains("not running", ignoreCase = true) ||
            message.contains("не запущено", ignoreCase = true) || message.contains("не удается получить application", ignoreCase = true)

    private fun decodeMetadata(value: String): String = runCatching {
        java.net.URLDecoder.decode(value, StandardCharsets.UTF_8)
    }.getOrDefault(value)

    private fun loadArtwork(url: String): ImageBitmap? {
        if (url == cachedArtworkUrl) return cachedArtwork
        val uri = java.net.URI(url)
        val host = uri.host ?: return null
        val allowedHost = host in setOf("i.scdn.co", "images.spotify.com", "googleusercontent.com", "ytimg.com") ||
            host.endsWith(".googleusercontent.com") || host.endsWith(".ytimg.com")
        if (uri.scheme != "https" || !allowedHost) return null
        val connection = uri.toURL().openConnection() as HttpURLConnection
        connection.connectTimeout = 1800
        connection.readTimeout = 1800
        connection.setRequestProperty("User-Agent", "CactusLauncher/2")
        return connection.inputStream.use { input ->
            val bytes = input.readNBytes(2 * 1024 * 1024 + 1)
            if (bytes.size > 2 * 1024 * 1024) return null
            Image.makeFromEncoded(bytes).toComposeImageBitmap().also {
                cachedArtworkUrl = url
                cachedArtwork = it
            }
        }
    }

    private fun command(value: String) {
        ensureStarted()
        input!!.apply { write(value); newLine(); flush() }
        val response = output!!.readLine() ?: failProcess()
        if (response.startsWith("ERROR\t")) throw IOException(decode(response.substringAfter('\t')))
        if (response != "OK") throw IOException("Неверный ответ аудиомикшера macOS.")
    }

    private fun ensureStarted() {
        if (process?.isAlive == true) return
        closeProcess()
        val bundle = extractHelperBundle()
        val executable = bundle.resolve("Contents/MacOS/CactusAudioMixer")
        if (!Files.isExecutable(executable)) throw IOException("В сборку не включён helper Core Audio для macOS.")
        process = ProcessBuilder(executable.toString()).redirectError(ProcessBuilder.Redirect.DISCARD).start().also { child ->
            input = BufferedWriter(OutputStreamWriter(child.outputStream, StandardCharsets.UTF_8))
            output = BufferedReader(InputStreamReader(child.inputStream, StandardCharsets.UTF_8))
        }
    }

    private fun extractHelperBundle(): Path {
        val resourceRoot = "/CactusAudioMixer.app/Contents/"
        val executableResource = javaClass.getResourceAsStream(resourceRoot + "MacOS/CactusAudioMixer")
            ?: throw IOException("Не найден ресурс Core Audio helper: CactusAudioMixer")
        val version = executableResource.use {
            val digest = java.security.MessageDigest.getInstance("SHA-256").digest(it.readBytes())
            java.util.HexFormat.of().formatHex(digest, 0, 8)
        }
        val bundle = Path.of(System.getProperty("java.io.tmpdir"), "cactus-launcher-audio-helper", version, "CactusAudioMixer.app")
        val executable = bundle.resolve("Contents/MacOS/CactusAudioMixer")
        val info = bundle.resolve("Contents/Info.plist")
        val signature = bundle.resolve("Contents/_CodeSignature/CodeResources")
        val notices = bundle.resolve("Contents/Resources/THIRD_PARTY_NOTICES.txt")
        if (Files.isExecutable(executable) && Files.exists(info) && Files.exists(signature) && Files.exists(notices)) return bundle
        Files.createDirectories(executable.parent)
        Files.createDirectories(signature.parent)
        Files.createDirectories(notices.parent)
        listOf(
            "MacOS/CactusAudioMixer" to executable,
            "Info.plist" to info,
            "_CodeSignature/CodeResources" to signature,
            "Resources/THIRD_PARTY_NOTICES.txt" to notices,
        ).forEach { (resourcePath, destination) ->
            val stream = javaClass.getResourceAsStream(resourceRoot + resourcePath)
                ?: throw IOException("Не найден ресурс Core Audio helper: $resourcePath")
            stream.use { Files.copy(it, destination, java.nio.file.StandardCopyOption.REPLACE_EXISTING) }
        }
        if (!executable.toFile().setExecutable(true, true)) throw IOException("Не удалось подготовить Core Audio helper.")
        return bundle
    }

    private fun closeProcess() {
        runCatching { input?.apply { write("QUIT"); newLine(); flush() } }
        runCatching { process?.waitFor(2, java.util.concurrent.TimeUnit.SECONDS) }
        process?.takeIf { it.isAlive }?.destroyForcibly()
        runCatching { input?.close() }
        runCatching { output?.close() }
        process = null
        input = null
        output = null
    }

    private fun failProcess(): Nothing {
        val exit = process?.let { if (it.isAlive) "не ответил" else "завершился с кодом ${it.exitValue()}" } ?: "не запущен"
        closeProcess()
        throw IOException("Helper аудиомикшера macOS $exit.")
    }

    private fun validId(id: String): String = id.takeIf { it.matches(Regex("[0-9]+")) }
        ?: throw IOException("Некорректный идентификатор аудиопроцесса.")

    private fun decode(value: String?): String = runCatching {
        String(Base64.getDecoder().decode(value ?: ""), StandardCharsets.UTF_8)
    }.getOrDefault("Ошибка Core Audio.")
}
