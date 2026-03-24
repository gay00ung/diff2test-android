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
./gradlew :apps:cli:run --args="scan"
./gradlew :apps:cli:run --args="plan app/src/main/java/com/example/LoginViewModel.kt"
./gradlew :apps:cli:run --args="generate app/src/main/java/com/example/LoginViewModel.kt"
```

## 메모

- 현재 CLI는 엔진 경계와 데이터 계약을 검증하기 위한 stub 분석을 사용한다.
- 다음 구현 우선순위는 Kotlin AST 또는 심볼 분석으로 stub을 교체하는 것이다.
- MCP는 지금 단계에서 transport까지 연결된 서버가 아니라 카탈로그 스캐폴딩까지만 들어가 있다.

