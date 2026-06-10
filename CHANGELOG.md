# Changelog

All notable changes to this project will be documented in this file.

## [0.1.0] - 2026-06-10

- Structured decision and handoff capture with recall over MCP (Streamable HTTP) and REST.
- Raw session event recording via opt-in hook bridge for Claude Code and Codex.
- SQLite source of truth with WAL.
- Session summaries via local OpenAI-compatible model by default, or external CLI backend when opted in.
- Secret redaction on ingest, on by default.
- Web UI with Sessions and Recall workspaces; ASK is shown only when its optional index is configured.
- Optional Elasticsearch secondary index.
- Obsidian and markdown summary export, opt-in.
- CLI commands for doctor, ingest, search, sessions, and summarize.
- Localhost-only bind by default.
- CI and tagged releases.
