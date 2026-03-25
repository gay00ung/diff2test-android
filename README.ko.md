<div align="center">
  <h1>diff2test-android</h1>
  <p><strong>코드 diff 기반 Android ViewModel 테스트 생성 CLI</strong></p>
  <p>변경된 ViewModel을 스캔하고, 테스트 계획을 만들고, local unit test 후보를 생성한 뒤, Gradle로 검증합니다.</p>
  <p>
    <a href="https://github.com/gay00ung/diff2test-android/stargazers">
      <img alt="GitHub stars" src="https://img.shields.io/github/stars/gay00ung/diff2test-android?style=flat-square">
    </a>
    <a href="https://github.com/gay00ung/diff2test-android/releases">
      <img alt="Release ZIP" src="https://img.shields.io/badge/release-d2t.zip-2563eb?style=flat-square">
    </a>
    <a href="https://github.com/gay00ung/diff2test-android/releases">
      <img alt="Homebrew" src="https://img.shields.io/badge/install-Homebrew-fbbf24?style=flat-square&logo=homebrew">
    </a>
    <img alt="상태 프리뷰" src="https://img.shields.io/badge/status-preview-f97316?style=flat-square">
    <img alt="Kotlin 1.9.25" src="https://img.shields.io/badge/kotlin-1.9.25-7f52ff?style=flat-square">
    <img alt="Java 17" src="https://img.shields.io/badge/java-17-437291?style=flat-square">
  </p>
  <p>
    <a href="./README.md">English</a>
    ·
    <a href="./README.ko.md">한국어</a>
  </p>
</div>

<p align="center">
  <img src="./docs/assets/readme-hero.png" alt="d2t workflow banner" width="960">
</p>

> 프리뷰 빌드입니다. 현재 CLI는 소스 실행, 릴리스 ZIP, Homebrew 기준으로 사용할 수 있습니다. 1.0 목표는 Android ViewModel local unit test 생성과 검증에 집중한 CLI이며, MCP 앱은 여전히 transport가 연결되지 않은 experimental 카탈로그 단계입니다.

`diff2test-android`는 코드 diff를 기반으로 Android ViewModel 테스트를 생성하는 Kotlin 기반 CLI 프로젝트입니다.

현재는 아래와 같이 이해해주시면 됩니다.

- CLI는 소스 실행 또는 릴리스 ZIP 기준으로 사용하실 수 있습니다.
- macOS에서는 Homebrew 설치 경로를 가장 권장합니다.
- MCP 앱은 아직 transport가 연결된 정식 서버가 아니라 experimental 카탈로그 스캐폴딩 단계입니다.

## 프로젝트가 하는 일

현재 프로젝트는 아래 흐름에 집중하고 있습니다.

- `git diff` 기준으로 변경된 ViewModel 파일을 찾습니다.
- 변경 메소드와 collaborator를 분석합니다.
- 시나리오 중심의 `TestPlan`을 생성합니다.
- local unit test 후보 코드를 생성합니다.
- 생성된 테스트를 Gradle로 검증합니다.

레포는 아래 세 계층으로 구성되어 있습니다.

- `modules/*`: 핵심 엔진 모듈입니다.
- `apps/cli`: 로컬 실행용 CLI 앱입니다.
- `apps/mcp-server`: MCP 카탈로그 스캐폴딩입니다.

## 1.0 방향

1.0에서는 범위를 아래처럼 좁게 유지하는 것이 맞습니다.

- diff 기반 Android ViewModel local unit test 생성과 검증용 CLI
- 사용자가 직접 관리하는 API 키와 Responses-compatible AI endpoint
- 릴리스 ZIP과 Homebrew 배포

현재 1.0 준비 항목은 [`docs/roadmap-1.0.md`](/Users/shingayeong/Desktop/projects/gayoung/diff2test-android/docs/roadmap-1.0.md)에, 최종 릴리스 게이트는 [`docs/release-gate-1.0.md`](/Users/shingayeong/Desktop/projects/gayoung/diff2test-android/docs/release-gate-1.0.md)에 정리되어 있습니다.

## 현재 지원하는 범위

- 현재 git working tree 기준 ViewModel diff 감지
- 변경된 ViewModel에 대한 시나리오 중심 테스트 계획 생성
- local unit test 후보 코드 생성
- 생성된 테스트 대상 Gradle 검증
- `auto --repair` 사용 시 import 및 coroutine test utility 오류에 대한 bounded repair 1회
- `d2t init`, `d2t doctor` 기반 사용자 설정
- Homebrew와 릴리스 ZIP 배포

## 1.0에 아직 포함하지 않는 범위

- transport가 연결된 정식 MCP 서버
- instrumented `androidTest` 자동 생성
- Compose UI test 생성
- native Anthropic `messages` transport
- end-to-end repair 루프
- primary path 기준의 비휴리스틱 Kotlin 정밀 분석

## 설치 방법

### macOS에서 Homebrew로 설치하기

macOS 사용자는 Homebrew 설치 방식을 가장 권장합니다.

tap 등록 없이 바로 설치하려면 아래 명령을 사용해주세요.

```bash
brew install gay00ung/diff2test-android/d2t
```

tap을 먼저 등록하고 짧은 이름으로 설치하려면 아래 명령을 사용해주세요.

```bash
brew tap gay00ung/diff2test-android
brew install d2t
```

### 릴리스 ZIP으로 실행하기

최신 릴리스의 `d2t.zip`을 내려받은 뒤 아래처럼 실행해주세요.

```bash
unzip d2t.zip
cd d2t
./bin/d2t help
```

### 소스에서 바로 실행하기

```bash
git clone https://github.com/gay00ung/diff2test-android.git
cd diff2test-android
./gradlew test
./d2t help
```

## 빠른 시작

```bash
d2t init
d2t doctor
d2t auto --ai
d2t verify
```

소스에서 직접 실행하는 경우에는 `d2t` 대신 `./d2t`를 사용해주세요.

`auto`는 이제 기본으로 생성 후 검증까지 수행합니다. import나 coroutine test utility 관련 공통 오류에 대해 1회 bounded repair를 시도하려면 `--repair`를 사용해주세요.
생성된 결과물은 기본 내장 quality gate도 통과해야 하며, placeholder assertion이나 남아 있는 `TODO()`가 있으면 실패로 처리합니다.

현재 analyzer가 symbol resolution 없이 PSI-backed declaration parsing만 사용하는 경우, 관련 명령은 그 경고를 CLI에 그대로 출력합니다.

## AI 설정

CLI는 아래 사용자 설정 파일을 읽습니다.

```bash
~/.config/d2t/config.toml
```

초기 템플릿을 생성하려면 아래 명령을 실행해주세요.

```bash
d2t init
```

현재 설정 상태를 확인하려면 아래 명령을 실행해주세요.

```bash
d2t doctor
```

설정 파일에는 실제 비밀키를 넣지 말고, 비밀키가 들어 있는 환경변수 이름만 넣어주세요.

OpenAI Responses API 예시는 아래와 같습니다.

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

로컬 또는 self-hosted Responses-compatible gateway 예시는 아래와 같습니다.

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

환경변수를 로드한 뒤에는 아래처럼 실행해주세요.

```bash
source ~/.zshrc
d2t auto --ai
```

## 명령어

```bash
d2t init [--force]
d2t doctor
d2t scan
d2t plan path/to/SomeViewModel.kt
d2t generate path/to/SomeViewModel.kt --write [--ai|--no-ai] [--strict-ai]
d2t auto [--ai|--no-ai] [--strict-ai] [--model model-name] [--no-verify] [--repair]
d2t verify :module:testTask
```

명시적인 Gradle task 없이 `verify`를 실행하면, 현재 변경된 ViewModel에 대해 생성된 테스트 파일을 기본 검증 대상으로 사용합니다.

## Homebrew 배포 경로

이 레포에는 Homebrew 배포를 위한 기본 준비가 들어 있습니다.

- Gradle 배포 task: `./gradlew :apps:cli:distZip`
- Homebrew formula 템플릿: [packaging/homebrew/d2t.rb](/Users/shingayeong/Desktop/projects/gayoung/diff2test-android/packaging/homebrew/d2t.rb)
- 배포 가이드: [docs/homebrew-release.md](/Users/shingayeong/Desktop/projects/gayoung/diff2test-android/docs/homebrew-release.md)
- `main`용 자동 태그 workflow: [`.github/workflows/tag-release.yml`](/Users/shingayeong/Desktop/projects/gayoung/diff2test-android/.github/workflows/tag-release.yml)
- 태그 기반 릴리스 자동화 workflow: [`.github/workflows/release.yml`](/Users/shingayeong/Desktop/projects/gayoung/diff2test-android/.github/workflows/release.yml)

`distZip`은 실행 가능한 CLI 묶음을 ZIP으로 만드는 Gradle task입니다.

생성되는 ZIP에는 아래 항목이 포함됩니다.

- `d2t` 실행 스크립트
- 컴파일된 jar
- 런타임 의존성

출력물은 아래 경로에 생성됩니다.

```bash
apps/cli/build/distributions/d2t.zip
```

## 현재 제한 사항

현재는 아래 항목이 아직 제한되거나 미구현 상태입니다.

- 1.0 수준의 Kotlin PSI 또는 symbol resolution 기반 정밀 분석
- Responses-compatible 외의 AI transport
- native Anthropic `messages` transport
- repair는 아직 bounded import 및 coroutine utility 보정 수준만 지원
- transport가 연결된 정식 MCP 서버

또한 `--ai`를 명시적으로 사용한 경우에는 AI 생성 실패를 heuristic fallback으로 숨기지 않고 실패로 처리합니다. fallback 동작이 필요하면 `--ai` 없이 `auto` 경로를 사용해주세요.

즉 현재는 안정화된 제품 릴리스라기보다 개발자용 preview에 가깝습니다.

## 레거시 환경변수 fallback

config 파일이 없으면 CLI는 여전히 환경변수 기반 설정으로 fallback 합니다.

- Auth: `D2T_AI_AUTH_TOKEN`, `LLM_API_KEY`, `ANTHROPIC_AUTH_TOKEN`, `OPENAI_API_KEY`
- Model: `D2T_AI_MODEL`, `STRIX_LLM`, `ANTHROPIC_MODEL`, `OPENAI_MODEL`
- Base URL: `D2T_AI_BASE_URL`, `LLM_API_BASE`, `ANTHROPIC_BASE_URL`, `OPENAI_BASE_URL`
- Reasoning: `D2T_REASONING_EFFORT`, `STRIX_RESONING_EFFORT`, `OPENAI_REASONING_EFFORT`
- Connect timeout: `D2T_CONNECT_TIMEOUT_SECONDS`, `LLM_CONNECT_TIMEOUT_SECONDS`, `OPENAI_CONNECT_TIMEOUT_SECONDS`
- Request timeout: `D2T_REQUEST_TIMEOUT_SECONDS`, `LLM_REQUEST_TIMEOUT_SECONDS`, `OPENAI_REQUEST_TIMEOUT_SECONDS`

## 레포 구조

- `apps/cli`: 로컬 실행용 CLI 앱입니다.
- `apps/mcp-server`: MCP 카탈로그 스캐폴딩입니다.
- `modules/*`: 핵심 엔진 모듈입니다.
- `prompts/*`: 프롬프트 및 정책 템플릿입니다.
- `docs/*`: 아키텍처 및 배포 문서입니다.
- `fixtures/*`: 샘플 앱과 검증용 fixture입니다.
