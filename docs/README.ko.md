# LearnLoop MVP

LearnLoop는 AI로 작성한 코드 근거를 검수 가능한 학습 자산으로 바꾸는 로컬 MVP입니다. 코드와 대화 로그를 수집하고, 관련 근거를 연결한 뒤, AI provider가 재사용 가능한 구현 패턴과 연습 문제를 생성합니다. 생성된 자산은 사람이 검수한 뒤 조직 학습 라이브러리에 공개됩니다.

## 추천 사용자

- AI가 생성한 코드에서 실제 구현 패턴을 학습하고 싶은 개발자
- 반복되는 구현 패턴을 조직 내부 연습 라이브러리로 만들고 싶은 팀
- 조직 공개 전에 사람 검수를 기본값으로 유지하려는 리뷰어와 플랫폼 관리자

## 실행

시스템 Node가 깨져 있어도 스크립트가 Codex 번들 Node 런타임을 자동으로 사용합니다.

```sh
./scripts/test.sh
./scripts/dev.sh
./scripts/smoke.sh
```

기본 개발 URL:

```text
http://localhost:4173
```

## 데모 사용자

UI의 역할 선택기를 사용하거나 `POST /api/session`으로 세션을 만들 수 있습니다.

- `u-admin`
- `u-contributor`
- `u-reviewer`
- `u-learner`

기본 로컬 비밀번호는 `demo-password`입니다. 로컬이 아닌 환경에서는 `APP_DEMO_PASSWORD`를 설정하세요.

## AI Provider 설정

기본 `provider-local-mock`은 안정적인 데모와 테스트를 위한 deterministic local provider입니다. non-mock provider는 OpenAI-compatible Responses API 형식으로 `POST /v1/responses`를 호출하고, 구조화된 JSON schema 응답을 검증한 뒤 패턴 카드와 문제를 생성합니다.

provider 등록 예시:

```json
{
  "organizationId": "org-demo",
  "provider": "openai",
  "model": "gpt-4.1-mini",
  "scope": "organization",
  "credential": "YOUR_API_KEY",
  "orgApproved": true
}
```

`provider: "openai"`는 기본 `baseUrl`로 `https://api.openai.com`을 사용합니다. 커스텀 `baseUrl`은 기본적으로 HTTPS만 허용합니다. 로컬 테스트용 loopback HTTP는 `APP_ALLOW_INSECURE_PROVIDER_BASE_URL=1`일 때만 허용됩니다.

provider credential은 로컬 JSON store에 암호화되어 저장되고 API 응답에서는 redaction됩니다. 로컬 개발이 아닌 환경에서는 `APP_CREDENTIAL_ENCRYPTION_KEY`를 설정해야 하며, production mode에서는 필수입니다.

## 검증

전체 테스트:

```sh
./scripts/test.sh
```

E2E smoke:

```sh
./scripts/smoke.sh
```

smoke는 세션 생성, 증거 수집, source link, local mock 생성, 리뷰, 공개, learner submission, progress, 그리고 local fake OpenAI-compatible provider를 통한 실제 HTTP provider generation까지 검증합니다.
