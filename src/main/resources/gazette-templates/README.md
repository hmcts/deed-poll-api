# Gazette spreadsheet templates

These are the cleaned visible-column workbooks supplied for the existing London
Gazette process. They are runtime resources; the ignored `data/documents` directory
is not used by the application.

| Resource | Columns | SHA-256 |
|---|---:|---|
| `adult.xlsx` | 27 | `640bf91890eaf1c33e401f7aeaca5a4ac962cc885bad3aa25585ad835b7e8f12` |
| `minor.xlsx` | 21 | `436c68d92dfcaf134805e3555ef14b08ddf8c1aaf1c00ac4f8d618c9fa673a0f` |

The original adult workbook had 33 columns (A:F hidden); the minor had 28
(A:G hidden). The source fingerprints are respectively
`f8c727f1dbf740483ed4d638d8ef0eacf21f65c2e407d30771f6638dd30cdb9b` and
`3cf7479496c63c883d13ed589e643613a4c4a6d1893f75483aa90c2a36bc05e7`.
The cleaned copies remove hidden columns, retained source data and metadata.
They contain only headers and an empty styled row. Do not replace these with the
raw source exports, which can retain personal data in hidden parts.

`GazetteColumn` states the visible header order and field readers. The generator
checks headers against this mapping, fills literal string cells and real Excel
dates (`dd/mm/yyyy`), removes manual-entry validation, freezes the header and
expands the table/filter to cover all generated rows. Full `displayReference` is
used without falling back to the numeric serial. Dates use the stored UTC calendar
day; Date Received never substitutes for Date of Application. Missing data stays
blank, including absent representative details. Existing Dynamics labels are used.

Adult and Adult Transgender cases use the adult file; Minor and Minor Transgender
use the minor file. Relinquishment, Unknown and missing types refuse the whole
selection. There is no default file and no silent omission. Order within each file
matches the input selection. Only populated groups produce a file.

The generator has no persistence or delivery side effects. Its future caller must
freeze the selected input and validate current publication readiness as part of the
send/concurrency protocol. HTTP download/send, recipient settings, Notify
provisioning, file-size admission and durable recovery are separate work.

Tests reopen generated workbooks against synthetic review samples pinned under
`src/test/resources/gazette`. No live case data is used. An Excel visual check
remains outstanding.

Apache POI 5.5.1 is pinned explicitly for workbook I/O:
[release information](https://poi.apache.org/download.cgi) and
[spreadsheet API guide](https://poi.apache.org/components/spreadsheet/quick-guide.html).
