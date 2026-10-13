import AppKit

let args = CommandLine.arguments
func runBlocking(_ body: @escaping @Sendable () async -> Int32) -> Never {
    let sem = DispatchSemaphore(value: 0)
    nonisolated(unsafe) var code: Int32 = 1
    Task.detached {
        code = await body()
        sem.signal()
    }
    sem.wait()
    exit(code)
}

if args.contains("--selftest") {
    runBlocking { await SelfTest.run() }
}
if args.count >= 3, args[1] == "--interop-server" {
    let key = args[2]
    runBlocking { await SelfTest.interopServer(keyHex: key) }
}
if args.count >= 6, args[1] == "--interop-send", let port = UInt16(args[2]) {
    let (key, file, text) = (args[3], args[4], args[5])
    runBlocking { await SelfTest.interopSend(port: port, keyHex: key, file: file, text: text) }
}
if args.contains("--vectors") {
    SelfTest.vectors()
    exit(0)
}

MainActor.assumeIsolated {
    let app = NSApplication.shared
    let delegate = AppDelegate()
    app.delegate = delegate
    app.setActivationPolicy(.accessory)
    app.run()
}
