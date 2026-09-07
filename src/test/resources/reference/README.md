# Vendored specification — do not edit

Everything under this directory is a **verbatim copy** of source material from the sibling repository
[`hmcts/deed-poll-admin`](https://github.com/hmcts/deed-poll-admin). It is here for two reasons:

1. It is the **specification** for the port. `deed-poll-api` reimplements this TypeScript domain and this
   database shape in Java; where the two disagree, the copy here is right and the Java is wrong.
2. It is the port's **conformance oracle**. Tests read these files directly — the schema-conformance test
   parses `deed-poll.prisma`, and the conformance harness asserts against `dataverse_tables/`. A copy that
   drifts from its source silently weakens every one of those assertions.

## Rules

- **Never edit anything in this directory.** Not to fix a typo, not to make a test pass, not to reformat.
  If a file here looks wrong, stop and report it — the fix belongs in `deed-poll-admin`, not here.
- **Refresh only by re-copying** the whole file or directory from source, as a commit that does nothing
  else. Then re-run the build: a refresh that breaks a conformance test has found a real divergence, and
  that divergence is the finding, not an inconvenience.
- **`deed-poll-admin` is read-only from this repo.** Never edit or commit into it.

## Contents and their sources

Paths are relative to `../deed-poll-admin/` (i.e. the sibling clone).

| Here | Source |
| --- | --- |
| `deed-poll.prisma` | `libs/postgres-prisma/prisma/schema/deed-poll.prisma` |
| `ts/` | `libs/deed-poll/src/` |
| `dataverse_tables/` | `.business_context/dataverse_tables/` |

`ts/` is the domain being ported — including its tests, which are as much a part of the specification as
the implementation is. `dataverse_tables/` holds the Dataverse exports the conformance harness reads via
`ts/conformance/artifacts.ts`; in `deed-poll-admin` that module walks up the tree looking for
`.business_context/dataverse_tables`, which is why the directory is vendored under a matching name here.

> **Carried caveat.** `dataverse_tables/deedpoll/columns.html` declares "Total columns: 70" but is
> alphabetically truncated at A–R (it ends at "Refund Reference"). The conformance assertions are
> therefore **forward-direction only** — every column present in the export must map — and the export must
> never be read as a complete column set. `artifacts.ts` documents this at length; keep the caveat with
> the assertions when porting them.

## Provenance

Copied from `deed-poll-admin` at commit `8b6b8fc` ("Delete five stale local branches, and record how to
merge better (#68)", 2026-09-04).

To refresh, from the root of this repo:

```sh
cp ../deed-poll-admin/libs/postgres-prisma/prisma/schema/deed-poll.prisma src/test/resources/reference/deed-poll.prisma
rsync -a --delete ../deed-poll-admin/libs/deed-poll/src/ src/test/resources/reference/ts/
rsync -a --delete ../deed-poll-admin/.business_context/dataverse_tables/ src/test/resources/reference/dataverse_tables/
```

Then update the commit recorded above, and run `./gradlew build`.
