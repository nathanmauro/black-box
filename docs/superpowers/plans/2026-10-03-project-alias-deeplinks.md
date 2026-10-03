# Preserve project evidence links through alias redirects

The Projects page canonicalizes an alias route by navigating to the canonical path alone. A shared
link with `?focus=capture:<eventId>` consequently loses its evidence selection and any URL fragment.
A focused component regression reproduced the exact dropped query/fragment before implementation.
The shared packaged browser port was in use by another verification lane, so full router/browser
acceptance follows once it is released.

Preserve the router's existing search and hash when replacing only the path. Keep real project-switch
selection clearing and browser history semantics unchanged. Verify the component regression and a
packaged alias registration → focused project URL → canonical redirect → selected evidence journey,
including reload and Back. No production alias or capture data is changed.

Verification completed: all 647 frontend tests pass, including the focused regression; check,
format, build and diff checks pass (70 preexisting lint warnings). The packaged Chromium fixture
registered a real alias, opened a capture-focused URL through it, verified the canonical URL retained
the exact query and fragment, and read the selected evidence after redirect and reload. Back returned
to Recall. The isolated database/project fixture was removed and port 8799 released; the running
local service PID was unchanged.
