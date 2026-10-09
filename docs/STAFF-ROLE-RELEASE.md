# Team leader and Delivery Manager release handoff

This is the API handoff for the 9 October 2026 permission change. Local validation does not
establish IDAM registration, CCD import, account assignment or deployment. The
[execution plan](plans/20261009-team-leader-delivery-manager.md) records the acceptance results
and artifact identities. The admin and infrastructure companion plans own their releases.

## Accepted permission boundary

| Capability | Consumers | Team leader / temporary legacy admin | Delivery Manager alone |
|---|---|---|---|
| OVERRIDE_PRIVACY | CurrentUser.writeContext; existing create/edit/form privacy adapters | Yes | Yes |
| DELETE_NOTE | NoteService.removeNote, before parent-scoped lookup | Yes | Yes |
| DELETE_WITNESS | WitnessService.removeWitness, before parent-scoped lookup | Yes | Yes |
| MANAGE_SHARED_QUERIES | SavedQueryPermissions editing and publishing | Yes | Yes |
| REPAIR_CCD | CcdAttemptRepairs overview, reconciliation and retry | Yes | Yes |
| ACCESS_GAZETTE | All Gazette human entry points and named ready worklist/count | No | Yes |

The exact new claims are `caseworker-deedpoll-team-leader` and
`caseworker-deedpoll-delivery-manager`. The deprecated `caseworker-deedpoll-admin` has only
the five casework capabilities. Grants are an immutable union of recognised roles. Neither
new claim implies `caseworker-deedpoll-judicial`, CCD system access or importer authority.
A private saved query remains private; ownership and historical attribution retain the subject.
An owner can still unpublish after losing shared-query management privileges.

The eight Gazette operations are GET `/caseworker/gazette-notices`, GET/PUT
`/caseworker/gazette-settings`, POST `/caseworker/gazette-notice-previews`, POST
`/caseworker/gazette-notices`, GET `/caseworker/gazette-notice-requests`, GET
`/caseworker/gazette-notices/{batchId}` and GET `/caseworker/gazette-notices/{batchId}/cases`.
The ninth entry is GET `/caseworker/cases?filter=readyForPublication`, including pagination
counts and saved-query links. All require ACCESS_GAZETTE before business reads or effects.
Known request keys do not bypass admission. There is no human recovery/download endpoint on
this baseline. Workers finish already-admitted requests using durable authority, preserving
reservation, idempotency, send and audit evidence. Ordinary field queries remain unchanged.

The general security chain on this baseline requires authentication. The separate staff-gate
feature is not included: its eventual integration must admit both new roles to CASEWORKER,
preserve its configured report/enforce mode, and retain the independent judicial boundary.
Gazette capability enforcement does not depend on that mode.

## Release inputs and provenance

Use the complete OpenAPI artifact identified in Task 6 evidence, and retain Task 5's full
before/after deltas. The admin's older input lacks seven operations, eleven schemas and the
history batchId field; these are existing API drift that the admin contract reconciliation
must explicitly consume or defer. Do not filter them out of the handoff. Build evidence is
local and ignored: transfer the named files and verify their recorded SHA-256 checksums, or
regenerate them from the recorded source. No remote publication has taken place.

The generated definition has 23 JSON files. Its new role rows grant only the existing
DEED_POLL_READER profile. The configured system role remains separate; neither new human
role gets system events or identifier fields. The pinned definition and local generation use
DEED_POLL_PLACEHOLDER identifiers. They are test provenance, not an importable production
release. The release owner must regenerate using confirmed environment identifiers, review
all resulting differences and record the actual imported bundle checksum. Keep the legacy
reader mapping until coordinated retirement.

The build produces a local JAR. No container image digest, deployed version or environment
import identity is established here. Release automation must record those identities and
associate them with the final task commit before deployment.

## Outstanding operator sequence

1. The IDAM owner verifies current registration conventions and registers both exact claims
   in the agreed environments. Keep system/importer accounts separate and retain the legacy
   registration during migration.
2. The CCD owner imports the reviewed environment-specific reader mappings and required role
   catalogue entries; verify each new role alone can read documents without system grants.
3. Deploy API enforcement to every serving instance before exposing the new admin UI. Use a
   Gazette maintenance window if old replicas would otherwise continue granting legacy access.
   Record image digests and prove no old instance remains in service before declaring cutover.
4. The admin owner reconciles the full contract and deploys its gates, counts, session handling
   and personas. Existing admins migrate to Team leader by default, preserving subject IDs;
   Delivery Manager requires explicit assignment by the authorised account owner.
5. Obtain fresh logins and invalidate affected old sessions through the established mechanism.
   Token refresh alone does not update the admin's stored role snapshot. Do not flush shared
   Redis or rotate client/session secrets for this change.
6. Run agreed per-environment smoke checks with each role alone and mixed judicial roles:
   privileged casework and document reads for both new roles, all Gazette operations and the
   named count refused to legacy/Team leader/ordinary/judicial callers, and judicial operations
   refused without JUDICIAL. Any publication smoke needs an approved non-production fixture
   and recipient. Local tests do not replace these live checks.

## Compatibility removal and rollback

Remove compatibility only when no users need legacy assignments, old tokens/sessions have
expired or been invalidated, new reader mappings work in every target environment, and every
retained rollback image honours Delivery Manager-only Gazette access. Coordinate API/admin
claim removal, CCD legacy reader removal, fixture cleanup and IDAM catalogue retirement in a
follow-up. Prove the old claim then fails privileged operations; retain historical audit data.

Do not roll back to a binary that restores legacy Gazette access. Prefer a forward fix or
suspend Gazette while preserving ordinary compatible casework. Never reset in-flight request,
reservation or send evidence, and never assign every former admin Delivery Manager as a repair.
