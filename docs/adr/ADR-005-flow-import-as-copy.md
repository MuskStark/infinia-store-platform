# ADR-005: Flow updates import as a new revision/copy — no auto three-way merge

Status: Accepted (design §6.5, §18.5)

Flows are user-editable documents; silently merging store updates with local edits is unsafe.
Installing a Flow resolves and confirms its dependency closure, then imports it as a new local
workflow (unpublished) recording `sourceCoordinate`/`sourceReleaseId`. Updates replace the
source revision only when the local copy is untouched; otherwise a new copy with a visible
diff is created. Extensions may never depend on FLOW items (enforced by the solver).
