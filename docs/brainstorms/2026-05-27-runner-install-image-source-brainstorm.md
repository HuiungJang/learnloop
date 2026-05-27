---
date: 2026-05-27
topic: runner-install-image-source
---

# Runner Install Image Source

## What We're Building

Runners 페이지의 Install 버튼이 사용자의 설치 형태에 맞는 runner image 획득 방식을 선택하도록 개선한다. 현재 Install은 항상 `docker pull <imageRef>`를 실행한다. `learnloop-runner-rust:latest`처럼 registry가 없는 로컬 image name에서는 Docker가 Docker Hub를 조회하고, 공개 repository가 없기 때문에 `pull access denied`로 실패한다.

목표는 Install을 “무조건 remote pull”이 아니라 “현재 설치 형태에서 runner를 사용할 수 있게 만드는 동작”으로 재정의하는 것이다. 소스 설치, 온라인 릴리스, 오프라인 릴리스가 서로 다른 image source를 갖기 때문에 이 차이를 제품과 테스트에 명시해야 한다.

## Evidence

- Runners UI의 Rust imageRef가 `learnloop-runner-rust:latest`로 표시된다.
- backend `RunnerLanguageService.install()`은 image 존재 여부나 image source를 보지 않고 `imageClient.pull(descriptor.imageRef)`를 호출한다.
- `ProcessRunnerImageClient.pull()`은 `docker pull learnloop-runner-rust:latest`를 실행한다.
- 로컬에 `learnloop-runner-rust:latest` image가 있어도 `docker pull learnloop-runner-rust:latest`는 Docker Hub를 조회하며 같은 `pull access denied` 오류로 실패한다.
- `ghcr.io/huiungjang/learnloop/learnloop-runner-rust:latest`도 현재 anonymous pull이 `denied`로 실패한다. 온라인 릴리스는 registry 설정뿐 아니라 공개 pull 가능성도 검증해야 한다.

## Why This Approach

권장 접근은 runner catalog에 image source 개념을 추가하고, Install 동작을 source별로 분기하는 것이다. 단순히 기본 registry를 GHCR로 바꾸면 소스 설치 환경에서는 불필요한 네트워크 의존이 생기고, GHCR package가 비공개이면 같은 문제가 재발한다.

Install은 먼저 image inspect를 수행해야 한다. 이미 local image가 있으면 pull 없이 installed로 처리한다. registry image라면 pull을 실행하되 실패 메시지를 제품 친화적으로 매핑한다. source checkout에서 registry 없는 local image를 설치하려는 경우에는 `runner/<language>` Dockerfile을 build하거나, build가 불가능하면 “local runner image를 build해야 한다”는 명확한 remediation을 반환한다.

## Key Decisions

- Install semantics: Install은 “runner 사용 가능 상태 만들기”이며, 항상 remote pull이 아니다.
- Image source policy: 각 runner descriptor는 `local`, `registry`, `bundled` 중 하나의 source를 가져야 한다.
- Local image behavior: registry 없는 imageRef는 pull하지 않는다. 먼저 inspect하고, 없으면 local build 가능 여부를 확인한다.
- Online release behavior: 온라인 릴리스의 default registry image는 anonymous pull 가능한 공개 image여야 한다.
- Offline release behavior: 번들에 포함된 image tar는 install 단계에서 load하고, Runners 페이지에서는 이미 설치됨 또는 사용 가능 상태로 표시한다.
- Error UX: Docker 원문 오류를 그대로 노출하지 않고 원인과 다음 행동을 짧게 보여준다.
- Source checkout install behavior: Rust/Swift optional runner image가 없으면 Install 버튼이 local Docker build까지 자동으로 수행한다.
- Distribution default: 온라인 릴리스는 GHCR public runner package를 기본으로 제공하고, offline/full bundle은 별도 배포 옵션으로 유지한다.
- Progress UX: 장시간 build/pull은 `Checking image`, `Building local image`, `Pulling image`, `Verifying image`, `Installed` 같은 단계 기반 상태만 표시한다.

## Approaches Considered

### A. Source-aware install pipeline

Runner catalog에 image source를 명시하고 Install이 `inspect -> source별 acquire -> inspect` 순서로 실행된다. local source는 build 또는 actionable error, registry source는 pull, bundled source는 loaded image 확인으로 처리한다.

Pros: 설치 형태별 동작이 명확하고 재발 방지 테스트를 만들기 쉽다. Cons: catalog와 install service contract가 조금 커진다.

### B. Registry default only

기본 `APP_RUNNER_IMAGE_REGISTRY`를 GHCR로 채우고 Install은 계속 pull만 수행한다.

Pros: 변경 범위가 작다. Cons: source checkout과 offline bundle을 잘 설명하지 못하고, GHCR public 설정이 깨지면 같은 장애가 반복된다.

### C. Always build optional runners locally

Install 버튼이 Rust/Swift image를 항상 local Docker build로 만든다.

Pros: registry 공개 여부와 무관하다. Cons: release 사용자에게 소스 Dockerfile이 없으면 동작할 수 없고, Swift build가 크고 오래 걸린다.

Recommendation: A를 기본으로 한다. B는 online release의 하위 정책으로만 사용하고, C는 source checkout 환경의 local source 처리로 제한한다.

## Prevention Plan

- Unit test: local imageRef는 pull을 호출하지 않고 inspect 성공 시 installed가 된다.
- Unit test: local imageRef가 missing이고 build source가 없으면 `local_image_missing` 같은 안정적인 errorCode와 사용자 친화 메시지를 반환한다.
- Unit test: registry imageRef만 pull을 호출한다.
- Integration test: Runners Install API가 bare image name에서 Docker Hub pull을 시도하지 않는다.
- E2E test: Runners 페이지에서 optional Rust install 실패 시 raw Docker Hub 오류 대신 remediation message가 표시된다.
- Release CI: online release가 설정한 runner image를 unauthenticated `docker pull`로 검증한다.
- Release CI: offline bundle은 manifest의 runner tar를 load한 뒤 Runners refresh에서 installed 또는 available 상태를 확인한다.
- Packaging guard: online mode에서 registry가 비어 있으면 release package 생성 또는 startup validation을 실패시킨다.

## Open Questions

- None.

## Next Steps

→ `/workflows:plan docs/brainstorms/2026-05-27-runner-install-image-source-brainstorm.md`
