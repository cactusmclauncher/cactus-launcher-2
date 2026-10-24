package com.cactuslauncher.game

import com.cactuslauncher.model.TrackSnapshot
import org.jetbrains.skia.Image
import androidx.compose.ui.graphics.toComposeImageBitmap
import java.io.IOException
import java.net.URI
import java.util.Base64
import java.util.concurrent.TimeUnit

internal class DesktopMediaPlayer {
    @Volatile private var enabled = true
    private var artworkUri: String? = null
    private var artwork: androidx.compose.ui.graphics.ImageBitmap? = null
    private val mpris by lazy { LinuxMpris() }

    fun setEnabled(value: Boolean) {
        enabled = value
    }

    fun currentTrack(): TrackSnapshot? {
        if (!enabled) return null
        return when {
            isWindows() -> windowsTrack()
            isLinux() -> linuxTrack()
            else -> null
        }
    }

    fun control(command: TrackCommand) {
        if (!enabled) return
        when {
            isWindows() -> windowsControl(command)
            isLinux() -> linuxControl(command)
            else -> throw IOException("Управление треками на этой платформе недоступно.")
        }
    }

    fun seek(positionSeconds: Int) {
        if (!enabled) return
        val seconds = positionSeconds.coerceAtLeast(0)
        when {
            isWindows() -> windowsSeek(seconds)
            isLinux() -> mpris.seek(seconds * 1_000_000L)
            else -> throw IOException("Перемотка на этой платформе недоступна.")
        }
    }

    private fun linuxTrack(): TrackSnapshot? {
        val track = mpris.selected() ?: return null
        return TrackSnapshot(
            title = track.title,
            artist = track.artist,
            positionSeconds = (track.positionMicros / 1_000_000L).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt(),
            durationSeconds = (track.lengthMicros / 1_000_000L).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt(),
            isPlaying = track.isPlaying,
            artwork = loadArtwork(track.artUrl),
            canSeek = track.canSeek,
        )
    }

    private fun linuxControl(command: TrackCommand) = mpris.control(command)

    private fun windowsTrack(): TrackSnapshot? {
        val script = """
            @@ErrorActionPreference = 'Stop'
            [Console]::OutputEncoding = [System.Text.Encoding]::UTF8
            Add-Type -AssemblyName System.Runtime.WindowsRuntime
            function Wait-Operation(@@operation) {
              while (@@operation.Status -eq [Windows.Foundation.AsyncStatus]::Started) { Start-Sleep -Milliseconds 20 }
              if (@@operation.Status -ne [Windows.Foundation.AsyncStatus]::Completed) { return @@null }
              return @@operation.GetResults()
            }
            @@manager = Wait-Operation ([Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager, Windows.Media.Control, ContentType=WindowsRuntime]::RequestAsync())
            if (@@null -eq @@manager) { exit 0 }
            @@session = @@manager.GetCurrentSession()
            if (@@null -eq @@session) { exit 0 }
            @@properties = Wait-Operation (@@session.TryGetMediaPropertiesAsync())
            if (@@null -eq @@properties) { exit 0 }
            @@status = @@session.GetPlaybackInfo().PlaybackStatus.ToString()
            @@timeline = @@session.GetTimelineProperties()
            @@canSeek = @@true
            try { @@canSeek = [bool]@@session.GetPlaybackInfo().Controls.IsPlaybackPositionEnabled } catch {}
            @@title = [uri]::EscapeDataString([string]@@properties.Title)
            @@artist = [uri]::EscapeDataString([string](@@properties.Artist -join ', '))
            @@cover = ''
            try {
              @@thumbnail = @@properties.Thumbnail
              if (@@null -ne @@thumbnail) {
                @@randomAccess = Wait-Operation (@@thumbnail.OpenReadAsync())
                if (@@null -ne @@randomAccess -and @@randomAccess.Size -le 4000000) {
                  @@source = [System.IO.WindowsRuntimeStreamExtensions]::AsStreamForRead(@@randomAccess)
                  @@memory = New-Object System.IO.MemoryStream
                  @@source.CopyTo(@@memory)
                  @@cover = [Convert]::ToBase64String(@@memory.ToArray())
                  @@source.Dispose()
                  @@memory.Dispose()
                }
              }
            } catch {}
            [Console]::WriteLine("@@title`t@@artist`t@@([int]@@timeline.Position.TotalSeconds)`t@@([int]@@timeline.EndTime.TotalSeconds)`t@@(@@status -eq 'Playing')`t@@cover`t@@canSeek")
        """.trimIndent().replace("@@", '$'.toString())
        val result = run(powerShell(script))
        if (result.exitCode != 0) return null
        val fields = result.output.lineSequence().firstOrNull()?.split('\t') ?: return null
        if (fields.size < 5) return null
        val title = decodeField(fields[0])
        if (title.isBlank()) return null
        return TrackSnapshot(
            title = title,
            artist = decodeField(fields[1]),
            positionSeconds = fields[2].toIntOrNull()?.coerceAtLeast(0) ?: 0,
            durationSeconds = fields[3].toIntOrNull()?.coerceAtLeast(0) ?: 0,
            isPlaying = fields[4].toBoolean(),
            artwork = fields.getOrNull(5)?.let(::loadEncodedArtwork),
            canSeek = fields.getOrNull(6)?.let { it.equals("true", ignoreCase = true) } ?: true,
        )
    }

    private fun loadEncodedArtwork(value: String): androidx.compose.ui.graphics.ImageBitmap? {
        if (value.isBlank()) return null
        if (artworkUri == "base64:$value") return artwork
        artworkUri = "base64:$value"
        artwork = runCatching { Image.makeFromEncoded(Base64.getDecoder().decode(value)).toComposeImageBitmap() }.getOrNull()
        return artwork
    }

    private fun windowsControl(command: TrackCommand) {
        val operation = when (command) {
            TrackCommand.PLAY_PAUSE -> "TryTogglePlayPauseAsync()"
            TrackCommand.NEXT -> "TrySkipNextAsync()"
            TrackCommand.PREVIOUS -> "TrySkipPreviousAsync()"
        }
        if (!windowsSessionCall(operation)) throw IOException("Windows не передала команду активному медиаплееру.")
    }

    /** GSMTC positions are TimeSpan ticks (100 ns). */
    private fun windowsSeek(positionSeconds: Int) {
        val ticks = positionSeconds.toLong() * 10_000_000L
        if (!windowsSessionCall("TryChangePlaybackPositionAsync([long]$ticks)")) {
            throw IOException("Медиаплеер не принял перемотку.")
        }
    }

    /** Runs one Try*Async call on the current media session; true when the player accepted it. */
    private fun windowsSessionCall(operation: String): Boolean {
        val script = """
            @@ErrorActionPreference = 'Stop'
            [Console]::OutputEncoding = [System.Text.Encoding]::UTF8
            Add-Type -AssemblyName System.Runtime.WindowsRuntime
            function Wait-Operation(@@operation) {
              while (@@operation.Status -eq [Windows.Foundation.AsyncStatus]::Started) { Start-Sleep -Milliseconds 20 }
              if (@@operation.Status -eq [Windows.Foundation.AsyncStatus]::Completed) { return @@operation.GetResults() }
              return @@false
            }
            @@manager = Wait-Operation ([Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager, Windows.Media.Control, ContentType=WindowsRuntime]::RequestAsync())
            if (@@null -eq @@manager) { exit 2 }
            @@session = @@manager.GetCurrentSession()
            if (@@null -eq @@session) { exit 3 }
            @@accepted = Wait-Operation (@@session.__MEDIA_OPERATION__)
            if (-not @@accepted) { exit 4 }
        """.trimIndent().replace("@@", '$'.toString()).replace("__MEDIA_OPERATION__", operation)
        return run(powerShell(script)).exitCode == 0
    }

    private fun powerShell(script: String): List<String> {
        val encoded = Base64.getEncoder().encodeToString(script.toByteArray(Charsets.UTF_16LE))
        val executable = "powershell.exe"
        return listOf(executable, "-NoLogo", "-NoProfile", "-NonInteractive", "-EncodedCommand", encoded)
    }

    private fun run(command: List<String>): CommandResult = try {
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        val finished = process.waitFor(3, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            CommandResult(-1, "")
        } else CommandResult(process.exitValue(), process.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }.trim())
    } catch (_: Exception) {
        CommandResult(-1, "")
    }

    private fun loadArtwork(value: String): androidx.compose.ui.graphics.ImageBitmap? {
        if (value.isBlank()) return null
        if (artworkUri == value) return artwork
        artworkUri = value
        artwork = runCatching {
            val uri = URI(value)
            val bytes = when (uri.scheme?.lowercase()) {
                "file" -> java.nio.file.Path.of(uri).let { path ->
                    if (java.nio.file.Files.size(path) <= 4_000_000) java.nio.file.Files.readAllBytes(path) else null
                }
                "http", "https" -> {
                    val connection = uri.toURL().openConnection() as java.net.HttpURLConnection
                    connection.connectTimeout = 1500
                    connection.readTimeout = 1500
                    connection.instanceFollowRedirects = false
                    if (connection.responseCode !in 200..299 || connection.contentLengthLong > 4_000_000) null
                    else connection.inputStream.use { it.readNBytes(4_000_001).takeIf { data -> data.size <= 4_000_000 } }
                }
                else -> null
            }
            bytes?.let { Image.makeFromEncoded(it).toComposeImageBitmap() }
        }.getOrNull()
        return artwork
    }

    private fun decodeField(value: String) = runCatching { java.net.URLDecoder.decode(value, Charsets.UTF_8) }.getOrDefault(value)
    private fun isWindows() = System.getProperty("os.name", "").lowercase().contains("win")
    private fun isLinux() = System.getProperty("os.name", "").lowercase().contains("linux")
    private data class CommandResult(val exitCode: Int, val output: String)
}
