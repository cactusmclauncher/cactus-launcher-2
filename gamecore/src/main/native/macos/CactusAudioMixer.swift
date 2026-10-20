// macOS Core Audio process-tap bridge for Cactus Launcher.
// Process taps require macOS 14.2+ and System Audio Recording permission.
import AppKit
import CoreAudio
import Foundation

private func address(_ selector: AudioObjectPropertySelector,
                     _ scope: AudioObjectPropertyScope = kAudioObjectPropertyScopeGlobal) -> AudioObjectPropertyAddress {
    AudioObjectPropertyAddress(mSelector: selector, mScope: scope, mElement: kAudioObjectPropertyElementMain)
}

private func scalar<T>(_ object: AudioObjectID, _ selector: AudioObjectPropertySelector, _ fallback: T) -> T {
    var addr = address(selector)
    var result = fallback
    var size = UInt32(MemoryLayout<T>.size)
    let status = withUnsafeMutablePointer(to: &result) {
        AudioObjectGetPropertyData(object, &addr, 0, nil, &size, $0)
    }
    return status == noErr ? result : fallback
}

private func stringValue(_ object: AudioObjectID, _ selector: AudioObjectPropertySelector) -> String? {
    var addr = address(selector)
    var result: CFString? = nil
    var size = UInt32(MemoryLayout<CFString?>.size)
    let status = withUnsafeMutablePointer(to: &result) {
        AudioObjectGetPropertyData(object, &addr, 0, nil, &size, $0)
    }
    guard status == noErr, let result else { return nil }
    return result as String
}

private struct AppEntry {
    let pid: pid_t
    let bundleID: String?
    let name: String
}

private final class AudioEngine {
    private static let maxTaps = 64
    private let gains = UnsafeMutablePointer<Float>.allocate(capacity: maxTaps)
    private let tapCount = UnsafeMutablePointer<Int32>.allocate(capacity: 1)
    private let inputOffset = UnsafeMutablePointer<Int32>.allocate(capacity: 1)
    private var output = AudioObjectID(kAudioObjectUnknown)
    private var aggregate = AudioObjectID(kAudioObjectUnknown)
    private var taps: [AudioObjectID] = []
    private var tappedProcesses: [AudioObjectID] = []
    private var ioProc: AudioDeviceIOProcID?
    private var apps: [pid_t: AppEntry] = [:]
    private var volumes: [pid_t: Float] = [:]
    private var mutes: [pid_t: Bool] = [:]
    private var owners: [pid_t] = []

    init() {
        gains.initialize(repeating: 1, count: Self.maxTaps)
        tapCount.pointee = 0
        inputOffset.pointee = 0
    }

    deinit {
        teardown()
        gains.deallocate()
        tapCount.deallocate()
        inputOffset.deallocate()
    }

    func refresh() throws -> [AppEntry] {
        if #unavailable(macOS 14.2) { throw NSError(domain: "CactusAudio", code: 14, userInfo: [NSLocalizedDescriptionKey: "Для микшера требуется macOS 14.2 или новее."]) }
        let system = AudioObjectID(kAudioObjectSystemObject)
        output = scalar(system, kAudioHardwarePropertyDefaultOutputDevice, AudioObjectID(kAudioObjectUnknown))
        guard output != AudioObjectID(kAudioObjectUnknown), let outputUID = stringValue(output, kAudioDevicePropertyDeviceUID) else {
            throw NSError(domain: "CactusAudio", code: 1, userInfo: [NSLocalizedDescriptionKey: "Не найдено устройство вывода звука."])
        }
        var listAddress = address(kAudioHardwarePropertyProcessObjectList)
        var byteCount: UInt32 = 0
        guard AudioObjectGetPropertyDataSize(system, &listAddress, 0, nil, &byteCount) == noErr else { return [] }
        let processCount = Int(byteCount) / MemoryLayout<AudioObjectID>.stride
        let raw = UnsafeMutablePointer<AudioObjectID>.allocate(capacity: processCount)
        defer { raw.deallocate() }
        guard AudioObjectGetPropertyData(system, &listAddress, 0, nil, &byteCount, raw) == noErr else { return [] }

        var nextApps: [pid_t: AppEntry] = [:]
        let runningApps = NSWorkspace.shared.runningApplications.filter { !$0.isTerminated }
        for object in UnsafeBufferPointer(start: raw, count: processCount) {
            guard scalar(object, kAudioProcessPropertyIsRunningOutput, UInt32(0)) != 0 else { continue }
            let pid = scalar(object, kAudioProcessPropertyPID, pid_t(-1))
            guard pid > 0 else { continue }
            let running = NSRunningApplication(processIdentifier: pid)
            let bundleID = stringValue(object, kAudioProcessPropertyBundleID) ?? running?.bundleIdentifier
            let owner = runningApps.filter { app in
                guard let ownerID = app.bundleIdentifier, let bundleID else { return app.processIdentifier == pid }
                return bundleID == ownerID || bundleID.hasPrefix(ownerID + ".")
            }.max { ($0.bundleIdentifier?.count ?? 0) < ($1.bundleIdentifier?.count ?? 0) }
            let ownerPID = owner?.processIdentifier ?? pid
            let ownerBundleID = owner?.bundleIdentifier ?? bundleID
            let name = owner?.localizedName ?? running?.localizedName ?? bundleID ?? "Приложение \(pid)"
            nextApps[ownerPID] = AppEntry(pid: ownerPID, bundleID: ownerBundleID, name: name)
        }
        apps = nextApps
        try rebuildIfNeeded(processObjects: Array(UnsafeBufferPointer(start: raw, count: processCount)), outputUID: outputUID)
        return nextApps.values.sorted { $0.name.localizedCaseInsensitiveCompare($1.name) == .orderedAscending }
    }

    func setVolume(_ pid: pid_t, _ value: Float) {
        volumes[pid] = max(0, min(1, value))
        updateGains()
    }

    func setMuted(_ pid: pid_t, _ muted: Bool) {
        mutes[pid] = muted
        updateGains()
    }

    func volume(_ pid: pid_t) -> Float { volumes[pid] ?? 1 }
    func muted(_ pid: pid_t) -> Bool { mutes[pid] ?? false }

    private func updateGains() {
        for (slot, pid) in owners.enumerated() where slot < Self.maxTaps {
            gains[slot] = (mutes[pid] ?? false) ? 0 : (volumes[pid] ?? 1)
        }
    }

    private func ownerPID(process: AudioObjectID) -> pid_t? {
        let pid = scalar(process, kAudioProcessPropertyPID, pid_t(-1))
        if apps[pid] != nil { return pid }
        guard let bundleID = stringValue(process, kAudioProcessPropertyBundleID) else { return nil }
        if let exact = apps.values.first(where: { $0.bundleID == bundleID }) { return exact.pid }
        return apps.values
            .filter { app in app.bundleID.map { bundleID.hasPrefix($0 + ".") } ?? false }
            .max(by: { ($0.bundleID?.count ?? 0) < ($1.bundleID?.count ?? 0) })?.pid
    }

    private func rebuildIfNeeded(processObjects: [AudioObjectID], outputUID: String) throws {
        let targets = processObjects.filter {
            scalar($0, kAudioProcessPropertyIsRunningOutput, UInt32(0)) != 0 && ownerPID(process: $0) != nil
        }
        if Set(targets) == Set(tappedProcesses), aggregate != AudioObjectID(kAudioObjectUnknown) { return }
        teardown()
        var tapUIDs: [String] = []
        var targetObjects: [AudioObjectID] = []
        owners = []
        for object in targets {
            guard let pid = ownerPID(process: object), taps.count < Self.maxTaps else { continue }
            let description = CATapDescription()
            description.name = "CactusLauncher-\(pid)"
            description.isPrivate = true
            description.muteBehavior = .mutedWhenTapped
            description.isMixdown = true
            description.isMono = false
            description.isExclusive = false
            description.setValue([NSNumber(value: object)], forKey: "processes")
            var tap = AudioObjectID(kAudioObjectUnknown)
            let status = AudioHardwareCreateProcessTap(description, &tap)
            guard status == noErr, tap != AudioObjectID(kAudioObjectUnknown),
                  let uid = stringValue(tap, kAudioTapPropertyUID) else { continue }
            taps.append(tap)
            tapUIDs.append(uid)
            targetObjects.append(object)
            owners.append(pid)
        }
        tapCount.pointee = Int32(taps.count)
        updateGains()
        tappedProcesses = targetObjects
        guard !taps.isEmpty else { return }
        let aggregateUID = "com.cactuslauncher.audio.aggregate"
        let spec: [String: Any] = [
            kAudioAggregateDeviceNameKey: "Cactus Launcher Audio Mixer",
            kAudioAggregateDeviceUIDKey: aggregateUID,
            kAudioAggregateDeviceMainSubDeviceKey: outputUID,
            kAudioAggregateDeviceIsPrivateKey: true,
            kAudioAggregateDeviceIsStackedKey: false,
            kAudioAggregateDeviceTapAutoStartKey: true,
            kAudioAggregateDeviceSubDeviceListKey: [[kAudioSubDeviceUIDKey: outputUID]],
            kAudioAggregateDeviceTapListKey: tapUIDs.map { [kAudioSubTapUIDKey: $0, kAudioSubTapDriftCompensationKey: true] },
        ]
        var newAggregate = AudioObjectID(kAudioObjectUnknown)
        let createStatus = AudioHardwareCreateAggregateDevice(spec as CFDictionary, &newAggregate)
        guard createStatus == noErr else {
            teardownTaps()
            throw NSError(domain: "CactusAudio", code: Int(createStatus), userInfo: [NSLocalizedDescriptionKey: "Не удалось создать аудиомикшер Core Audio (\(createStatus))."])
        }
        aggregate = newAggregate
        var streamAddress = address(kAudioDevicePropertyStreamConfiguration, kAudioObjectPropertyScopeInput)
        var streamBytes: UInt32 = 0
        if AudioObjectGetPropertyDataSize(aggregate, &streamAddress, 0, nil, &streamBytes) == noErr, streamBytes > 0 {
            let buffer = UnsafeMutableRawPointer.allocate(byteCount: Int(streamBytes), alignment: 16)
            defer { buffer.deallocate() }
            if AudioObjectGetPropertyData(aggregate, &streamAddress, 0, nil, &streamBytes, buffer) == noErr {
                inputOffset.pointee = Int32(max(0, Int(buffer.assumingMemoryBound(to: AudioBufferList.self).pointee.mNumberBuffers) - taps.count))
            }
        }
        var proc: AudioDeviceIOProcID?
        let callback = makeCallback()
        let procStatus = AudioDeviceCreateIOProcIDWithBlock(&proc, aggregate, nil, callback)
        guard procStatus == noErr, let proc else {
            teardown()
            throw NSError(domain: "CactusAudio", code: Int(procStatus), userInfo: [NSLocalizedDescriptionKey: "Не удалось подготовить аудиопоток микшера (\(procStatus))."])
        }
        ioProc = proc
        let startStatus = AudioDeviceStart(aggregate, proc)
        guard startStatus == noErr else {
            teardown()
            throw NSError(domain: "CactusAudio", code: Int(startStatus), userInfo: [NSLocalizedDescriptionKey: "Не удалось запустить Core Audio (\(startStatus)). Проверьте разрешение «Запись аудио системы» в настройках Конфиденциальности macOS."])
        }
    }

    private func makeCallback() -> AudioDeviceIOBlock {
        let gains = self.gains
        let tapCount = self.tapCount
        let inputOffset = self.inputOffset
        return { _, inputData, _, outputData, _ in
            let output = UnsafeMutableAudioBufferListPointer(outputData)
            for buffer in output { if let data = buffer.mData { memset(data, 0, Int(buffer.mDataByteSize)) } }
            let input = UnsafeMutableAudioBufferListPointer(UnsafeMutablePointer(mutating: inputData))
            guard output.count > 0, let destination = output[0].mData?.assumingMemoryBound(to: Float.self) else { return }
            let samples = Int(output[0].mDataByteSize) / MemoryLayout<Float>.size
            for slot in 0..<Int(tapCount.pointee) {
                let index = Int(inputOffset.pointee) + slot
                guard index < input.count, let sourceRaw = input[index].mData else { continue }
                let source = sourceRaw.assumingMemoryBound(to: Float.self)
                let count = min(samples, Int(input[index].mDataByteSize) / MemoryLayout<Float>.size)
                let gain = gains[slot]
                if gain != 0 { for sample in 0..<count { destination[sample] += source[sample] * gain } }
            }
        }
    }

    private func teardown() {
        if aggregate != AudioObjectID(kAudioObjectUnknown) {
            if let ioProc { AudioDeviceStop(aggregate, ioProc); AudioDeviceDestroyIOProcID(aggregate, ioProc) }
            AudioHardwareDestroyAggregateDevice(aggregate)
        }
        ioProc = nil
        aggregate = AudioObjectID(kAudioObjectUnknown)
        teardownTaps()
        tapCount.pointee = 0
    }

    private func teardownTaps() {
        for tap in taps { AudioHardwareDestroyProcessTap(tap) }
        taps.removeAll()
    }
}

private func emit(_ fields: [String]) {
    print(fields.joined(separator: "\t"), terminator: "\n")
    fflush(stdout)
}

private func encoded(_ text: String) -> String {
    Data(text.utf8).base64EncodedString()
}

// Read the same session macOS exposes in Now Playing, regardless of the app
// publishing it. Resolve dynamically: this framework is not a public SDK API.
private let mediaRemoteHandle: UnsafeMutableRawPointer? = {
    guard let bundle = Bundle(path: "/System/Library/PrivateFrameworks/MediaRemote.framework"),
          bundle.load(),
          let executable = bundle.executablePath else { return nil }
    return dlopen(executable, RTLD_LAZY)
}()

private func nowPlaying() -> [String]? {
    guard let handle = mediaRemoteHandle,
          let symbol = dlsym(handle, "MRMediaRemoteGetNowPlayingInfo") else { return nil }
    typealias Getter = @convention(c) (DispatchQueue, @escaping @convention(block) (NSDictionary?) -> Void) -> Void
    let getInfo = unsafeBitCast(symbol, to: Getter.self)
    var finished = false
    var result: [String]?
    getInfo(DispatchQueue.main) { info in
        defer { finished = true }
        guard let info,
              let title = info["kMRMediaRemoteNowPlayingInfoTitle"] as? String,
              !title.isEmpty else { return }
        let artist = info["kMRMediaRemoteNowPlayingInfoArtist"] as? String ?? ""
        let duration = (info["kMRMediaRemoteNowPlayingInfoDuration"] as? NSNumber)?.doubleValue ?? 0
        let rate = (info["kMRMediaRemoteNowPlayingInfoPlaybackRate"] as? NSNumber)?.doubleValue ?? 0
        var elapsed = (info["kMRMediaRemoteNowPlayingInfoElapsedTime"] as? NSNumber)?.doubleValue ?? 0
        if rate > 0, let timestamp = info["kMRMediaRemoteNowPlayingInfoTimestamp"] as? Date {
            elapsed += max(0, Date().timeIntervalSince(timestamp)) * rate
        }
        elapsed = max(0, duration > 0 ? min(duration, elapsed) : elapsed)
        let artwork = (info["kMRMediaRemoteNowPlayingInfoArtworkData"] as? Data)?.base64EncodedString() ?? ""
        result = [encoded(title), encoded(artist), String(elapsed), String(duration),
                  rate > 0 ? "playing" : "paused", artwork]
    }
    let deadline = Date().addingTimeInterval(1.5)
    while !finished && Date() < deadline {
        RunLoop.main.run(until: Date().addingTimeInterval(0.01))
    }
    // Keep the framework loaded: a delayed callback may still be pending.
    return result
}

private func postMediaKey(_ key: Int32) throws {
    for state: Int32 in [0xA, 0xB] {
        let data = Int((key << 16) | (state << 8))
        guard let event = NSEvent.otherEvent(with: .systemDefined, location: .zero,
                                             modifierFlags: NSEvent.ModifierFlags(rawValue: UInt(0xA00)),
                                             timestamp: ProcessInfo.processInfo.systemUptime,
                                             windowNumber: 0, context: nil, subtype: 8,
                                             data1: data, data2: -1)?.cgEvent else {
            throw NSError(domain: "CactusMedia", code: 1, userInfo: [NSLocalizedDescriptionKey: "Не удалось отправить команду медиаплееру."])
        }
        event.post(tap: .cghidEventTap)
    }
}

if #unavailable(macOS 14.2) {
    emit(["ERROR", encoded("Для микшера требуется macOS 14.2 или новее.")])
    exit(0)
}

private let engine = AudioEngine()
while let line = readLine() {
    let parts = line.split(separator: "\t", omittingEmptySubsequences: false).map(String.init)
    guard let command = parts.first else { continue }
    do {
        switch command {
        case "LIST":
            let apps = try engine.refresh()
            for app in apps {
                emit(["SESSION", String(app.pid), encoded(app.name), String(engine.volume(app.pid)), String(engine.muted(app.pid))])
            }
            emit(["END"])
        case "VOLUME":
            guard parts.count == 3, let pid = Int32(parts[1]), let volume = Float(parts[2]) else { throw NSError(domain: "CactusAudio", code: 2, userInfo: [NSLocalizedDescriptionKey: "Некорректное значение громкости."]) }
            engine.setVolume(pid, volume)
            emit(["OK"])
        case "MUTE":
            guard parts.count == 3, let pid = Int32(parts[1]), let muted = Bool(parts[2]) else { throw NSError(domain: "CactusAudio", code: 3, userInfo: [NSLocalizedDescriptionKey: "Некорректная команда отключения звука."]) }
            engine.setMuted(pid, muted)
            emit(["OK"])
        case "TRACK":
            guard parts.count == 2, let key = Int32(parts[1]), [16, 17, 18].contains(key) else { throw NSError(domain: "CactusMedia", code: 2, userInfo: [NSLocalizedDescriptionKey: "Неизвестная команда трека."]) }
            try postMediaKey(key)
            emit(["OK"])
        case "NOW_PLAYING":
            if let track = nowPlaying() { emit(["NOW_PLAYING"] + track) }
            else { emit(["EMPTY"]) }
        case "QUIT":
            emit(["OK"])
            exit(0)
        default:
            throw NSError(domain: "CactusAudio", code: 4, userInfo: [NSLocalizedDescriptionKey: "Неизвестная команда микшера."])
        }
    } catch {
        emit(["ERROR", encoded(error.localizedDescription)])
    }
}
