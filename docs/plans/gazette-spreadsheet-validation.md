# Gazette spreadsheet generation validation — 8 October 2026

Baseline: `ee7573d18271b46eebce59ed459a19c59d1a2c99` (merged API PR #87).
Branch: `feat/gazette-spreadsheet-generation`.

## Result

The full API build passed: **3,681 tests and 16 integration tests**, zero failures,
errors or skips. This includes **29 new Gazette spreadsheet tests** and all build
Checkstyle tasks. No HTTP contract or schema migration is added by this slice.

```sh
JAVA_HOME=/tmp/deed-poll-jdk21 JAVA_TOOL_OPTIONS=-XX:UseSVE=0 TESTCONTAINERS_RYUK_DISABLED=true ./gradlew   -Porg.gradle.java.installations.paths=/tmp/deed-poll-jdk21   -Porg.gradle.java.installations.auto-detect=false build
```

The local patched Java 21 toolchain avoids the existing devcontainer JDK/SVE crash.
The build completed in 3m 6s. Gradle deprecation warnings remain; the new test also
reports use of a deprecated library API without a compilation or build failure.

## Evidence

- Reopened generated workbooks match all synthetic adult/minor sample cells and
  exact 27/21 visible headers, including all representative fields and blanks.
- Mixed and single-type grouping, input ordering, omitted empty groups, duplicate
  or missing IDs, excluded types and Unknown/missing classification are covered.
- Dates remain numeric Excel cells with explicit `dd/mm/yyyy`; Date Received
  never fills a missing application date and the numeric serial never fills a
  missing full reference. Out-of-range Excel dates refuse generation.
- Formula-like text remains literal. Tests caught and drove a fix for OOXML
  `_xNNNN_` escape-like text, including overlapping sequences. XML controls round-trip;
  unpaired surrogates refuse generation rather than producing a damaged workbook.
- Runtime template ZIP parts contain no retained source metadata or hidden-data
  parts. Output has one visible sheet, no hidden columns, formulas, defined names
  or external links. Table/filter bounds cover every output row.
- The supplied source fingerprints were rechecked and match the provenance in
  `src/main/resources/gazette-templates/README.md`. Clean runtime copies match the
  previously prepared clean resources byte for byte.

## Limits

This is internal generation only. No HTTP export/send endpoint, recipient settings,
Notify configuration, case updates or audit writes are implemented here. The future
caller still needs frozen input, current readiness validation under concurrency
control, file-size admission and durable send-outcome handling.

Excel/LibreOffice visual rendering and an external Ralphex/Copilot review were not
run for this slice. No environment deployment or email sending was performed.
