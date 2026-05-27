---
title: "fix: Make runner install source-aware"
type: fix
date: 2026-05-27
brainstorm: docs/brainstorms/2026-05-27-runner-install-image-source-brainstorm.md
---

# fix: Make runner install source-aware

## Overview

Runners 페이지의 Install 버튼이 `learnloop-runner-rust:latest` 같은 로컬 image name에도 무조건 `docker pull`을 실행해서 Docker Hub `pull access denied` 오류를 노출한다. Install의 의미를 “remote pull”이 아니라 “현재 설치 형태에서 runner를 사용 가능하게 만들기”로 바꾼다.

이번 변경은 세 설치 형태를 구분한다.

- Source checkout: registry 없는 local image를 사용하며, 없으면 backend가 포함한 runner build context로 local Docker build를 수행한다.
- Online release: GHCR public runner package를 pull한다.
- Offline release: bundle install 단계에서 load된 image를 확인하며, 누락 시 pull/build 대신 재설치 안내를 반환한다.

## Confirmed Decisions

- Source checkout 환경에서 Rust/Swift optional runner image가 없으면 Install 버튼이 local Docker build까지 자동 수행한다.
- Online release는 GHCR public runner package를 기본으로 사용한다.
- Offline/full bundle은 별도 옵션으로 유지한다.
- 긴 build/pull UI는 Docker raw log나 percent progress가 아니라 단계 기반 상태만 표시한다.

## Current Root Cause

- `RunnerLanguageService.install()`은 image 존재 여부와 image source를 보지 않고 `imageClient.pull(descriptor.imageRef)`를 호출한다.
- `ProcessRunnerImageClient.pull()`은 그대로 `docker pull <imageRef>`를 실행한다.
- registry 없는 `learnloop-runner-rust:latest`는 Docker가 Docker Hub repository로 해석한다.
- 로컬에 image가 이미 있어도 `docker pull learnloop-runner-rust:latest`는 local image를 사용하지 않는다.
- GHCR image도 현재 anonymous pull이 `denied`라서 release CI에서 공개 pull 검증이 필요하다.

## Affected Areas

- Backend runner domain: `backend/src/main/kotlin/com/aicodelearning/runner/RunnerImageCatalog.kt`
- Backend install service: `backend/src/main/kotlin/com/aicodelearning/runner/RunnerLanguageInstallation.kt`
- Backend properties/build context: `backend/src/main/kotlin/com/aicodelearning/runner/RunnerProperties.kt`, `backend/Dockerfile`
- Runner image scripts: `scripts/build-runner-images.sh`, `scripts/install.sh`, `scripts/start.sh`
- Release packaging: `scripts/package-release.sh`, `packaging/release-bundle/start.sh`, `packaging/release-bundle/docker-compose.yml`
- Release CI: `.github/workflows/release.yml`
- Frontend Runners UI: `frontend/src/App.tsx`, `frontend/src/api/client.ts`
- Installed E2E: `scripts/e2e-installed.mjs`

## Proposed Solution

Introduce a source-aware runner install pipeline:

1. Resolve each runner descriptor with an `imageSource`:
   - `local` when `APP_RUNNER_IMAGE_REGISTRY` is empty and release mode is not set.
   - `registry` when `APP_RUNNER_IMAGE_REGISTRY` is set for online release.
   - `bundled` when release runner mode is offline.
2. Change install flow to:
   - set status `installing`, stage `checking_image`
   - run `docker image inspect`
   - if image exists, mark `installed`
   - if source is `local`, set stage `building_local_image`, run `docker build -t <imageRef> <context>`
   - if source is `registry`, set stage `pulling_image`, run `docker pull <imageRef>`
   - if source is `bundled`, fail with a remediation message if image is missing
   - set stage `verifying_image`, inspect again, then mark `installed` or `failed`
3. Make install API return quickly and perform long work asynchronously so the UI can poll stage changes.
4. Add release guards so online packages cannot ship unless runner images are anonymous-pullable.

## Implementation Phases

Each phase below is intentionally small enough to implement, run focused verification, and commit independently. Do not batch adjacent phases unless the previous phase already passes its verification.

### Phase 0: Lock in the regression with a failing test

- [ ] Add or update a `RunnerLanguageServiceTest` that models a local bare image ref: `learnloop-runner-rust:latest`.
- [ ] Configure fake inspect to return success.
- [ ] Assert install marks Rust installed.
- [ ] Assert fake image client did not receive `pull`.
- [ ] Keep the test failing before implementation.

Verification:

- [ ] `./gradlew :backend:test --tests "*RunnerLanguageServiceTest"` fails only on the new regression assertion before the fix.

### Phase 1: Add image source constants only

- [ ] Add `RunnerImageSources` constants or a small enum with `local`, `registry`, `bundled`.
- [ ] Add `imageSource` to `RunnerLanguageDescriptor`.
- [ ] Default every descriptor to `local` temporarily.
- [ ] Do not change install behavior yet.

Verification:

- [ ] `RunnerImageCatalogTest` asserts every default descriptor has `imageSource = local`.
- [ ] `./gradlew :backend:test --tests "*RunnerImageCatalogTest"` passes.

### Phase 2: Parse image source from environment

- [ ] Add catalog parsing for `APP_RUNNER_IMAGE_SOURCE`.
- [ ] Accept only `local`, `registry`, `bundled`.
- [ ] Derive `registry` when source is blank and `APP_RUNNER_IMAGE_REGISTRY` is nonblank.
- [ ] Derive `local` when source and registry are both blank.
- [ ] Keep invalid values rejected through `BadRequestException` or startup-safe fallback with a test-backed decision.

Verification:

- [ ] Catalog test: blank source + blank registry -> `local`.
- [ ] Catalog test: blank source + GHCR registry -> `registry`.
- [ ] Catalog test: explicit `bundled` -> `bundled`.
- [ ] Catalog test: invalid source is rejected or normalized exactly as implemented.

### Phase 3: Expose image source in API response

- [ ] Add `imageSource` to `RunnerLanguageResponse`.
- [ ] Populate it in `toResponse`.
- [ ] Add `imageSource` to frontend `RunnerLanguageResponse`.
- [ ] Do not render it in UI yet.

Verification:

- [ ] Backend service test asserts Rust response includes `local`.
- [ ] `npm --prefix frontend run typecheck` passes.

### Phase 4: Add install progress fields to schema

- [ ] Add migration `V22__runner_install_progress.sql`.
- [ ] Add nullable `install_stage`.
- [ ] Add nullable `last_error_code`.
- [ ] Do not alter existing status semantics in this phase.

Verification:

- [ ] Migration integration test verifies existing rows can migrate.
- [ ] `./gradlew :backend:test --tests "*RunnerLanguageInstallationMigrationIntegrationTest"` passes.

### Phase 5: Wire progress fields through backend DTOs

- [ ] Add `installStage` and `lastErrorCode` to `RunnerLanguageInstallationEntity`.
- [ ] Add the same fields to `RunnerLanguageResponse`.
- [ ] Populate response fields from entity.
- [ ] Keep frontend type update in the same phase.

Verification:

- [ ] Service test asserts fresh catalog rows return null stage/error code.
- [ ] `npm --prefix frontend run typecheck` passes.

### Phase 6: Add Docker build primitive without using it

- [ ] Add `build(imageRef, contextPath)` to `RunnerImageClient`.
- [ ] Update fake image clients in tests with a recorded `built` list.
- [ ] Implement `ProcessRunnerImageClient.build()` with `ProcessBuilder(listOf(docker, "build", "-t", imageRef, contextPath))`.
- [ ] Reuse existing timeout, sanitization, and output truncation.

Verification:

- [ ] `ProcessRunnerImageClientTest` asserts build receives separate args.
- [ ] Existing runner image client tests pass.

### Phase 7: Classify local build context failures

- [ ] Before running `docker build`, detect missing context path.
- [ ] Return `errorCode = local_build_context_missing`.
- [ ] Return a short detail that does not mention stack traces or raw shell output.
- [ ] Add a test using a missing temp path.

Verification:

- [ ] `./gradlew :backend:test --tests "*ProcessRunnerImageClientTest"` passes.

### Phase 8: Add build context root property

- [ ] Add `buildContextRoot` to `RunnerProperties`.
- [ ] Default it to `/app/runner`.
- [ ] Add a helper that resolves a language context path from root + language.
- [ ] Keep the helper unit-testable without Docker.

Verification:

- [ ] Unit test verifies Rust context resolves to `/app/runner/rust`.
- [ ] `./gradlew :backend:test --tests "*Runner*"` passes.

### Phase 9: Copy runner build contexts into backend image

- [ ] Update `backend/Dockerfile` to copy `runner/` into the runtime image.
- [ ] Ensure only Dockerfiles and runner scripts are copied, not host build artifacts.
- [ ] Keep backend user unchanged.

Verification:

- [ ] `docker compose --env-file .env -f docker-compose.install.yml build backend` passes.
- [ ] `docker compose --env-file .env -f docker-compose.install.yml -f docker-compose.runner.yml up -d backend` passes.
- [ ] `docker compose --env-file .env -f docker-compose.install.yml exec -T backend test -f /app/runner/rust/Dockerfile` passes.

### Phase 10: Change install to inspect first

- [ ] In `RunnerLanguageService.install`, call `inspect` before any acquire operation.
- [ ] If inspect succeeds, mark installed immediately.
- [ ] Clear `lastError`, `lastErrorCode`, and `installStage` on success.
- [ ] Leave missing-image behavior otherwise unchanged for now.

Verification:

- [ ] Phase 0 regression test now passes.
- [ ] Test asserts local existing image does not call pull or build.

### Phase 11: Implement local-source missing image build

- [ ] When inspect fails and descriptor source is `local`, set `installStage = building_local_image`.
- [ ] Call `imageClient.build(imageRef, contextPath)`.
- [ ] Verify with a second inspect after successful build.
- [ ] Mark installed only after verify inspect succeeds.

Verification:

- [ ] Unit test: local missing image calls build once.
- [ ] Unit test: local build success but verify inspect failure marks failed.
- [ ] Unit test: bare local image still never calls pull.

### Phase 12: Implement registry-source missing image pull

- [ ] When inspect fails and descriptor source is `registry`, set `installStage = pulling_image`.
- [ ] Call `imageClient.pull(imageRef)`.
- [ ] Verify with inspect after successful pull.
- [ ] Keep registry failure isolated from local source behavior.

Verification:

- [ ] Unit test: registry missing image calls pull once.
- [ ] Unit test: registry source does not call build.
- [ ] Unit test: pull success but verify failure marks failed.

### Phase 13: Implement bundled-source missing image handling

- [ ] When inspect fails and descriptor source is `bundled`, do not pull or build.
- [ ] Set status `failed`.
- [ ] Set `lastErrorCode = bundled_image_missing`.
- [ ] Set `lastError` to a remediation message telling the user to reinstall/import the bundle image.

Verification:

- [ ] Unit test: bundled missing image calls neither pull nor build.
- [ ] Unit test: response contains `bundled_image_missing`.

### Phase 14: Map Docker errors to product messages

- [ ] Add a small mapper from image operation `errorCode` to user-facing message.
- [ ] Cover `registry_auth_failed`, `docker_daemon_unreachable`, `disk_full`, `timeout`, `local_build_context_missing`.
- [ ] Store raw sanitized detail only if still needed internally; response `lastError` should be product-facing.

Verification:

- [ ] Unit test: Docker Hub `pull access denied` maps to a registry access message.
- [ ] Unit test: response does not include `pull access denied for learnloop-runner-rust`.

### Phase 15: Add synchronous install stage tests

- [ ] Add tests for stage sequence in the current synchronous implementation.
- [ ] Assert `checking_image` is set before inspect if observable through fake store.
- [ ] Assert `building_local_image`, `pulling_image`, and `verifying_image` are persisted in the right branches.

Verification:

- [ ] `./gradlew :backend:test --tests "*RunnerLanguageServiceTest"` passes.

### Phase 16: Extract install worker without async

- [ ] Move the acquire logic into a dedicated internal method or collaborator.
- [ ] Keep public install behavior synchronous for this phase.
- [ ] Keep all tests from phases 10-15 passing.
- [ ] This phase should be a pure refactor.

Verification:

- [ ] `./gradlew :backend:test --tests "*RunnerLanguageServiceTest"` passes without assertion changes.

### Phase 17: Make install endpoint return after scheduling

- [ ] Introduce an injectable executor for runner install work.
- [ ] On request, mark row `installing` + `checking_image`.
- [ ] Schedule the extracted worker.
- [ ] Return current response immediately.
- [ ] In tests, use a same-thread executor to keep deterministic assertions.

Verification:

- [ ] Controller/service test: install returns `installing` before worker completion when using a paused fake executor.
- [ ] Existing service tests pass with same-thread executor.

### Phase 18: Prevent duplicate in-flight installs

- [ ] Track one in-flight operation per language.
- [ ] If the same language is already installing, return current row.
- [ ] Remove in-flight marker in a finally block.
- [ ] Keep per-language behavior independent.

Verification:

- [ ] Unit test: second Rust install does not schedule a second worker.
- [ ] Unit test: Rust install does not block Swift install.

### Phase 19: Recover stale installing state on refresh

- [ ] On refresh, inspect images as today.
- [ ] If a row is `installing` but no in-flight work exists, map installed image to `installed`.
- [ ] If no image exists, map selected default to `missing` and optional to `available` or `failed` with a clear stale message.
- [ ] Clear stale `installStage`.

Verification:

- [ ] Unit test: stale installing + existing image -> installed.
- [ ] Unit test: stale installing + missing optional image -> available or failed exactly as implemented.

### Phase 20: Render source and stage in Runners UI

- [ ] Add frontend label mapping for `installStage`.
- [ ] Show stage text under the row while status is `installing`.
- [ ] Optionally show image source as subdued metadata if useful for troubleshooting.
- [ ] Keep row layout consistent with current Runners page.

Verification:

- [ ] `npm --prefix frontend run typecheck` passes.
- [ ] Browser check shows stage text in an installing state.

### Phase 21: Poll while installs are active

- [ ] Add a `useEffect` that polls runner languages while any row is `installing`.
- [ ] Stop polling when no rows are installing or the user leaves Runners page.
- [ ] Use a modest interval such as 2 seconds.
- [ ] Avoid starting multiple overlapping polls.

Verification:

- [ ] Frontend typecheck passes.
- [ ] Manual browser check: installing row updates without pressing Refresh.

### Phase 22: Scope UI loading state per language

- [ ] Replace global `runnerLoading` install blocking with a per-language pending set.
- [ ] Keep Refresh global loading separate.
- [ ] Disable Install/Remove only for the active row and during refresh where necessary.

Verification:

- [ ] Typecheck passes.
- [ ] Manual UI check: installing Rust does not disable unrelated Swift controls unless refresh is running.

### Phase 23: Add local script env defaults

- [ ] Update `scripts/install.sh` generated `.env` with `APP_RUNNER_IMAGE_SOURCE=local`.
- [ ] Update `scripts/start.sh` to append `APP_RUNNER_IMAGE_SOURCE=local` if absent and registry is blank.
- [ ] Update `.env.example`.

Verification:

- [ ] `sh -n scripts/install.sh scripts/start.sh`.
- [ ] Running `./scripts/start.sh` on an existing env does not duplicate keys.

### Phase 24: Add release package source env

- [ ] Update `scripts/package-release.sh` to write release runner mode into `.release-runner.env`.
- [ ] Update `packaging/release-bundle/start.sh` to set `APP_RUNNER_IMAGE_SOURCE=registry` for online mode.
- [ ] Set `APP_RUNNER_IMAGE_SOURCE=bundled` for offline mode.
- [ ] Update release bundle Compose env passthrough if needed.

Verification:

- [ ] `sh -n scripts/package-release.sh packaging/release-bundle/start.sh`.
- [ ] Inspect generated staging `.release-runner.env` in a local package build if practical.

### Phase 25: Guard online package registry configuration

- [ ] In `scripts/package-release.sh`, fail online mode if registry is empty.
- [ ] Print a clear message describing `APP_RUNNER_IMAGE_REGISTRY`.
- [ ] Do not affect offline mode.

Verification:

- [ ] Script-level test or manual command with `RUNNER_IMAGE_MODE=online APP_RUNNER_IMAGE_REGISTRY=` fails before build.
- [ ] Offline mode still reaches image build/save path.

### Phase 26: Add unauthenticated GHCR pull check in release CI

- [ ] In `.github/workflows/release.yml`, logout or use a clean Docker config before pull smoke.
- [ ] Pull published runner image without credentials.
- [ ] Check release tag image for every language.
- [ ] Check `latest` at least once, or all languages if matrix cost is acceptable.

Verification:

- [ ] YAML syntax remains valid.
- [ ] The job would fail if package visibility is private.

### Phase 27: Update docs for the three install modes

- [ ] Update `README.md`.
- [ ] Update `README.ko.md`.
- [ ] Update `packaging/release-bundle/README.md`.
- [ ] Include the exact behavior of `local`, `registry`, and `bundled`.
- [ ] Mention Swift/Rust local build can take a long time.

Verification:

- [ ] Documentation references `APP_RUNNER_IMAGE_SOURCE`.
- [ ] No doc says Install always pulls from registry.

### Phase 28: Add installed E2E coverage for Runners page

- [ ] Extend `scripts/e2e-installed.mjs` to visit Runners.
- [ ] Assert optional Rust row renders without raw Docker pull error.
- [ ] If a test-only API/fake is needed, prefer backend unit coverage instead of making E2E slow.
- [ ] Keep E2E runtime reasonable.

Verification:

- [ ] `node --check scripts/e2e-installed.mjs`.
- [ ] `./scripts/e2e-installed.sh` passes.

### Phase 29: Manual regression check

- [ ] Ensure Docker is running.
- [ ] Remove Rust image: `docker rmi learnloop-runner-rust:latest` if present.
- [ ] Start app from source checkout.
- [ ] Click Runners -> Rust -> Install.
- [ ] Confirm UI shows local build stage, not Docker Hub denial.
- [ ] Refresh and confirm Rust is installed.

Verification:

- [ ] `docker image inspect learnloop-runner-rust:latest` succeeds after install.
- [ ] UI row status is `Installed`.

### Phase 30: Full final verification

- [ ] Run focused backend runner tests.
- [ ] Run full backend tests.
- [ ] Run frontend typecheck and build.
- [ ] Run installed E2E.
- [ ] Run shell syntax checks for changed scripts.

Verification:

- [ ] `./gradlew :backend:test --tests "*Runner*"`
- [ ] `./gradlew :backend:test`
- [ ] `npm --prefix frontend run typecheck`
- [ ] `npm --prefix frontend run build`
- [ ] `node --check scripts/e2e-installed.mjs`
- [ ] `./scripts/e2e-installed.sh`
- [ ] `sh -n scripts/install.sh scripts/start.sh scripts/package-release.sh packaging/release-bundle/start.sh`

## Acceptance Criteria

- [ ] Clicking Install for `learnloop-runner-rust:latest` does not execute `docker pull learnloop-runner-rust:latest`.
- [ ] If the local Rust image already exists, Install marks it installed without network access.
- [ ] If source checkout local Rust image is missing, Install builds `runner/rust` locally and verifies the image.
- [ ] Online release uses GHCR image refs and CI verifies anonymous pull.
- [ ] Offline release never attempts remote pull for bundled runner images.
- [ ] UI shows stage-based progress for long build/pull operations.
- [ ] UI no longer displays raw Docker Hub denial messages for local image names.
- [ ] Existing default TypeScript/Java/Kotlin runner behavior remains compatible.

## Risks and Mitigations

- Risk: Backend container lacks build context for local build.
  - Mitigation: Copy `runner/` into backend runtime image and verify file existence inside container.
- Risk: Async install introduces race conditions.
  - Mitigation: one in-flight operation per language, synchronous executor tests, persisted stages.
- Risk: GHCR package visibility remains private.
  - Mitigation: unauthenticated pull smoke is required before release success.
- Risk: Swift build takes a long time.
  - Mitigation: stage-based progress and generous install timeout; no raw log streaming in MVP.
- Risk: Raw Docker output may leak credentials.
  - Mitigation: keep shared sanitization and store only mapped user-facing errors.

## Out of Scope

- Full Docker log streaming UI.
- Percentage progress parsing from Docker output.
- Remote build service for runner images.
- Automatic GHCR visibility mutation through GitHub API.
- Multi-user or admin-specific runner management.

## Implementation Notes

- Prefer adding fields to existing `RunnerLanguageResponse` over creating a separate endpoint.
- Keep `RunnerImageClient` focused on Docker image operations; source policy belongs in `RunnerLanguageService` or a small helper.
- Do not use shell strings for Docker commands.
- Keep runner image build contexts minimal; do not copy language SDKs into the backend image.
- Use stable backend error codes for tests and localized/user-friendly copy later.

## Suggested Commit Slices

1. `test: runner 로컬 이미지 설치 회귀 테스트 추가` - Phase 0
2. `fix: runner 이미지 source 계약 추가` - Phase 1-3
3. `fix: runner 설치 진행 상태 저장` - Phase 4-5
4. `fix: runner 이미지 빌드 명령 추가` - Phase 6-8
5. `fix: backend runner build context 포함` - Phase 9
6. `fix: runner 설치를 source별로 처리` - Phase 10-15
7. `refactor: runner 설치 worker 분리` - Phase 16
8. `fix: runner 설치를 비동기로 실행` - Phase 17-19
9. `fix: runner 설치 진행 상태 UI 표시` - Phase 20-22
10. `chore: runner image source 설정 전파` - Phase 23-25
11. `chore: runner 이미지 공개 pull 검증 추가` - Phase 26
12. `docs: runner 설치 모드 문서화` - Phase 27
13. `test: runner 설치 E2E 회귀 검증 추가` - Phase 28-30

## References

- Brainstorm: `docs/brainstorms/2026-05-27-runner-install-image-source-brainstorm.md`
- Current failing symptom: Docker Hub `pull access denied for learnloop-runner-rust`
- Related plan: `docs/plans/2026-05-21-feat-swift-rust-runner-support-plan.md`
- Related learning: `docs/solutions/2026-05-18-ai-pattern-e2e-performance-security.md`
