package com.cactuslauncher.game

import org.freedesktop.dbus.annotations.DBusInterfaceName
import org.freedesktop.dbus.DBusPath
import org.freedesktop.dbus.connections.BusAddress
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.interfaces.DBus
import org.freedesktop.dbus.interfaces.DBusInterface
import org.freedesktop.dbus.interfaces.Properties
import org.freedesktop.dbus.messages.MethodCall
import org.freedesktop.dbus.types.Variant
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/** org.mpris.MediaPlayer2.Player methods the launcher uses. Names must match the D-Bus members. */
@DBusInterfaceName("org.mpris.MediaPlayer2.Player")
@Suppress("FunctionName")
interface MprisPlayer : DBusInterface {
    fun PlayPause()
    fun Next()
    fun Previous()
    fun SetPosition(trackId: DBusPath, position: Long)
    fun Seek(offset: Long)
}

internal data class MprisTrack(
    val busName: String,
    val title: String,
    val artist: String,
    val positionMicros: Long,
    val lengthMicros: Long,
    val isPlaying: Boolean,
    val artUrl: String,
    /** mpris:trackid when it is a usable object path; SetPosition needs it. */
    val trackId: String?,
    val canSeek: Boolean,
)

/**
 * Reads MPRIS players straight from the D-Bus session bus, so no playerctl or other helper is needed.
 * One connection is kept and reopened after a failure; a missing bus just yields no players.
 */
internal class LinuxMpris {
    private val lock = Any()
    private var connection: DBusConnection? = null
    private var retryAt = 0L
    @Volatile private var lastBusName: String? = null

    fun tracks(): List<MprisTrack> = synchronized(lock) {
        val bus = connect() ?: return emptyList()
        val names = try {
            bus.getRemoteObject(DBUS_NAME, DBUS_PATH, DBus::class.java, false).ListNames()
        } catch (_: Exception) {
            drop()
            return emptyList()
        }
        names.filter { it.startsWith(MPRIS_PREFIX) }.sorted().mapNotNull { name -> readPlayer(bus, name) }
    }

    /**
     * The player the sidebar shows: the one that is playing, else the one shown last time (so pausing
     * and resuming stays on the same player), else the first that has a title.
     */
    fun selected(): MprisTrack? {
        val tracks = tracks()
        val chosen = tracks.firstOrNull { it.isPlaying && it.busName == lastBusName }
            ?: tracks.firstOrNull { it.isPlaying }
            ?: tracks.firstOrNull { it.busName == lastBusName }
            ?: tracks.firstOrNull()
        lastBusName = chosen?.busName ?: lastBusName
        return chosen
    }

    fun control(command: TrackCommand) {
        val player = selected() ?: throw IOException("Не найден активный медиаплеер. Нужен плеер с поддержкой MPRIS.")
        synchronized(lock) {
            val bus = connection ?: throw IOException("Нет связи с медиаплеером.")
            try {
                val remote = bus.getRemoteObject(player.busName, MPRIS_PATH, MprisPlayer::class.java, false)
                when (command) {
                    TrackCommand.PLAY_PAUSE -> remote.PlayPause()
                    TrackCommand.NEXT -> remote.Next()
                    TrackCommand.PREVIOUS -> remote.Previous()
                }
            } catch (error: Exception) {
                if (!bus.isConnected) drop()
                throw IOException("Медиаплеер не принял команду.", error)
            }
        }
    }

    /**
     * Moves the selected player to [positionMicros]. SetPosition is exact; relative Seek is used only when
     * the player reports a real position, because some (Spotify) always claim 0.
     */
    fun seek(positionMicros: Long) {
        val player = selected() ?: throw IOException("Не найден активный медиаплеер. Нужен плеер с поддержкой MPRIS.")
        if (!player.canSeek) throw IOException("Этот плеер не поддерживает перемотку.")
        val target = positionMicros.coerceIn(0L, if (player.lengthMicros > 0) player.lengthMicros else Long.MAX_VALUE)
        synchronized(lock) {
            val bus = connection ?: throw IOException("Нет связи с медиаплеером.")
            try {
                val remote = bus.getRemoteObject(player.busName, MPRIS_PATH, MprisPlayer::class.java, false)
                val trackId = player.trackId
                if (trackId != null) {
                    try {
                        remote.SetPosition(DBusPath(trackId), target)
                        return
                    } catch (_: Exception) {
                        if (!bus.isConnected) throw IOException("Нет связи с медиаплеером.")
                    }
                }
                if (player.positionMicros <= 0L && target > 0L) {
                    throw IOException("Медиаплеер не принял перемотку.")
                }
                remote.Seek(target - player.positionMicros)
            } catch (error: Exception) {
                if (!bus.isConnected) drop()
                if (error is IOException) throw error
                throw IOException("Медиаплеер не принял перемотку.", error)
            }
        }
    }

    private fun readPlayer(bus: DBusConnection, name: String): MprisTrack? = try {
        val properties = bus.getRemoteObject(name, MPRIS_PATH, Properties::class.java, false)
        val values = properties.GetAll(PLAYER_INTERFACE)
        val metadata = (values["Metadata"]?.value as? Map<*, *>).orEmpty()
        val title = metadata.text("xesam:title")
        if (title.isBlank()) null
        else MprisTrack(
            busName = name,
            title = title,
            artist = metadata.text("xesam:artist"),
            positionMicros = (values["Position"]?.value as? Number)?.toLong()
                ?: runCatching { properties.Get<Any>(PLAYER_INTERFACE, "Position") as? Number }.getOrNull()?.toLong()
                ?: 0L,
            lengthMicros = metadata.number("mpris:length"),
            isPlaying = (values["PlaybackStatus"]?.value as? String).equals("Playing", ignoreCase = true),
            artUrl = metadata.text("mpris:artUrl"),
            trackId = metadata.objectPath("mpris:trackid"),
            // CanSeek is mandatory in MPRIS, but treat a player that leaves it out as seekable.
            canSeek = (values["CanSeek"]?.value as? Boolean) ?: true,
        )
    } catch (_: Exception) {
        // A player can vanish between ListNames and GetAll, or implement MPRIS partially.
        if (!bus.isConnected) drop()
        null
    }

    private fun connect(): DBusConnection? {
        connection?.let { if (it.isConnected) return it else drop() }
        val now = System.currentTimeMillis()
        if (now < retryAt) return null
        return try {
            val address = sessionBusAddress() ?: throw IOException("D-Bus session bus not found")
            MethodCall.setDefaultTimeout(CALL_TIMEOUT_MS)
            DBusConnectionBuilder.forAddress(address)
                .withShared(false)
                .transportConfig()
                // Point ServiceLoader at our own loader so the transport is found inside the uber JAR too.
                .withServiceLoaderClassLoader(LinuxMpris::class.java.classLoader)
                .withTimeout(CONNECT_TIMEOUT_MS)
                .back()
                .build()
                .also { connection = it }
        } catch (_: Throwable) {
            // No bus (headless, SSH, container) or an address we cannot use: stay quiet and try again later.
            retryAt = now + RETRY_DELAY_MS
            null
        }
    }

    private fun drop() {
        val old = connection
        connection = null
        retryAt = 0L
        if (old != null) runCatching { old.disconnect() }
    }

    private fun Map<*, *>.raw(key: String): Any? = this[key].let { if (it is Variant<*>) it.value else it }

    private fun Map<*, *>.text(key: String): String = when (val value = raw(key)) {
        is String -> value.trim()
        is Collection<*> -> value.mapNotNull { (it as? String)?.trim()?.takeIf(String::isNotEmpty) }.joinToString(", ")
        is Array<*> -> value.mapNotNull { (it as? String)?.trim()?.takeIf(String::isNotEmpty) }.joinToString(", ")
        else -> ""
    }

    private fun Map<*, *>.number(key: String): Long = (raw(key) as? Number)?.toLong() ?: 0L

    /** The value as a valid D-Bus object path other than the MPRIS "no track" placeholder, else null. */
    private fun Map<*, *>.objectPath(key: String): String? {
        val path = when (val value = raw(key)) {
            is DBusPath -> value.path
            is String -> value
            else -> null
        } ?: return null
        return path.takeIf { it != NO_TRACK && OBJECT_PATH.matches(it) }
    }

    private companion object {
        const val DBUS_NAME = "org.freedesktop.DBus"
        const val DBUS_PATH = "/org/freedesktop/DBus"
        const val MPRIS_PREFIX = "org.mpris.MediaPlayer2."
        const val MPRIS_PATH = "/org/mpris/MediaPlayer2"
        const val PLAYER_INTERFACE = "org.mpris.MediaPlayer2.Player"
        const val CALL_TIMEOUT_MS = 2_000L
        const val CONNECT_TIMEOUT_MS = 2_000
        const val RETRY_DELAY_MS = 10_000L
        const val NO_TRACK = "/org/mpris/MediaPlayer2/TrackList/NoTrack"
        val OBJECT_PATH = Regex("/|(/[A-Za-z0-9_]+)+")

        /**
         * DBUS_SESSION_BUS_ADDRESS may list several addresses; the JDK socket transport handles `unix:path=`
         * only, so prefer that and fall back to the systemd user bus socket in XDG_RUNTIME_DIR.
         */
        fun sessionBusAddress(): BusAddress? {
            val fromEnv = System.getenv("DBUS_SESSION_BUS_ADDRESS").orEmpty()
                .split(';').map(String::trim).filter(String::isNotEmpty)
            fromEnv.firstOrNull { it.startsWith("unix:") && "path=" in it }?.let { return BusAddress.of(it) }
            val runtimeDir = System.getenv("XDG_RUNTIME_DIR")?.takeIf(String::isNotBlank)
                ?: runCatching { "/run/user/${Files.getAttribute(Path.of("/proc/self"), "unix:uid")}" }.getOrNull()
            val socket = runtimeDir?.let { Path.of(it, "bus") }
            if (socket != null && Files.exists(socket)) return BusAddress.of("unix:path=$socket")
            return fromEnv.firstOrNull()?.let(BusAddress::of)
        }
    }
}
