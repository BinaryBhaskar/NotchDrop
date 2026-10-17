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
    let cancelAfter = args.count >= 4 ? Int64(args[3]) : nil
    runBlocking { await SelfTest.interopServer(keyHex: key, cancelAfter: cancelAfter, resumable: args.count >= 5 && args[4] == "resume") }
}
if args.count >= 6, args[1] == "--interop-send", let port = UInt16(args[2]) {
    let (key, file, text) = (args[3], args[4], args[5])
    let cancelAfter = args.count >= 7 ? Int64(args[6]) : nil
    let id = args.count >= 8 ? args[7] : nil
    let dropAfter = args.count >= 9 ? Int64(args[8]) : nil
    runBlocking {
        await SelfTest.interopSend(port: port, keyHex: key, file: file, text: text, cancelAfter: cancelAfter, id: id, dropAfter: dropAfter)
    }
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
