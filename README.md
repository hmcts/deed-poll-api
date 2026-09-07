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

Twelve caseworker operations, and deliberately no thirteenth. `/swagger-ui.html` is the authority;
this table is the map.

| Method | Path | What it does |
|---|---|---|
| GET | `/caseworker/cases` | The case list, filtered by a saved-query `filter` key |
| GET | `/caseworker/cases/field-rules` | Show / hide / mandate rules for a `type`, `migrated` and `publishToGazette` |
| GET | `/caseworker/cases/{id}` | One case **with** its notes, witnesses and documents — one call, not four |
| GET | `/caseworker/document-types` | Every supporting-document type, with its applicability arrays |
| POST | `/caseworker/cases` | Create a case, allocating its reference number |
| PATCH | `/caseworker/cases/{id}` | Apply a patch — PATCH, not PUT, because the forms save one tab at a time |
| GET | `/caseworker/cases/{id}/notes` | A case's notes |
| POST | `/caseworker/cases/{id}/notes` | Add a note |
| GET | `/caseworker/cases/{id}/notes/{noteId}` | One note |
| PATCH | `/caseworker/cases/{id}/notes/{noteId}` | Amend a note |
| POST | `/caseworker/cases/{id}/documents` | Upload a file (multipart) and record its document row |
| GET | `/caseworker/cases/{id}/documents/{documentId}/file` | Stream a document's stored file back |

There are deliberately **no** case deletes, no note delete, no witness writes, no document-type writes
and no `validate` endpoint. The domain implements and tests them; nothing consumes them, and the
sibling prototype's mistake was building an API for an imagined consumer.

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
role modelled is `caseworker-deedpoll-admin`, and it gates exactly one thing: whether `privacy` is
editable.

> **Not yet role-gated.** Every authenticated principal reaches the whole caseworker surface, because
> the only role name confirmed with IDAM is the admin one and gating everything on it would lock out
> ordinary caseworkers. Naming a non-admin caseworker role is part of the IDAM onboarding that has not
> happened; until it does, `anyRequest().authenticated()` is the whole of the authorisation rule.

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

You should get a response similar to this:

```
  {"status":"UP","components":{
    "db":{"status":"UP","details":{"database":"PostgreSQL","validationQuery":"isValid()"}},
    "deedPollSchema":{"status":"UP","details":{"tables":6}},
    "diskSpace":{"status":"UP","details":{"total":249644974080,"free":137188298752,"threshold":10485760}},
    "livenessState":{"status":"UP"},"ping":{"status":"UP"},"readinessState":{"status":"UP"}}}
```

### The two probes, and why they differ

There are two Kubernetes probes and they deliberately do not check the same things.

```bash
  curl http://localhost:4550/health/readiness
  curl http://localhost:4550/health/liveness
```

**Readiness** — should traffic come to this pod? It checks the database twice over: `db` is Spring
Boot's own connection check, and `deedPollSchema` asks whether the six deed-poll tables are actually
there. A connection check alone passes against an unmigrated database, where the probe stays green
while every request 500s. When a table is missing the response **names it**, so a page that says
"readiness DOWN" comes with the diagnosis attached rather than needing one:

```
  {"status":"DOWN","components":{"deedPollSchema":{"status":"DOWN","details":{
    "error":"the deed-poll schema is incomplete. Has the migration job run?",
    "missingTables":["deedpoll_notes","deedpoll_witness"]}}}}
```

**Liveness** — should this process be killed and restarted? It checks nothing but the process
answering, on purpose. Restarting cannot create a missing table and cannot fix someone else's
database, so putting the database in liveness turns a blip into a rolling restart of every replica
and destroys the pods that would have recovered on their own.

Both are public, because Kubernetes has no token to present. `uk.gov.hmcts.reform.deedpoll.health`
and the `management.endpoint.health.group` block in `application.yaml` are the two places this is
written down; `HealthProbeTest` asserts it over HTTP, including that readiness answers **503** and
not 200 when the database is unreachable.

**Kubernetes does not consult them yet.** `charts/rpe-spring-boot-template` is still the template's —
the chart name, the image and the ingress host are `spring-boot-template`'s, and no probe path is
configured — so these endpoints are tested but not yet wired. Renaming the chart and naming the two
paths is part of the outstanding deployment onboarding, which is deliberately outside the build plans.

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

