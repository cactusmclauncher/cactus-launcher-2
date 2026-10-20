import Foundation

// The Swift interpreter can read MediaRemote sessions on macOS releases where
// standalone helpers receive an empty dictionary. Keep one interpreter alive.
let handle = dlopen("/System/Library/PrivateFrameworks/MediaRemote.framework/MediaRemote", RTLD_LAZY)
typealias Getter = @convention(c) (DispatchQueue, @escaping @convention(block) (NSDictionary?) -> Void) -> Void
let symbol = handle.flatMap { dlsym($0, "MRMediaRemoteGetNowPlayingInfo") }
func encoded(_ text: String) -> String { Data(text.utf8).base64EncodedString() }
while let command = readLine() {
    if command == "QUIT" { break }
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
