# Git Convention

## 작업 방식

1. 기능 또는 이슈 단위로 GitHub Issue를 생성합니다.
2. 생성한 Issue에 대응하는 브랜치를 생성합니다.
3. 작업 완료 후 Pull Request(PR)를 생성하여 develop 브랜치에 병합합니다.
4. 최종 검증 완료 후 main 브랜치로 배포합니다.

## 브랜치 전략

프로젝트는 아래와 같은 브랜치 전략으로 관리합니다.

- main
  - 실제 배포 서버와 연결되는 브랜치
  - 검증 완료된 코드만 병합

- develop
  - 로컬 테스트 및 통합 개발 브랜치
  - 기능 개발 브랜치를 병합하여 테스트 진행

## 브랜치 네이밍 규칙

브랜치는 아래 Prefix 중 하나를 선택하여 작성합니다.

- feature : 기능 추가
- fix : 버그 수정
- refactor : 리팩토링
- chore : 설정, 빌드, 패키지 관리 등 기타 작업
- docs : 문서 수정

브랜치명은 아래 형식을 따릅니다.

```
{issue number}-{prefix}-{issue-related-content}
```

예시:

```
1-chore-github-template refactor/user-service
```

단어 구분은 -를 사용하며, 영어로 작성합니다.

## Commit Convention

Commit은 가능한 한 의미 단위로 나누어 작성합니다.  
하나의 Commit에는 하나의 목적만 포함되도록 합니다.

또한 Commit 메시지는 실제 수행한 작업 내용을 구체적으로 작성합니다.
작업 내용을 명확하게 이해할 수 있도록 작성합니다.

아래 형식을 따릅니다.

```
{prefix}: {content}
```

### 예시

```
text readme 수정 (x)
README.md에 최초 프로젝트 구조 설명 추가 (O)
JWT 인증 필터 예외 처리 로직 추가(O)
```

## 프로젝트 구조

```
└── src
    ├── main
    │   ├── java/com/justinneed
    │   │   ├── JustInNeedApplication.java        // @SpringBootApplication 진입점
    │   │   │
    │   │   ├── session                            // ── 세션 도메인 (MVP 주력) ──
    │   │   │   ├── management                      // 세션 본체: 목록/상세 종합, 편집, 공개여부
    │   │   │   │   ├── SessionController.java
    │   │   │   │   ├── SessionService.java
    │   │   │   │   ├── SessionRepository.java
    │   │   │   │   ├── Session.java                // @Entity
    │   │   │   │   └── dto
    │   │   │   │       ├── SessionListResponse.java     // 목록용 (markdown 제외, 가벼움)
    │   │   │   │       ├── SessionDetailResponse.java   // 상세용 (summary 포함)
    │   │   │   │       └── SessionUpdateRequest.java    // PATCH 바디
    │   │   │   │
    │   │   │   ├── summary                          // 세션 줄글 (+텍스트수정 뷰)
    │   │   │   ├── mindmap                          // 세션 마인드맵 (향후)
    │   │   │   └── taggroup                         // 해시태그 그룹
    │   │   │
    │   │   ├── analysis                            // ── 분석/추천 (향후) ──
    │   │   ├── community                           // ── 커뮤니티 (향후) ──
    │   │   ├── settings                            // ── 설정 (향후) ──
    │   │   ├── admin                               // ── 관리자 (향후) ──
    │   │   ├── auth                                // ── 인증 (#6 회원가입/로그인) ──
    │   │   └── global                              // ── 공통 ──
    │   │       ├── config
    │   │       ├── exception
    │   │       │   ├── GlobalExceptionHandler.java  // @RestControllerAdvice
    │   │       │   ├── ErrorCode.java               // 에러 코드 enum
    │   │       │   └── CustomException.java
    │   │       └── common
    │   │           ├── BaseEntity.java              // createdAt, updatedAt 공통 (@MappedSuperclass)
    │   │           └── ApiResponse.java             // 공통 응답 포맷 (선택)
    │   │
    │   └── resources
    │       ├── application.yml                      // 공통 설정
    │
    └── test
```
