# Repair precise event feed chronology

Black Box records event timestamps with variable fractional-second precision. Its
event feed must present events in descending chronological order and paginate without
missing, repeating, or reordering evidence. Reproduce and repair the chronology issue
at the recording store's existing feed boundary, including an inclusive `since`
boundary between adjacent nanoseconds.

Preserve canonical event payloads and stored timestamp text, the existing timestamp
plus ID cursor wire format, descending ID order for equal instants, and combined
project, source, and text-query filtering. Backdated arrivals must retain their own
event time. Keep SQLite as the default. Do not replace nanosecond comparisons with a
lower-precision date conversion, rewrite stored history, change build configuration,
or introduce a new cursor protocol.

Use the repository's existing Java 21 and Maven patterns. Work only in the supplied
source snapshot; do not use external history, remote services, models, live databases,
credentials, or dependency downloads. This is a familiar development task, not a
held-out evaluation or evidence of continuation usefulness.
