import AppKit

let options = Options.parse(Array(CommandLine.arguments.dropFirst()), env: ProcessInfo.processInfo.environment)
if let output = options.selfTestOutput {
    SelfTest.run(url: options.url, output: output)
}
let app = NSApplication.shared
let delegate = AppDelegate(options: options)
app.delegate = delegate
app.run()
