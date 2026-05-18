<p align="center">
  <img src="assets/learnloop-logo.svg" alt="LearnLoop" width="180">
</p>

# LearnLoop

[English](README.md)

LearnLoop는 개발자가 Codex, Gemini, Claude 같은 AI 도구를 사용하며 생성한 코드 조각과 PR, commit, diff 근거를 모아 학습 가능한 개발 지식으로 바꾸는 플랫폼입니다.

## 주요 화면 및 기능

<p align="center">
  <img src="assets/learnloop-demo.gif" alt="LearnLoop demo showing signup, local AI setup, workflow run, and generated learning card" width="900">
</p>

이 데모는 회원가입, 로컬 AI 설정, evidence-to-practice workflow 실행, 생성된 학습 카드 확인까지의 핵심 흐름을 보여줍니다.

## 목적

AI로 생성한 코드는 당장의 생산성을 높이지만, 팀과 개인에게 어떤 패턴이 반복되고 무엇을 배워야 하는지 남기지 못하는 경우가 많습니다. LearnLoop는 이 흐름을 학습 루프로 바꿉니다.

- AI 사용으로 생성된 코드 조각, 대화 로그, PR, commit, diff를 수집합니다.
- 디자인 패턴, 라이브러리, 알고리즘, API 사용법, 설정 방법을 분석합니다.
- 분석 결과를 구현 문제, Q&A, 실습 카드로 변환합니다.
- 사람 검수를 거쳐 조직 안에서 재사용 가능한 학습 자산으로 축적합니다.
- 사용자의 AI API key와 OAuth 설정은 로컬 브라우저에만 저장하고 서버로 전송하지 않습니다.

## 추천 사용자

- AI 코딩 도구를 자주 사용하지만 생성된 코드를 제대로 학습 자산으로 남기고 싶은 개발자
- 팀원이 반복적으로 사용하는 라이브러리, API, 패턴을 교육 콘텐츠로 만들고 싶은 테크 리드
- 온보딩, 코드 리뷰, 사내 개발자 교육을 운영하는 플랫폼 관리자
- PR과 commit 기반으로 실제 코드 변경에서 학습 문제를 만들고 싶은 조직

## 사용 방법

### 설치형 실행

가장 쉬운 실행 방법은 Docker Compose 설치 스크립트입니다. Spring Boot API, React 앱, PostgreSQL을 빌드하고 하나의 브라우저 URL로 노출합니다.

필수 환경:

- Docker Desktop 또는 Docker Engine with Compose v2

설치 및 시작:

```sh
./scripts/install.sh
```

브라우저에서 엽니다:

```text
http://localhost:8080
```

설치 스크립트는 `.env`를 만들고 로컬 자격 증명을 생성합니다. 시작 후 출력되는 demo password를 UI에 입력하면 seeded demo role을 사용할 수 있습니다.

설치 후 자주 쓰는 명령:

```sh
./scripts/start.sh
./scripts/status.sh
./scripts/stop.sh
```

브라우저 포트를 바꾸려면 `.env`의 `AI_CODE_WEB_PORT`를 수정한 뒤 다시 시작합니다.

```sh
./scripts/start.sh
```

데이터는 `learnloop_install-postgres-data` Docker volume에 저장됩니다. `./scripts/stop.sh`는 컨테이너만 중지하고 데이터를 삭제하지 않습니다.

### 실습 워크벤치와 샌드박스 실행

실습 워크벤치는 TypeScript, Java, Kotlin 문제를 VS Code에 익숙한 편집 경험으로 풀 수 있게 합니다. 사용자는 로컬 코드 실행이 불가능한 환경에서도 실습을 탐색하고, 파일을 수정하고, 초안을 저장하고, 답안을 제출하고, 피드백과 정답 diff를 확인할 수 있습니다.

샌드박스 실행은 선택 기능이며 실패 시 안전하게 비활성화됩니다. Run 동작은 backend runtime이 Docker CLI, 접근 가능한 Docker daemon, 로컬 언어별 runner image를 사용할 수 있을 때만 동작합니다. 이 조건이 없으면 앱은 `runner_unavailable`을 표시하지만 읽기, 편집, 로컬 저장, draft sync, 제출 흐름은 유지합니다.

현재 runner 제한:

- 지원 언어: TypeScript, Java, Kotlin
- 코드 실행 중 네트워크 접근 없음
- 실행 중 package 설치 없음; 필요한 의존성은 runner image에 포함해야 함
- browser가 아니라 backend가 고정 harness command를 선택
- wall-clock timeout, CPU, memory, process 수, stdout/stderr excerpt 제한 적용

소스에서 runner를 확인하는 명령:

```sh
./scripts/runner-typescript-smoke.sh
./scripts/runner-java-smoke.sh
./scripts/runner-kotlin-smoke.sh
./scripts/status.sh
```

기본 설치형 container는 backend에 Docker 접근 권한을 mount하지 않습니다. 설치를 단순하게 유지하고 host Docker socket 노출을 기본값으로 피하기 위한 선택입니다. `./scripts/status.sh`는 runner health를 출력하므로 운영자는 현재 환경에서 코드 실행이 가능한지 확인할 수 있습니다.

### 풀이기록과 동기화

Editor state는 local-first로 동작합니다. Browser는 아직 전송하지 않은 수정사항을 로컬에 유지하고, draft와 제출 답안을 사용자별 attempt record로 서버에 동기화합니다. 패턴 카드, 실습 파일, 힌트, 정답 참조 같은 조직의 canonical asset은 사용자가 답안을 제출해도 변경되지 않습니다.

서버 동기화는 `(user, problem, clientAttemptId)` 기준으로 idempotent하게 처리됩니다. 따라서 재시도는 조직 자산에 충돌 레코드를 만들지 않고 해당 사용자의 draft/submission을 갱신합니다. Reviewer와 admin은 권한에 따라 제출 기록을 확인할 수 있지만, 로컬 AI provider credential은 사용자 브라우저에만 남고 서버로 전송되지 않습니다.

### 첫 사용자 플로우

1. 회원가입 또는 로그인합니다.
2. 첫 로그인 시 사용할 AI provider를 선택합니다.
3. Codex/Gemini는 OAuth 또는 API key 방식을 선택할 수 있고, Claude는 API key 방식을 사용합니다.
4. 로컬 AI 설정을 저장한 뒤 학습 플로우를 실행합니다.
5. 생성된 패턴 카드와 실습 문제를 검수하고 재사용 가능한 학습 자산으로 축적합니다.

## 릴리즈 번들

현재 머신 아키텍처용 배포 패키지를 만듭니다.

```sh
./scripts/package-release.sh
```

산출물은 `dist/release/`에 생성되며 다음을 포함합니다.

- runtime `docker-compose.yml`
- `install.sh`, `start.sh`, `status.sh`, `stop.sh`
- release metadata
- backend, web, PostgreSQL Docker image archive
- macOS용 `LearnLoop.app`

릴리즈 번들에서 설치:

```sh
tar -xzf dist/release/learnloop-0.1.0-*.tar.gz
cd learnloop-0.1.0-*
./install.sh
```

릴리즈 번들 설치는 소스 빌드를 하지 않습니다. 패키지에 포함된 Docker image를 로드하고 stack을 시작한 뒤 demo password를 출력합니다.

릴리즈 번들은 application과 database image를 포함합니다. 언어별 runner image는 크기가 크고 Docker 실행 권한이 있는 환경이 필요하므로 application bundle과 분리했습니다. Runner 조건이 준비되지 않은 경우에도 릴리즈 앱은 실습 탐색, 편집, 저장, 제출, 풀이기록 검토를 지원합니다.

## CI/CD

LearnLoop는 GitHub Actions로 주요 품질 게이트와 릴리즈 흐름을 실행합니다.

- `CI`는 pull request와 `main` push에서 변경 파일 검증, 테스트, 빌드, dependency check, secret scan, filesystem scan, container image scan을 실행합니다.
- `CodeQL`은 pull request, `main` push, 주간 schedule에서 Kotlin과 TypeScript 정적 분석을 실행합니다.
- `Release`는 `v0.1.0` 같은 version tag 또는 manual dispatch에서 테스트, 빌드, 보안 검사, 릴리즈 번들 패키징, GitHub Release publishing을 실행합니다.

## 개발 실행

이 저장소의 스크립트는 Codex 번들 Node runtime을 자동으로 사용합니다.

```sh
./scripts/test.sh
./scripts/dev.sh
./scripts/smoke.sh
```

기본 개발 URL:

```text
http://localhost:4173
```

Spring Boot와 React를 분리해서 실행하려면 다음 명령을 사용합니다.

```sh
./scripts/db-up.sh
./scripts/backend-dev.sh
./scripts/frontend-dev.sh
```

기본 split-stack URL:

```text
Backend API: http://localhost:8080
Frontend: http://127.0.0.1:5173
Health: http://localhost:8080/api/health
```

현재 split-stack 검증:

```sh
./scripts/check-split.sh
./scripts/npm.sh --prefix frontend audit
```

샌드박스 runner image 빌드 및 검증:

```sh
./scripts/runner-typescript-smoke.sh
./scripts/runner-java-smoke.sh
./scripts/runner-kotlin-smoke.sh
```

추후 새로운 runner 언어를 추가하려면 `runner/` 아래에 runner image를 만들고, `backend/src/main/kotlin/com/aicodelearning/runner/RunnerRegistry.kt`에 고정 harness를 등록하고, practice contract test를 확장하고, 네트워크 없이 passing/failing exercise를 모두 증명하는 smoke script를 추가합니다.

## Demo Users

UI role selector를 사용하거나 `POST /api/session`으로 session을 생성할 수 있습니다.

- `u-admin`
- `u-contributor`
- `u-reviewer`
- `u-learner`

로컬 개발 기본 password는 `demo-password`입니다. Docker installer는 `.env`에 `APP_DEMO_PASSWORD`를 생성하며, 설치형 UI는 정적 frontend bundle에 password를 넣지 않고 이 값을 입력받습니다.

## Scope

Node MVP는 parity oracle로 유지됩니다. 설치형 앱은 Kotlin/Spring Boot backend, React frontend, PostgreSQL persistence, deterministic local pattern generation을 사용합니다. Provider credential은 non-reversible reference로만 서버에 저장됩니다.

## License

GNU Affero General Public License v3.0. See [LICENSE](LICENSE) and [NOTICE](NOTICE).
