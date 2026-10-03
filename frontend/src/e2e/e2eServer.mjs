// Playwright overlays webServer.env onto the caller's environment. Keep build tooling in its
// normal checkout, but launch the app with only this explicit fixture runtime configuration.
// The subshell exec preserves the Java PID so the outer trap waits before owned-storage cleanup.
export const E2E_SERVER_COMMAND = [
  "set -eu; child=; owned=0; ",
  "cleanup() { ",
  "  code=$?; trap - EXIT INT TERM; ",
  '  if [ -n "$child" ] && kill -0 "$child" 2>/dev/null; then kill "$child" 2>/dev/null || true; wait "$child" 2>/dev/null || true; fi; ',
  '  if [ "$owned" = 1 ]; then node frontend/src/e2e/e2ePreflight.mjs cleanup || code=$?; fi; ',
  '  exit "$code"; ',
  "}; ",
  "trap cleanup EXIT INT TERM; ",
  // These are separate from the URL guard: never let a direct runner launch target another port.
  '[ "$SBA_PORT" = 8799 ] && [ "$SBA_BIND_ADDRESS" = 127.0.0.1 ] || { echo "Refusing non-fixture E2E listener" >&2; exit 1; }; ',
  "node frontend/src/e2e/e2ePreflight.mjs prepare; owned=1; ",
  'printf "BLACK_BOX_E2E_DB=%s\\n" "$SBA_E2E_DB_PATH"; ',
  "mvn -q -Pfrontend -DskipTests package; ",
  'jar="$PWD/target/sba-agentic-0.2.0.jar"; ',
  '( cd "$SBA_E2E_TEMP_DIR"; exec env -i ',
  'PATH="$PATH" HOME="$SBA_E2E_TEMP_DIR" LANG="${LANG:-C}" JAVA_HOME="${JAVA_HOME:-}" ',
  'SBA_PORT=8799 SBA_BIND_ADDRESS=127.0.0.1 SBA_DATASOURCE_URL="jdbc:sqlite:$SBA_E2E_DB_PATH" ',
  'SBA_E2E_TEMP_DIR="$SBA_E2E_TEMP_DIR" SBA_E2E_DB_PATH="$SBA_E2E_DB_PATH" SBA_E2E_RUN_TOKEN="$SBA_E2E_RUN_TOKEN" ',
  "SBA_LOCAL_AI_ENABLED=false SBA_ELASTICSEARCH_ENABLED=false SBA_MEMORY_EMBEDDING_ENABLED=false ",
  "SBA_ASK_EMBEDDING_ENABLED=false SBA_JUDGE_ENABLED=false SBA_SUMMARY_BACKEND=local ",
  'SBA_EDITOR_ENABLED=true SBA_EDITOR_COMMAND="$SBA_E2E_TEMP_DIR/fake-editor" ',
  'SBA_EDITOR_ALLOWLIST="$SBA_E2E_TEMP_DIR/fake-editor" SBA_EDITOR_TIMEOUT=2s ',
  'SBA_E2E_EDITOR_LOG="$SBA_E2E_TEMP_DIR/editor-argv.bin" SBA_E2E_INJECTION_SENTINEL="$SBA_E2E_TEMP_DIR/injection-sentinel" ',
  'java -jar "$jar" --spring.config.location=classpath:/application.yml --spring.profiles.active=default ',
  ') & child=$!; wait "$child"',
].join("");
