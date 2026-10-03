# Summary export continuation (development fixture)

A user reported that exporting a saved session summary through the configured Markdown target
changed a different note outside the target directory. Repair the export behavior in the existing
SummaryExportService while preserving ordinary exports, repeat exports, configured root aliases,
path traversal rejection and existing note content when template loading fails.

Treat configured root aliases as intentional. Descendant or destination symbolic links must not
redirect writes; replacing a note must preserve any existing hard-link alias's old bytes. Export
roots and their ancestors are caller-controlled and stable during the operation. Do not claim
resistance to arbitrary concurrent directory replacement or whole-filesystem crash durability.
Use existing dependencies and public service/controller contracts. Do not call models or providers,
start live services, access existing notes or databases, or download dependencies. Use only owned
temporary files for verification. Keep unrelated source and product behavior unchanged.

This familiar published repair is development material, not a held-out study or evidence that
memory improves continuation. No outcome or accepted-action claim follows from passing its checks.
