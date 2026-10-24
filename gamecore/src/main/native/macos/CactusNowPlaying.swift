import Foundation

// Compiled at build time into the CactusNowPlaying helper (composeApp/build.gradle.kts, macOS hosts only)
// and started once by MacAudioMixer; it answers NOW_PLAYING and SEEK<TAB>seconds requests over stdin/stdout.
// Note: newer macOS releases may return an empty dictionary to non-Apple binaries; the launcher then
// falls back to Spotify/browser AppleScript lookups.
let handle = dlopen("/System/Library/PrivateFrameworks/MediaRemote.framework/MediaRemote", RTLD_LAZY)
typealias Getter = @convention(c) (DispatchQueue, @escaping @convention(block) (NSDictionary?) -> Void) -> Void
let symbol = handle.flatMap { dlsym($0, "MRMediaRemoteGetNowPlayingInfo") }
typealias ElapsedTimeSetter = @convention(c) (Double) -> Void
let setElapsedTime = handle.flatMap { dlsym($0, "MRMediaRemoteSetElapsedTime") }
func encoded(_ text: String) -> String { Data(text.utf8).base64EncodedString() }
while let command = readLine() {
    if command == "QUIT" { break }
    if command.hasPrefix("SEEK\t") {
        // Replies OK once the request is handed to MediaRemote; it reports no result of its own.
        guard let setElapsedTime, let seconds = Double(command.dropFirst(5)), seconds.isFinite, seconds >= 0 else {
            print("UNSUPPORTED"); fflush(stdout); continue
        }
        unsafeBitCast(setElapsedTime, to: ElapsedTimeSetter.self)(seconds)
        print("OK"); fflush(stdout); continue
    }
    guard command == "NOW_PLAYING", let symbol else {
        print("EMPTY"); fflush(stdout); continue
    }
    var finished = false
    var response = "EMPTY"
    unsafeBitCast(symbol, to: Getter.self)(.main) { info in
        defer { finished = true }
        guard let info, let title = info["kMRMediaRemoteNowPlayingInfoTitle"] as? String, !title.isEmpty else { return }
        let artist = info["kMRMediaRemoteNowPlayingInfoArtist"] as? String ?? ""
        let duration = (info["kMRMediaRemoteNowPlayingInfoDuration"] as? NSNumber)?.doubleValue ?? 0
        let rate = (info["kMRMediaRemoteNowPlayingInfoPlaybackRate"] as? NSNumber)?.doubleValue ?? 0
        var elapsed = (info["kMRMediaRemoteNowPlayingInfoElapsedTime"] as? NSNumber)?.doubleValue ?? 0
        if rate > 0, let date = info["kMRMediaRemoteNowPlayingInfoTimestamp"] as? Date {
            elapsed += max(0, Date().timeIntervalSince(date)) * rate
        }
        elapsed = max(0, duration > 0 ? min(elapsed, duration) : elapsed)
        let art = (info["kMRMediaRemoteNowPlayingInfoArtworkData"] as? Data)?.base64EncodedString() ?? ""
        response = ["NOW_PLAYING", encoded(title), encoded(artist), String(elapsed), String(duration), rate > 0 ? "playing" : "paused", art].joined(separator: "\t")
    }
    let deadline = Date().addingTimeInterval(1.5)
    while !finished && Date() < deadline { RunLoop.main.run(until: Date().addingTimeInterval(0.01)) }
    print(response)
    fflush(stdout)
}
