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

    private fun linuxTrack(): TrackSnapshot? {
        val tracks = linuxTracks()
        return tracks.firstOrNull { it.second.isPlaying }?.second ?: tracks.firstOrNull()?.second
    }

    private fun linuxTracks(): List<Pair<String, TrackSnapshot>> {
        val separator = "\u001f"
        val format = listOf("{{playerName}}", "{{title}}", "{{artist}}", "{{position}}", "{{mpris:length}}", "{{status}}", "{{mpris:artUrl}}").joinToString(separator)
        val result = run(listOf("playerctl", "--all-players", "metadata", "--format", format))
        if (result.exitCode != 0) return emptyList()
        return result.output.lineSequence().mapNotNull { line ->
            val fields = line.split(separator)
            if (fields.size < 7 || fields[1].isBlank()) return@mapNotNull null
            val position = fields[3].toDoubleOrNull()?.toInt() ?: 0
            val duration = fields[4].toLongOrNull()?.div(1_000_000L)?.toInt() ?: 0
            fields[0] to TrackSnapshot(
                title = fields[1].trim(),
                artist = fields[2].trim(),
                positionSeconds = position.coerceAtLeast(0),
                durationSeconds = duration.coerceAtLeast(0),
                isPlaying = fields[5].equals("Playing", true),
                artwork = loadArtwork(fields[6]),
            )
        }.toList()
    }

    private fun linuxControl(command: TrackCommand) {
        val sessions = linuxTracks()
        val selected = sessions.firstOrNull { it.second.isPlaying } ?: sessions.firstOrNull()
            ?: throw IOException("Не найден активный медиаплеер. Установите playerctl и проверьте поддержку MPRIS.")
        val player = selected.first.takeIf { it.matches(Regex("[A-Za-z0-9_.-]+")) }
            ?: throw IOException("Не удалось определить активный медиаплеер.")
        val action = when (command) {
            TrackCommand.PLAY_PAUSE -> "play-pause"
            TrackCommand.NEXT -> "next"
            TrackCommand.PREVIOUS -> "previous"
        }
        val result = run(listOf("playerctl", "--player", player, action))
        if (result.exitCode != 0) throw IOException(result.output.ifBlank { "Медиаплеер не принял команду." })
    }

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
            [Console]::WriteLine("@@title`t@@artist`t@@([int]@@timeline.Position.TotalSeconds)`t@@([int]@@timeline.EndTime.TotalSeconds)`t@@(@@status -eq 'Playing')`t@@cover")
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
            TrackCommand.PLAY_PAUSE -> "TryTogglePlayPauseAsync"
            TrackCommand.NEXT -> "TrySkipNextAsync"
            TrackCommand.PREVIOUS -> "TrySkipPreviousAsync"
        }
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
            @@accepted = Wait-Operation (@@session.__MEDIA_OPERATION__())
            if (-not @@accepted) { exit 4 }
        """.trimIndent().replace("@@", '$'.toString()).replace("__MEDIA_OPERATION__", operation)
        val result = run(powerShell(script))
        if (result.exitCode != 0) throw IOException("Windows не передала команду активному медиаплееру.")
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
                "https" -> {
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
