# deed-poll-api

## Purpose

The backend service for deed poll case management. It serves the caseworker admin service and will
later also serve the citizen-facing service, which is why it has separate `/caseworker/**` and
`/citizen/**` namespaces.

It is a port of the TypeScript domain layer in the sibling repo
[`hmcts/deed-poll-admin`](https://github.com/hmcts/deed-poll-admin) (`libs/deed-poll`), which is the
specification for this service. Build plans live under [`docs/plans/`](docs/plans).

## What's inside

Scaffolded from [`hmcts/spring-boot-template`](https://github.com/hmcts/spring-boot-template), so it
contains:
 * application skeleton
 * common plugins and libraries
 * [HMCTS Java plugin](https://github.com/hmcts/gradle-java-plugin)
 * docker setup
 * automatically publishes API documentation to [hmcts/cnp-api-docs](https://github.com/hmcts/cnp-api-docs)
 * code quality tools already set up
 * MIT license and contribution information
 * Helm chart using chart-java.

The application exposes a health endpoint (http://localhost:4550/health) and a Prometheus metrics
endpoint (http://localhost:4550/prometheus). Those are the only actuator endpoints exposed —
`management.endpoints.web.exposure.include` lists `health, info, prometheus` and nothing else.

## The API surface

Twenty-five caseworker operations. `/swagger-ui.html` is the authority; this table is the map.

| Method | Path | What it does |
|---|---|---|
| GET | `/caseworker/cases` | The case list — a `filter` key, `where` conditions, a `q` search term, `sort`, `page` and `size` |
| GET | `/caseworker/cases/field-rules` | Show / hide / mandate rules for a `type`, `migrated` and `publishToGazette` |
| GET | `/caseworker/cases/{id}` | One case **with** its notes, witnesses and documents — one call, not four |
| GET | `/caseworker/document-types` | Every supporting-document type, with its applicability arrays |
| POST | `/caseworker/cases` | Create a case, allocating its reference number |
| PATCH | `/caseworker/cases/{id}` | Apply a patch — PATCH, not PUT, because the forms save one tab at a time |
| GET | `/caseworker/cases/{id}/notes` | A case's notes |
| POST | `/caseworker/cases/{id}/notes` | Add a note |
| GET | `/caseworker/cases/{id}/notes/{noteId}` | One note |
| PATCH | `/caseworker/cases/{id}/notes/{noteId}` | Amend a note |
| DELETE | `/caseworker/cases/{id}/notes/{noteId}` | Delete a note — 204, and 404 through the wrong case |
| GET | `/caseworker/cases/{id}/witnesses` | A case's witnesses and declarants, oldest first |
| POST | `/caseworker/cases/{id}/witnesses` | Add a witness — all four fields required |
| GET | `/caseworker/cases/{id}/witnesses/{witnessId}` | One witness |
| PATCH | `/caseworker/cases/{id}/witnesses/{witnessId}` | Amend a witness — an omitted field is left alone |
| DELETE | `/caseworker/cases/{id}/witnesses/{witnessId}` | Delete a witness — **Administrators only**, 204, and 404 through the wrong case |
| POST | `/caseworker/cases/{id}/documents` | Upload a file (multipart) and record its document row |
| GET | `/caseworker/cases/{id}/documents/{documentId}/file` | Stream a document's stored file back |
| GET | `/caseworker/saved-queries` | The shared library plus the caller's own, each flagged `selected` and `editable` |
| POST | `/caseworker/saved-queries` | Save a query — `name` required, unshared unless `shared` says otherwise |
| GET | `/caseworker/saved-queries/{id}` | One saved query, if it is shared or theirs |
| PATCH | `/caseworker/saved-queries/{id}` | Amend one — the owner, or an **Administrator** if it is shared |
| DELETE | `/caseworker/saved-queries/{id}` | Delete one — same rule, 204, and its subscribers' selections cascade |
| PUT | `/caseworker/saved-queries/{id}/selection` | Add it to the caller's own list — 204, idempotent, no edit rights needed |
| DELETE | `/caseworker/saved-queries/{id}/selection` | Remove it from the caller's own list — 204 whether or not it was in it |

There are deliberately **no** case deletes, no document-type writes and no `validate` endpoint. The domain
implements and tests them; nothing consumes them, and the sibling prototype's mistake was building an API
for an imagined consumer. There is also no *duplicate* endpoint for a saved query: a client reads one and posts
a new one with a new name, and there is no server-side invariant an endpoint would protect.

The seven saved-query routes were the nineteenth to twenty-fifth, and they are the first resource here that is
**not nested under a case** — a saved query is a way of finding cases rather than a thing belonging to one. The
notes and witnesses routes get their scoping from the parent id in the path; this one has to get it from the
entity, which is why its permission rules are written out rather than implied:

- A query is **visible** if it is `shared` or the caller owns it. One that is neither is **404**, carrying the
  message an id that never existed gets, so no id is discoverable by watching status codes. That includes an
  Administrator naming somebody else's unshared query: the override reaches shared queries only, and the
  visibility rule is what enforces that rather than a second check.
- A query the caller can see but may not change is **403**. They are already looking at it, so there is nothing
  left to conceal, and a 404 there would be a lie a client cannot act on.
- **Selection is a separate sub-resource** rather than a field on the query. Amending a query is the owner's
  right; choosing to see it is every caseworker's, and keeping them apart is what stops "add this to my list"
  needing edit permission. A saved query is a **live reference, not a copy** — subscribers read the owner's
  edits, because a shared definition each subscriber owns a diverging copy of is the spreadsheet this service
  replaces.

Sharing is a **library, not a push**: publishing a query makes it findable, and each caseworker chooses which
found queries appear in their own navigation. Sharing with a *named* colleague is deliberately absent — this
service has no user directory, and a query is already a URL, so pushing one at somebody is a link in Slack.

The witness delete was the eighteenth, and is **the one witness route restricted by role**: the capability
matrix puts "Delete witnesses" at ✗ for Basic User and ✓ for Administrator, the same split as deleting a
note. The check lives in `WitnessService.removeWitness` rather than on the route, so it holds for every
future caller, and it **refuses before it resolves** — a caller without the role gets 403 whether or not
the witness exists, so the status code cannot be used to tell an existing witness from a missing one. The
delete is a hard one, on the notes precedent, and there is **no minimum number of witnesses** a case must
retain: no artefact states one, so an Administrator may delete the last witness on a case. That is the one
decision here the business might want changed.

Before the route existed, `removeWitness` was an ungated two-argument method safe only because nothing
called it — the shape the note delete had before it was gated. Adding the route without the gate would have
reintroduced that, so the gate landed with it.

The witness create, read and edit were the fourteenth to seventeenth, and arrived the same way the note
delete did: `deed-poll-admin` renders a case's witnesses and had no way to record one, which was the only
place on this surface where data was readable and not writable. The business document gives Basic User
"Create, view and edit witness records", so the capability existed and the route did not.
`WitnessService` already had all five operations and their tests — the routes are the consumer it was
keeping itself for. Neither write is role-gated: the capability matrix marks "Add and edit witnesses" for
both roles.

The note delete was the thirteenth, added 2026-09-08 on exactly that principle rather than against it:
`deed-poll-admin` is losing its data-access layer to this service and its note form has always had a delete
button, so the operation acquired a real consumer. `NoteService.removeNote` already existed and was already
tested — this was the route it was keeping itself for. It is **Administrators only**, enforced the same way
the witness delete is: in `NoteService.removeNote` rather than on the route. Only
`caseworker-deedpoll-admin` is confirmed with IDAM, which is why the restriction is expressed as "requires
the admin role" and never as "requires a Basic User role" — naming that second role would be inventing one.

Every failure answers the same envelope — `{"error": "...", "errors": [{"field": "...", "message": "..."}]}`
(`api/ApiError.java`), so a form can render a message against the input it belongs to.

`/citizen/**` exists, is **empty**, and has its own controller and DTO packages. It is reserved for
`deed-poll-web`; an ArchUnit rule (`NamespaceSeparationSecurityTest`) is what stops a later plan
sharing a caseworker DTO into it.

## Authentication

Every route requires a forwarded IDAM bearer token except a short infrastructure allowlist — `/`,
`/health/**`, `/info`, `/prometheus`, `/v3/api-docs/**`, `/swagger-ui/**`, `/favicon.ico`. See
`SecurityConfiguration.PUBLIC_PATHS`. Authentication is by omission: a route added later is protected
unless somebody adds it to that list, rather than protected when somebody remembers to.

One consequence worth knowing: an unknown path answers **401, not 404**, because authorisation runs
before routing. Which paths exist is not something an unauthenticated caller needs to learn.

Two variables must be set before any `/caseworker/**` call can succeed, and **both default to empty
so that an unconfigured environment refuses every token** rather than trusting an issuer nobody chose:

| Variable | Meaning |
|---|---|
| `IDAM_API_ISSUER_URL` | The IDAM issuer. Endpoints are discovered from it, never configured |
| `IDAM_API_AUDIENCES` | Comma-separated `aud` values that are ours. Empty refuses everything |

Roles come from `/o/userinfo`, not from the token — an IDAM access token carries no roles at all — and
the lookup is deliberately uncached, so a role removed in IDAM is gone from the next request. The one
role modelled is `caseworker-deedpoll-admin`, and it gates three things: whether `privacy` is editable,
deleting a note, and deleting a witness.

> **No route is gated as a route.** Every authenticated principal reaches the whole caseworker surface,
> because the only role name confirmed with IDAM is the admin one and gating everything on it would lock
> out ordinary caseworkers. Naming a non-admin caseworker role is part of the IDAM onboarding that has not
> happened; until it does, `anyRequest().authenticated()` is the whole of the *transport's* authorisation
> rule, and the three checks above live in the domain, where they hold for callers a route does not have.

Outbound S2S (`IDAM_S2S_AUTH_URL`, `DEED_POLL_API_S2S_SECRET`, `S2S_SERVICE_NAME`) is wired but
unused — nothing here calls another CFT service yet, and the secret has a non-working default.

## Documents and uploads

Uploaded files go through the `DocumentStore` interface. The only implementation in this plan is
`LocalFilesystemDocumentStore`: blobs keyed by a generated UUID under `DOCUMENT_STORE_ROOT`
(default `data/documents`, gitignored) so a fresh checkout uploads without being configured first.

> **Development only, and it must not reach a deployed environment.** Pod disk is ephemeral, replicas
> do not share a volume, and the chart declares none — a deployed environment running this loses every
> uploaded file on the next restart and serves 404s from whichever replica did not receive the upload.
> Real storage is CDAM, in plan 03, which adds an implementation beside this one.

The limits are **25 MB per file** and 26 MB per request, and they come from
`UploadPolicy.MAX_UPLOAD_BYTES` rather than from a property — the same constant derives the refusal
message ("The file must be smaller than 25MB"), and a property an environment could override would
let the limit being enforced and the limit being quoted to the caller drift apart. A consequence for
operators: declaring `MultipartConfigElement` as a bean makes **`spring.servlet.multipart.max-file-size`
inert**, so setting it changes nothing. Change the constant.

The accepted-type list is also `UploadPolicy`'s, and it is the client's *declared* MIME type, so treat
it as hygiene rather than as a security control. A narrower list decides which types are served
`inline` rather than as an attachment (`CaseworkerDocumentController.INLINE_MIME_TYPES`); every
download is served under `Content-Security-Policy: default-src 'none'; sandbox`, because a PDF is an
active-content format and a browser PDF viewer runs embedded scripts.

## Plugins

The project contains the following plugins:

  * HMCTS Java plugin

    Applies code analysis tools with HMCTS default settings. See the [project repository](https://github.com/hmcts/gradle-java-plugin) for details.

    Analysis tools include:

    * checkstyle

        https://docs.gradle.org/current/userguide/checkstyle_plugin.html

        Performs code style checks on Java source files using Checkstyle and generates reports from these checks.
        The checks are included in gradle's *check* task (you can run them by executing `./gradlew check` command).

    * org.owasp.dependencycheck

        https://jeremylong.github.io/DependencyCheck/dependency-check-gradle/index.html

        Provides monitoring of the project's dependent libraries and creating a report
        of known vulnerable components that are included in the build. To run it
        execute `gradle dependencyCheck` command.

  * jacoco

    https://docs.gradle.org/current/userguide/jacoco_plugin.html

    Provides code coverage metrics for Java code via integration with JaCoCo.
    You can create the report by running the following command:

    ```bash
      ./gradlew jacocoTestReport
    ```

    The report will be created in build/reports subdirectory in your project directory.

  * io.spring.dependency-management

    https://github.com/spring-gradle-plugins/dependency-management-plugin

    Provides Maven-like dependency management. Allows you to declare dependency management
    using `dependency 'groupId:artifactId:version'`
    or `dependency group:'group', name:'name', version:version'`.

  * org.springframework.boot

    http://projects.spring.io/spring-boot/

    Reduces the amount of work needed to create a Spring application


  * com.github.ben-manes.versions

    https://github.com/ben-manes/gradle-versions-plugin

    Provides a task to determine which dependencies have updates. Usage:

    ```bash
      ./gradlew dependencyUpdates -Drevision=release
    ```

## Building and deploying the application

### Building the application

The project uses [Gradle](https://gradle.org) as a build tool. It already contains
`./gradlew` wrapper script, so there's no need to install gradle.

**Requires JDK 21.** `build.gradle` pins the toolchain and the runtime image is
`base/java:21-distroless`; an older JDK fails before compiling anything, because Gradle has no
resolver configured to download a 21 toolchain for itself.

To build the project execute the following command:

```bash
  ./gradlew build
```

### The database, and what the tests need from Docker

The service uses Postgres, with the schema owned by Flyway (`src/main/resources/db/migration`).
`docker compose up` starts a local Postgres alongside the API; it is published on **5433**, not
5432, because the sibling `deed-poll-admin` stack already binds 5432 and both are routinely
running at once.

Tests never touch that database. Anything needing one starts a throwaway Postgres via
Testcontainers, so `./gradlew build` requires a working Docker but no local setup.

Two things can make Testcontainers fail on a developer machine for reasons unrelated to the
code, both when the build itself runs inside a container that shares the host's Docker daemon:

- **Ryuk, the resource reaper, cannot be reached.** It listens on IPv6 inside its container,
  and some Docker Desktop configurations do not forward that. Export
  `TESTCONTAINERS_RYUK_DISABLED=true`; containers are still removed on a clean JVM exit, only
  crash-cleanup is lost. This is deliberately **not** set in the repo, so CI keeps the reaper.
- **Published ports are not forwarded.** The test harness already handles this by reaching
  containers over the Docker bridge network in preference to their published port — see
  `ContainerDatabase` for the detail.

### Running the application

Create the image of the application by executing the following command:

```bash
  ./gradlew assemble
```

Note: Docker Compose V2 is highly recommended for building and running the application.
In the Compose V2 old `docker-compose` command is replaced with `docker compose`.

Create docker image:

```bash
  docker compose build
```

Run the distribution (created in `build/install/deed-poll-api` directory)
by executing the following command:

```bash
  docker compose up
```

This will start the API container exposing the application's port
(set to `4550` in `application.yaml`).

In order to test if the application is up, you can call its health endpoint:

```bash
  curl http://localhost:4550/health
```

You get a status and nothing else:

```
  {"status":"UP"}
```

That is deliberate, and it is worth knowing before you go looking for the missing detail. This endpoint
is on the unauthenticated allowlist and it aggregates *every* registered contributor — including ones
nobody here chose, such as Boot's auto-configured `diskSpace`, whose details name the pod's absolute
filesystem path and its free bytes. So `management.endpoint.health.show-details` is `never` at the
endpoint level, and the two probe groups re-enable details only for themselves, where the include list
is closed and what can be published is reviewable. Use `/health/readiness` when you want the breakdown.

### The two probes, and why they differ

There are two Kubernetes probes and they deliberately do not check the same things.

```bash
  curl http://localhost:4550/health/readiness
  curl http://localhost:4550/health/liveness
```

**Readiness** — should traffic come to this pod? This is the one that shows its working:

```
  {"status":"UP","components":{
    "db":{"status":"UP"},
    "deedPollSchema":{"status":"UP","details":{"tables":8}},
    "readinessState":{"status":"UP"}}}
```

It checks the database twice over: `db` is `DatabaseHealthIndicator` and `deedPollSchema` asks whether
the eight deed-poll tables are actually there. A connection check alone passes against an unmigrated
database, where the probe stays green while every request 500s.

`db` is ours rather than Spring Boot's — `management.health.db.enabled` is `false` — because Boot's
`DataSourceHealthIndicator` reports a failure as `Health.down(exception)`, and a JDBC message names the
host, the port, the database and the user it failed to authenticate. This group runs with
`show-details: always` on an endpoint that takes no credential, so that message is logged and not
published; a healthy `db` therefore has no details at all. When a table is missing the response
**names it**, so a page that says "readiness DOWN" comes with the diagnosis attached rather than
needing one:

```
  {"status":"DOWN","components":{
    "db":{"status":"UP"},
    "deedPollSchema":{"status":"DOWN","details":{
      "error":"the deed-poll schema is incomplete. Has the migration job run?",
      "missingTables":["deedpoll_notes","deedpoll_witness"]}},
    "readinessState":{"status":"UP"}}}
```

**Liveness** — should this process be killed and restarted? It checks nothing but the process
answering, on purpose. Restarting cannot create a missing table and cannot fix someone else's
database, so putting the database in liveness turns a blip into a rolling restart of every replica
and destroys the pods that would have recovered on their own.

Both are public, because Kubernetes has no token to present. `uk.gov.hmcts.reform.deedpoll.health`
and the `management.endpoint.health.group` block in `application.yaml` are the two places this is
written down; `HealthProbeTest` asserts it over HTTP, including that readiness answers **503** and
not 200 when the database is unreachable.

**Kubernetes does not consult them yet.** The chart is now `charts/deed-poll-api`, with this service's
name, image and ingress host, but no probe path is configured — so these endpoints are tested and not
yet wired. Naming the two paths is part of the outstanding deployment onboarding.

### The OpenAPI document

Published at `/v3/api-docs`, and browsable at `/swagger-ui.html`. `springdoc.packagesToScan` covers
`…deedpoll.api`, so a controller is documented by being in the package it belongs in rather than by
somebody remembering to add it. `OpenAPIPublisherTest` writes the document to
`/tmp/openapi-specs.json`, which is where the HMCTS Jenkins library reads it to publish to
[`hmcts/cnp-api-docs`](https://github.com/hmcts/cnp-api-docs), and asserts that it actually
describes the caseworker surface rather than merely being served.

### Alternative script to run application

To skip all the setting up and building, just execute the following command:

```bash
./bin/run-in-docker.sh
```

For more information:

```bash
./bin/run-in-docker.sh -h
```

The script sets **no** environment variables of its own — it runs `./gradlew clean` / `assemble` when
asked and then `docker compose up`, so `-c` and `-i` are the only options that do anything and the
"Available parameters" block is empty. Configuration comes from `docker-compose.yml` and `.env`; see
[Authentication](#authentication) for the two variables a caseworker call needs.

Whenever any variable is changed, or any other script regarding docker image/container build, the suggested way to ensure all is cleaned up properly is by this command:

```bash
docker compose rm
```

It clears stopped containers correctly. Might consider removing clutter of images too, especially the ones fiddled with:

```bash
docker images

docker image rm <image-id>
```

There is no need to remove postgres and java or similar core images.

## License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details

