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
1-chore-github-template
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
    │   │   │   ├── management                      // 세션 메인 : 목록/상세 종합, 편집, 공개여
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

## 현재 구현 범위

이번 기능 브랜치에서는 기록 관리 MVP API를 구현합니다.

| Method | Path | 설명 |
| --- | --- | --- |
| GET | `/sessions` | 세션 목록 조회 |
| GET | `/sessions/{id}` | 세션 상세 조회. 요약 본문과 출처를 함께 반환 |
| PATCH | `/sessions/{id}` | 세션 편집. `title`, `editedMarkdown`, `isPublic`, `isFavorite`, `tags` 수정 |
| DELETE | `/sessions/{id}` | 세션 삭제. 실제 삭제가 아니라 soft delete 처리 |
| GET | `/tag-groups` | 해시태그 그룹 목록 조회. 그룹별 매칭 세션 포함 |
| POST | `/tag-groups` | 새 해시태그 그룹 생성 |
| PATCH | `/tag-groups/{id}` | 그룹 이름과 해시태그 수정 |
| DELETE | `/tag-groups/{id}` | 그룹 삭제 |
| PATCH | `/tag-groups/order` | 그룹 표시 순서 변경 |

이번 범위에서 제외한 항목은 다음과 같습니다.

- 마인드맵 뷰
- 회원 관련 기능
- 어드민 관련 기능
- 즐겨찾기 전용 세션 목록 뷰

회원 기능이 아직 제외 범위이므로 현재 API는 임시로 `X-User-Id` 헤더를 사용합니다. 헤더가 없으면 기본값 `1`로 처리합니다.

## 구현 도메인

### BrowsingSession

`sessions` 테이블에 매핑되는 기록 세션 엔티티입니다. `tags`, `sources`는 JSON 컬럼으로 저장하고, 삭제는 `deleted_at`을 채우는 soft delete 방식입니다.

### Summary

`summaries` 테이블에 매핑되는 세션 요약 엔티티입니다. `session_id`를 기본키이자 외래키로 사용하며, 상세 조회에서 세션과 함께 반환합니다.

### TagGroup

`tag_groups` 테이블에 매핑되는 해시태그 그룹 엔티티입니다. `hashtags`와 세션 `tags`를 비교해 그룹별 매칭 세션을 구성합니다.

## 해시태그 검증 규칙

- 최대 10개
- 한글, 영문, 숫자만 허용
- 각 태그는 최대 10자
- 공백/특수문자 불가
- 대소문자 무시 중복 불가

## 실행 및 테스트

```bash
./gradlew bootRun
./gradlew test
```

Windows PowerShell:

```powershell
.\gradlew.bat bootRun
.\gradlew.bat test
```

## PR 준비 메모

PR은 위 Git Convention에 맞춰 이슈 번호 기반 브랜치에서 생성합니다. 현재 브랜치명은 `2-feature-session-taggroup-api` 형식으로 정리했으며, 실제 PR 전 GitHub Issue 번호가 다르면 브랜치 번호를 해당 이슈 번호로 변경해야 합니다.
