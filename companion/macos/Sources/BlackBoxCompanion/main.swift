import AppKit

let options: Options
do {
    options = try Options.parse(Array(CommandLine.arguments.dropFirst()), env: ProcessInfo.processInfo.environment)
} catch {
    FileHandle.standardError.write("\(error)\n".data(using: .utf8)!)
    exit(2)
}
if let output = options.selfTestOutput {
    SelfTest.run(url: options.url, output: output)
}
if let outputDir = options.clickThroughOutput {
    MainActor.assumeIsolated { ClickThrough.run(options: options, outputDir: outputDir) }
}
let app = NSApplication.shared
let delegate = AppDelegate(options: options)
app.delegate = delegate
app.run()
