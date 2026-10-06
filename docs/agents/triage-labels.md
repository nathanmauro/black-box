# Triage Labels

The skills speak in terms of five canonical triage roles. This repo's Linear project carries those
roles as **statuses**, not labels, so no triage labels exist in Linear and none should be created.

| Role in mattpocock/skills | In our tracker                         | Meaning                                  |
| ------------------------- | -------------------------------------- | ---------------------------------------- |
| `needs-triage`            | status `Backlog`, unassigned           | Nathan needs to evaluate this issue      |
| `needs-info`              | status `Backlog`, with an open question comment | Waiting on more information     |
| `ready-for-agent`         | status `Todo`, unassigned              | Fully specified, ready for an AFK agent  |
| `ready-for-human`         | status `Todo`, assigned to Nathan      | Requires human implementation            |
| `wontfix`                 | status `Canceled`, with a reason comment | Will not be actioned                   |

When a skill mentions a role (e.g. "apply the AFK-ready triage label"), set the corresponding
status and assignee from this table instead of applying a label.

The existing Linear labels `Bug`, `Feature`, and `Improvement` are type labels and may be applied
alongside. `retroactive` marks work logged after it was done.
