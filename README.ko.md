# diff2test-android

[![English](https://img.shields.io/badge/Language-English-1f6feb?style=for-the-badge)](./README.md)
[![한국어](https://img.shields.io/badge/언어-한국어-0f9d58?style=for-the-badge)](./README.ko.md)

`diff2test-android`는 코드 diff를 기반으로 안드로이드 테스트를 생성하기 위한 Kotlin 모노레포다.

레포는 세 계층으로 구성된다.

- 분석, 계획, 생성, 실행, 복구를 담당하는 핵심 엔진 모듈
- 로컬 실행과 워크플로 오케스트레이션을 담당하는 CLI 앱
- 같은 엔진 계약을 MCP의 tools, resources, prompts로 노출하는 MCP 앱

## 현재 범위

현재 스캐폴딩은 `src/test` 기준의 ViewModel 중심 local unit test를 우선 대상으로 하고, patch preview와 최대 2회의 복구 정책을 기본 전제로 둔다.

예정된 v1 범위:

- diff에서 ViewModel 중심 변경 감지
- 대상 API, state holder, collaborator 분석
- 시나리오 우선의 `TestPlan` 생성
- local unit test 후보 코드 생성
- 타겟 Gradle 검증 실행
- 최대 2회까지 실패 복구 허용

## 레포 구조

- `apps/cli`: 로컬 실행용 진입점
- `apps/mcp-server`: MCP 카탈로그 및 서버 측 계약
- `modules/*`: 엔진 모듈
- `prompts/*`: 프롬프트 및 정책 템플릿
- `docs/*`: 아키텍처 및 계약 문서
- `fixtures/*`: 샘플 앱과 골든 데이터

## 빠른 시작

```bash
./gradlew test
./d2t init
./d2t doctor
./d2t auto --ai
```

## AI 설정

이제 CLI는 사용자 단위 설정 파일 `~/.config/d2t/config.toml`을 지원한다.

초기 템플릿 생성:

```bash
./d2t init
```

현재 AI 설정 점검:

```bash
./d2t doctor
```

설정 파일에는 실제 비밀키를 넣지 않고, 비밀키가 들어 있는 환경변수 이름만 저장한다.

OpenAI Responses API 예시:

```toml
[ai]
enabled = true
provider = "openai"
protocol = "responses-compatible"
api_key_env = "OPENAI_API_KEY"
model = "gpt-5"
base_url = "https://api.openai.com/v1"
connect_timeout_seconds = 30
request_timeout_seconds = 180
```

로컬 또는 self-hosted Responses-compatible 게이트웨이 예시:

```toml
[ai]
enabled = true
provider = "custom"
protocol = "responses-compatible"
api_key_env = "LLM_API_KEY"
model = "qwen3-coder-next-mlx"
base_url = "http://127.0.0.1:12345"
reasoning_effort = "high"
connect_timeout_seconds = 30
request_timeout_seconds = 300
```

그다음 셸에서 키를 로드한 뒤 실행하면 된다.

```bash
source ~/.zshrc
./d2t auto --ai
```

현재 제약:

- 지금은 `responses-compatible` 엔드포인트만 지원한다.
- Anthropic의 native `messages` transport는 아직 구현하지 않았다.
- Anthropic 계열이더라도 Responses-compatible 게이트웨이를 제공하면 `provider = "custom"`으로 사용하면 된다.

## 명령어

```bash
./d2t init [--force]
./d2t doctor
./d2t scan
./d2t plan path/to/SomeViewModel.kt
./d2t generate path/to/SomeViewModel.kt --write [--ai|--no-ai]
./d2t auto [--ai|--no-ai] [--model model-name]
./d2t verify :module:testTask
```

## 레거시 환경변수 fallback

config 파일이 없으면 CLI는 여전히 환경변수로 fallback 한다.

- Auth: `D2T_AI_AUTH_TOKEN`, `LLM_API_KEY`, `ANTHROPIC_AUTH_TOKEN`, `OPENAI_API_KEY`
- Model: `D2T_AI_MODEL`, `STRIX_LLM`, `ANTHROPIC_MODEL`, `OPENAI_MODEL`
- Base URL: `D2T_AI_BASE_URL`, `LLM_API_BASE`, `ANTHROPIC_BASE_URL`, `OPENAI_BASE_URL`
- Reasoning: `D2T_REASONING_EFFORT`, `STRIX_RESONING_EFFORT`, `OPENAI_REASONING_EFFORT`

## 메모

- 현재 CLI는 git diff 기준으로 변경된 ViewModel을 잡아 테스트 파일을 자동 생성할 수 있다.
- 분석기는 아직 heuristic 기반이며, 다음 우선순위는 Kotlin AST 또는 심볼 분석으로 교체하는 것이다.
- MCP는 지금 단계에서 transport까지 연결된 서버가 아니라 카탈로그 스캐폴딩까지만 들어가 있다.
