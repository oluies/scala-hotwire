# scala-hotwire

## Review memory

`reviewmemory/` holds saved findings from past `/ultrareview` runs on this
repo, one file per PR (`pr_ultrareview<PR#>.md`). Each entry records the
ultrareview-flagged bug, the PR comment, the reasoning, and the commit that
addressed it.

When working on a PR, check `reviewmemory/pr_ultrareview<PR#>.md` first for
prior findings on the same branch — fixes and rationales there should be
preserved across follow-up commits unless explicitly revisited. New findings
from a fresh `/ultrareview` run should be appended to the same file rather
than written to a new one, keeping each PR's review history in one place.
