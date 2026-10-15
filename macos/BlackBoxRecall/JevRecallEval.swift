import Foundation

struct TraceEvaluationFixture: Decodable {
    let name: String
    let query: String
    let anchors: [TraceCapture]
    let later: [TraceCapture]
    let allowedRelations: [String]
}

@main
struct JevRecallEval {
    static func main() async {
        let arguments = Array(CommandLine.arguments.dropFirst())
        guard arguments.contains("--live"), let key = JevTraceClient.validKey(ProcessInfo.processInfo.environment["TYPESAFE_API_KEY"]) else {
            FileHandle.standardError.write(Data("Usage: eval-jev-recall.sh --live --fixture FILE | --live --query TEXT [--base URL]. Requires TYPESAFE_API_KEY; no Keychain or GUI is used.\n".utf8))
            exit(2)
        }
        let client = JevTraceClient()
        defer { client.invalidate() }
        do {
            if let index = arguments.firstIndex(of: "--fixture"), arguments.indices.contains(index + 1) {
                let fixtures = try JSONDecoder().decode([TraceEvaluationFixture].self, from: Data(contentsOf: URL(fileURLWithPath: arguments[index + 1])))
                guard fixtures.count <= 12 else { throw JevTraceError.invalidResponse }
                for fixture in fixtures {
                    let result = try await client.synthetic(query: fixture.query, anchors: fixture.anchors, later: fixture.later, key: key)
                    var output = summary(result)
                    output["case"] = String(fixture.name.prefix(80))
                    output["allowed_relations"] = fixture.allowedRelations
                    output["meets_expected_gate"] = fixture.allowedRelations.contains(result.relation?.rawValue ?? "abstain")
                    try printJSON(output)
                }
            } else if let index = arguments.firstIndex(of: "--query"), arguments.indices.contains(index + 1) {
                let baseIndex = arguments.firstIndex(of: "--base")
                let baseValue = baseIndex.flatMap { arguments.indices.contains($0 + 1) ? arguments[$0 + 1] : nil } ?? RecallURL.defaultBase
                let result = try await client.trace(query: arguments[index + 1], base: RecallURL.base(baseValue), key: key)
                try printJSON(summary(result))
            } else { throw JevTraceError.invalidQuery }
        } catch {
            let message = (error as? JevTraceError)?.localizedDescription ?? "Evaluation failed; no key or source payload was logged."
            FileHandle.standardError.write(Data((message + "\n").utf8))
            exit(1)
        }
    }

    static func summary(_ result: TraceResult) -> [String: Any] {
        var output: [String: Any] = ["relation": result.relation?.rawValue ?? "abstain", "call_count": result.callCount,
            "latency_ms": result.latencyMilliseconds, "note": result.note]
        if let anchor = result.anchor { output["anchor_event_id"] = String(anchor.eventId.prefix(200)) }
        if let later = result.later { output["later_event_id"] = String(later.eventId.prefix(200)) }
        output["evaluations"] = result.calls.map { response -> [String: Any] in
            let answers = response.answers.mapValues { answer -> [String: Any] in
                ["choice": answer.choice, "probabilities": answer.probabilities, "confidence": answer.confidence]
            }
            return ["model": response.model, "answers": answers,
                    "usage": ["input_tokens": response.usage.input_tokens, "output_tokens": response.usage.output_tokens]]
        }
        return output
    }

    static func printJSON(_ value: [String: Any]) throws {
        let data = try JSONSerialization.data(withJSONObject: value, options: [.sortedKeys])
        print(String(decoding: data, as: UTF8.self))
    }
}
