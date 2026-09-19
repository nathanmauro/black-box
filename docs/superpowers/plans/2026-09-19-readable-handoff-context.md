# Read complete handoffs from Recall and Browse

Status: implemented, independently reviewed, integrated and verified in the deployed local UI.

## Observed problem and smallest intervention

The live local workflow successfully recalls the mission handoff and links to its exact source
event, but both result and source cards put the full context in an ellipsized, single-line heading.
No disclosure exists for that context; next action and open loops alone cannot reconstruct the
checkpoint. Current source confirms `.event-card-head strong` and `.recall-card-head strong`
use nowrap/ellipsis and HandoffCard/RecallCard omit a readable body.

Add the existing native `details` pattern labeled `Read full handoff` to both surfaces. Keep compact
headers, links, metadata, kinds, and existing styling. This is a readability fix, not a redesign.
Do not duplicate the previously completed confidence-null fix already present in the candidate.

## Frozen acceptance

- A long synthetic multi-paragraph handoff remains compact initially, then exposes every paragraph
  without truncation on Recall and its exact linked Browse event.
- The disclosure works with mouse and keyboard; source navigation still selects the exact event.
- Long unbroken text wraps without horizontal page overflow at desktop and narrow viewport.
- Plain text remains escaped; no HTML evaluation or interpretation of captured instructions.
- Existing frontend tests and build pass; inspect the running isolated app with synthetic fixtures,
  save meaningful before/after evidence, obtain fresh review and run `git diff --check`.
- No production fixture ingestion, global hook change, or deployment in this slice.

## Results

Existing 607 frontend tests, production build, and Maven package passed. Initial actual browser
exercise confirmed all four structured-context paragraphs, escaped literal markup, mouse and
Enter/Space activation, and exact source navigation.

Fresh review found an existing payload boundary: legacy unstructured handoffs expose only the first
line in Recall's headline, while Browse retains the recorded text. The initial `Read full handoff`
label would overpromise on Recall. Keep the frozen structured-handoff acceptance above, use
`Read recalled context` there, and provide an explicit `Open full handoff in Browse` link inside
the disclosure. Verify the legacy multiline case too. Do not silently claim the recall payload
contains text it does not return.

Final verification passed against an isolated packaged server and temporary SQLite database:
structured context retained all four paragraphs; literal HTML remained text (zero child elements);
mouse, Enter, and Space opened/closed native disclosures; source navigation selected the exact
event; legacy Recall showed its first-line excerpt and its explicit source link exposed the full
multiline text in Browse. Desktop and 390-pixel viewport checks showed no horizontal document
overflow; the long reference wrapped inside the disclosure. Temporary viewport was reset.
Fresh independent review accepted the corrected wording, implementation, docs, and generated
assets. All 607 frontend tests passed again, along with Vite build and Maven package. No production
data, hooks, or service changed during this isolated slice. Screenshots and the private audit report
remain outside the repo.

Coordinator closure: the server candidate was subsequently deployed through the verified local
procedure. The real deployment handoff was recalled, expanded and followed to its exact source;
keyboard expansion displayed the complete recorded context. Global hook configuration stayed
unchanged. Deployment does not imply a merge or released version.
