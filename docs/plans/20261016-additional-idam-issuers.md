# Plan: accept IDAM's ForgeRock issuer as well as the discovered one

## Overview

The CCD onboarding smoke in AAT (`docs/CCD-SMOKE.md`, step 2, run 2026-10-16) found that every password-grant token
carries this `iss`:

`https://forgerock-am.service.core-compute-idam-aat2.internal:8443/openam/oauth2/realms/root/realms/hmcts`

That applies whether the token came from `idam-api` or `hmcts-access` `/o/token`. The api accepts only the
discovered issuer, `https://hmcts-access.aat.platform.hmcts.net/o`, so it refuses the system user's token with
`invalid_token: The iss claim is not valid`. Linking signs in as that system user, and data-store forwards its
token to `/ccd-persistence`, which uses the same decoder. **So until this is fixed, every CCD link fails with 401.**

The signature already validates: Spring checks claims only after it, and the key set discovered from
`hmcts-access` signs these tokens. Userinfo at the discovered `hmcts-access` endpoint accepts them too (200 with
roles, checked 10-16). **The issuer claim is the only thing in the way.** CFT services accept this issuer
explicitly: ia-case-api's `IDAM_SECURITY_ALLOWED_ISSUERS_2`, HMC's `OIDC_ISSUER_FORGEROCK`.

This plan adds a configured list of additional trusted issuers. It defaults to empty, so deploying it changes
nothing until flux sets it. No discovery, key, audience, expiry or token-kind check changes.

## Context

- `auth/IdamJwtDecoder.build` chains `JwtTimestampValidator`, `JwtIssuerValidator(metadata.issuer())`,
  `IdamAudienceValidator` and `IdamAccessTokenValidator`. The issuer validator is the only one being replaced.
- `auth/IdamProperties` is the record `(issuerUrl, audiences)` under `idam.api`, read from `IDAM_API_ISSUER_URL` and
  `IDAM_API_AUDIENCES` in `application.yaml`.
- `auth/IdamDiscovery` requires the configured issuer to be absolute `https` (refusing cleartext), and requires
  the advertised issuer to equal it.
- One `JwtDecoder` bean serves both `securityFilterChain` and `ccdPersistenceFilterChain` (`SecurityConfiguration`).
- api#107 added `auth/SecurityEvents`. A rejected token goes through `LoggingAuthenticationEntryPoint`, which
  emits `auth.unauthenticated`. `config/SecurityEventLoggingTest` covers it.
- Test fixture: `auth/StubIdam.accessToken(customise)` mints a token whose claims a test can change, the issuer
  included.

## Decisions: implement these, do not revisit

1. **Property.** `idam.api.additional-issuers: ${IDAM_API_ADDITIONAL_ISSUERS:}`, comma-separated, the same
   shape as `audiences`. Default empty. Add it to `IdamProperties`: trim entries, drop empty ones, and drop
   one trailing `/`.
2. **Each additional issuer must be an absolute `https` URL.** Anything else fails at startup, naming the
   variable but not the value's query string. This is the same rule `IdamDiscovery` applies to the main issuer.
   An entry equal to the main issuer is harmless, and is de-duplicated.
3. **The validator is Spring's own; there is no new class.** Replace `new JwtIssuerValidator(metadata.issuer())`
   with Spring's `JwtClaimValidator`, using the same predicate Spring's `JwtIssuerValidator` is built on, applied
   to a list:

   ```java
   new JwtClaimValidator<Object>(JwtClaimNames.ISS,
       iss -> iss != null && trustedIssuers.contains(iss.toString()))
   ```

   - `trustedIssuers` is an immutable `List`: the discovered issuer first, then the additional ones,
     de-duplicated. A `List` because `Set.of(...).contains(null)` throws rather than answering false. Null is
     checked first anyway.
   - `toString()`, because Spring may hand over `iss` as a URL rather than a String, which is why its own
     validator does the same.
   - Exact string comparison: no prefix, case-folding or path matching. A missing `iss` is refused.
   - Spring's `JwtIssuerValidator` can't be used twice, because `DelegatingOAuth2TokenValidator` combines with AND.
   - Spring's multi-issuer `JwtIssuerAuthenticationManagerResolver` is wrong here: it discovers a key set per
     issuer (it would discover against ForgeRock's internal 8443 address), and these are two names for one key set.
   - The failure is `invalid_token` with `The iss claim is not valid`, as now, so logs and #107's events read the same.
4. **Keys come only from discovery of the main issuer.** No JWKS is fetched for an additional issuer, and none
   is discovered. An additional issuer is a name accepted on tokens that the main key set signs. A token
   signed by any other key still fails before the issuer is read.
5. **Both chains** get it through the one decoder bean: the caseworker/judicial chain and `/ccd-persistence`.
6. **Events unchanged.** A wrong-issuer token still produces 401 through `LoggingAuthenticationEntryPoint`
   and emits `auth.unauthenticated`. Pin that with a test.
7. **Docs.** Add the setting and why it exists to `CLAUDE.md`, as one short paragraph under the auth
   conventions. Comment it in `application.yaml`.
8. **Out of scope:**
   - the flux change that sets `IDAM_API_ADDITIONAL_ISSUERS` in `aat.yaml` (separate, after merge)
   - any change to discovery, userinfo or audiences
   - the chart's `values.yaml`, which stays unset because only AAT needs it today

## Development Approach

- Each task: implement, add tests, run the validation commands, commit.
- Run every Gradle command in the foreground only. Never background a build.
- Follow the auth package's Javadoc style. Doc comments say why.
- Don't change the order or members of the other three validators.

## Validation Commands

Run from this worktree with Java 21 and Docker. Green on the starting commit `5fffecd`; see Execution notes.

```bash
export TESTCONTAINERS_RYUK_DISABLED=true JAVA_TOOL_OPTIONS=-XX:UseSVE=0
./gradlew test --tests '*Idam*' --tests '*SecurityEvent*' --tests '*SecurityConfiguration*' --no-daemon
./gradlew test --tests '*Security*' --tests '*Staff*' --tests '*Conformance*' --no-daemon
./gradlew build --no-daemon
git diff --check
```

If Checkstyle's Gradle worker dies with SIGILL or SIGABRT, run Checkstyle directly over all five source sets, as
`docs/plans/completed/20261014-me-caller-access.md` records. It must report **0 warnings**. Record actual counts from
`build/test-results`.

## Implementation Steps

### Task 1: The property and the validator

Files: `auth/IdamProperties.java`, `auth/IdamJwtDecoder.java`, `application.yaml`, plus tests next to each. No new
validator class (decision 3).

- [x] Add `additionalIssuers` to `IdamProperties`, per decisions 1–2. Unit tests:
  - trimming, empties dropped, trailing slash
  - a non-`https`, relative or unparseable entry fails, naming `IDAM_API_ADDITIONAL_ISSUERS`
- [x] In `IdamJwtDecoder.build`, replace `JwtIssuerValidator` with the `JwtClaimValidator` of decision 3, in the same
      position in the chain. Comment the property in `application.yaml`.
- [x] Tests on the decoder's issuer check, using `StubIdam` tokens:
  - the main issuer passes, and an additional issuer passes
  - refused: an unlisted issuer, a missing `iss`, a prefix or suffix of a listed issuer, a different case,
    and a trailing slash difference
  - the refusal's description is still `The iss claim is not valid`
- [x] Mark completed

### Task 2: Prove it through the decoder and the filter chains

Files: the existing `IdamJwtDecoder`/security tests, or a new `config/AdditionalIssuerSecurityTest.java`, using
`StubIdam.accessToken(customise)`.

- [x] With `idam.api.additional-issuers` unset: a token whose `iss` is the ForgeRock URL is refused with 401 on
      `/caseworker/document-types`, and `auth.unauthenticated` is emitted (decision 6).
- [x] With it set to the ForgeRock URL:
  - that token is admitted, with the same body as a main-issuer token
  - a token with another unlisted issuer is still refused with 401, and the event is emitted
  - a ForgeRock-issuer token signed by a key **outside** the stub's key set is refused (decision 4)
  - a ForgeRock-issuer token with the wrong audience is still refused
- [x] `/ccd-persistence`: with S2S `ccd_data` allowed by the existing stubs and the additional issuer set, a
      ForgeRock-issuer token gets past authentication. Assert it isn't the 401 an unlisted issuer gets. Copy the
      existing `CcdPersistenceSecurityTest` set-up.
- [x] Mark completed

### Task 3: Documentation and handoff

- [ ] `CLAUDE.md`: one paragraph on `IDAM_API_ADDITIONAL_ISSUERS`. Explain why (password-grant tokens carry
      ForgeRock's internal issuer; the system user and `/ccd-persistence` depend on it) and that keys still come
      only from the main issuer's discovery.
- [ ] Record the validation counts, the Checkstyle result and the branch SHA in Execution notes. Don't claim
      any live verification.
- [ ] Mark completed

## Execution notes

**Baseline, measured 2026-10-16 on `5fffecd`** (Java 21, `-XX:UseSVE=0`, Ryuk disabled):
- first selector: 95 tests, 0 failures
- second selector: 606 tests, 0 failures
- `./gradlew build`, Checkstyle tasks excluded: 4,242 unit and 20 integration tests, 0 failures, 0 skipped

**Task 1** (`IdamProperties`, `IdamJwtDecoder`, `application.yaml`, `IdamPropertiesTest`, new `IdamJwtDecoderTest`):
- a bad entry fails binding with `IDAM_API_ADDITIONAL_ISSUERS entry N is not an absolute https URL`, naming its
  position and never the value; an upper-case `HTTPS` scheme is accepted, as discovery accepts it
- de-duplication happens in `IdamProperties` and again when the decoder puts the discovered issuer first
- the decoder test can't cover an additional issuer equal to the main one, because the stub issuer is cleartext and
  an additional issuer must be `https`; the properties test covers de-duplication
- first selector: 120 tests, 0 failures
- second selector: 606 tests, 0 failures
- `./gradlew build` failed only in `checkstyleFunctionalTest` (worker SIGILL/SIGABRT, exit 134); with the Checkstyle
  tasks excluded: 4,267 unit and 20 integration tests, 0 failures, 0 skipped
- Checkstyle run directly over all five source sets: 0 warnings
- `git diff --check`: clean

**Task 2** (new `config/AdditionalIssuerSecurityTest`, one test added to `SecurityEventLoggingTest`, and a
`StubIdam.tokenSignedByAnotherKey(customise)` overload):
- the unset case is in `SecurityEventLoggingTest`, which needs no database because the 401 comes before any
  controller: it covers a ForgeRock-issuer token on `/caseworker/document-types`, `The iss claim is not valid`, and
  `auth.unauthenticated` with reason `invalid_token`
- the set case runs against Postgres, so the ForgeRock-issuer and main-issuer bodies from `/caseworker/document-types`
  can be compared. The ForgeRock token gets the same body, with no security event. An unlisted issuer, a foreign key
  and a wrong audience each get 401 plus one `auth.unauthenticated`. On `/ccd-persistence/cases`, the ForgeRock
  system user reaches the SDK's 400 for the missing `Idempotency-Key`, while an unlisted issuer gets 401
- first selector: 121 tests, 0 failures
- second selector: 612 tests, 0 failures
- `./gradlew build` again failed only in `checkstyleFunctionalTest` (worker SIGABRT, exit 134); with the Checkstyle
  tasks excluded: 4,273 unit and 20 integration tests, 0 failures, 0 skipped
- Checkstyle run directly over all five source sets: 0 warnings
- `git diff --check`: clean

## Post-Completion

Not Ralphex checkboxes:
- a direct scoped external review, then the PR
- after merge, a flux PR adding `IDAM_API_ADDITIONAL_ISSUERS` (the ForgeRock URL above) to
  `apps/deed-poll/deed-poll-api/aat.yaml`
- then re-run CCD-SMOKE step 2.3 and continue to step 3
