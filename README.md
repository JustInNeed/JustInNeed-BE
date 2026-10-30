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
    │   │   ├── auth                                // ── 인증/회원 (소셜 로그인, JWT, 닉네임) ──
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

### 인증 / 회원

| Method | Path | 설명 |
| --- | --- | --- |
| GET | `/oauth2/authorization/{kakao\|naver}` | 소셜 로그인 진입 (브라우저 리다이렉트) |
| GET | `/login/oauth2/code/{provider}` | 소셜 로그인 콜백. 성공 시 FE `/oauth/callback?code=..` 로 302 (토큰은 URL 에 싣지 않음), 실패 시 `OAUTH_FAILURE_REDIRECT_URI` 로 302 |
| POST | `/auth/token` | 콜백으로 받은 일회용 코드 `{code}` 를 `{accessToken, refreshToken, needsNickname}` 으로 교환. 코드는 60초 안에 한 번만 사용 가능, 없거나 만료 · 이미 쓴 코드는 401 |
| POST | `/auth/refresh` | refresh 토큰으로 access/refresh 재발급 (회전) |
| POST | `/auth/logout` | refresh 토큰 폐기 + access 토큰 즉시 무효화 (무효화 목록은 DB 저장이라 재시작 후에도 유지) |
| GET | `/members/me` | 내 정보 조회. 탈퇴한 회원은 404 |
| PATCH | `/members/me/nickname` | 닉네임 설정/변경. 영문 시작 3~8자(영문·숫자), 대소문자 무시 중복 불가 |
| GET | `/members/nickname/available?nickname=` | 닉네임 중복 확인. `{nickname, available}` 반환. 형식 규칙은 PATCH 와 같음(위반 시 400), 본인 닉네임은 사용 가능 |

로그인 흐름: FE `/oauth2/authorization/{kakao|naver}` 진입 → 소셜 로그인 → BE 콜백 → FE `/oauth/callback?code=..` → FE 가 `POST /auth/token` 으로 토큰 교환 → `needsNickname=true` 면 닉네임 입력 화면.
탈퇴(`DELETE /settings/account`)하면 회원 행의 소셜 식별자 · 닉네임 · 이메일을 비우므로, 같은 소셜 계정으로 다시 로그인하면 새 회원으로 가입되고 예전 닉네임도 다시 쓸 수 있습니다. 탈퇴한 회원은 커뮤니티(프로필 · 검색 · 추천 · 북마크 해시태그)에서도 보이지 않습니다.

### 세션 / 해시태그 그룹

| Method | Path | 설명 |
| --- | --- | --- |
| GET | `/sessions` | 세션 목록 조회 |
| GET | `/sessions/{id}` | 세션 상세 조회. 요약 본문과 출처를 함께 반환 |
| PATCH | `/sessions/{id}` | 세션 편집. `title`, `editedMarkdown`, `isPublic`, `isFavorite`, `tags`, `insights` 중 보낸 필드만 수정 |
| DELETE | `/sessions/{id}` | 세션 삭제. 실제 삭제가 아니라 soft delete 처리 |
| POST | `/sessions/chunks` | 확장 프로그램이 세션 원본 청크를 보냄. `sessionId`(수집기 세션 id) · `segId` · `n` · `payload`, 마지막 청크는 `final` · `maxN` · `stopReason`. 처음 받으면 201, 이미 받은 청크면 저장하지 않고 200(`duplicate: true`) |
| GET | `/tag-groups` | 해시태그 그룹 목록 조회. 그룹별 매칭 세션 포함 |
| POST | `/tag-groups` | 새 해시태그 그룹 생성. `position` 생략 시 맨 뒤, 지정 시 해당 위치에 끼워 넣음 |
| PATCH | `/tag-groups/{id}` | 그룹 이름과 해시태그 수정 |
| DELETE | `/tag-groups/{id}` | 그룹 삭제 |
| PATCH | `/tag-groups/order` | 그룹 표시 순서 변경 |

인증이 필요한 API(`/sessions/**`, `/tag-groups/**`, `/members/**`)는 `Authorization: Bearer {accessToken}` 헤더가 필수이며, 없으면 401을 반환합니다. 사용자 식별은 access 토큰의 memberId 로 합니다.

모든 응답은 `{ success, data, message }` 형식입니다. 에러도 같은 형식으로 내려가며 `success=false`, `message`에 사유가 담깁니다.

아직 없는 기능은 다음과 같습니다.

- 세션 생성(`POST /sessions`) 및 행동 로그 수집·분석·LLM 요약 파이프라인
- 마인드맵 뷰
- 어드민 관련 기능
- 즐겨찾기 전용 세션 목록 뷰

현재 세션 및 커뮤니티 API는 `Authorization: Bearer <access-token>` 인증을 사용합니다. 사용자 ID는 JWT에서 가져오며, `X-User-Id` 헤더나 기본 사용자 ID를 사용하지 않습니다.

## 커뮤니티 백엔드

이슈 #35~#40의 프로필 편집, 비공개 저장 목록, 공개 마인드맵 조회, 인터랙션, 검색, 추천 기능을 구현했습니다.
API, 데이터 모델, 공개 범위 처리, 테스트 및 PR 병합 순서는 [커뮤니티 개발 문서](docs/community/README.md)를 참고하세요.

## 환경 변수

| 변수 | 설명 |
| --- | --- |
| `KAKAO_CLIENT_ID`, `KAKAO_CLIENT_SECRET` | 카카오 개발자 콘솔. redirect URI `http://localhost:8080/login/oauth2/code/kakao` 등록 필요 |
| `NAVER_CLIENT_ID`, `NAVER_CLIENT_SECRET` | 네이버 개발자 콘솔. redirect URI `http://localhost:8080/login/oauth2/code/naver` 등록 필요 |
| `JWT_SECRET` | JWT 서명 키. 비우면 개발용 기본값 사용 (운영 금지) |
| `OAUTH_SUCCESS_REDIRECT_URI` | 로그인 성공 후 FE 콜백 (기본 `http://localhost:3000/oauth/callback`) |
| `OAUTH_FAILURE_REDIRECT_URI` | 로그인 실패 시 FE 로그인 페이지 (기본 `http://localhost:3000/login?error=oauth`) |
| `CORS_ALLOWED_ORIGINS` | 허용 origin, 쉼표 구분 (기본 `http://localhost:3000`) |
| `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` | 앱 DB 접속 정보 (기본값은 docker-compose 의 Postgres) |

## 실행 및 테스트

```bash
docker compose up -d      # PostgreSQL(5433) + pgAdmin(5051)
./gradlew bootRun         # http://localhost:8080 , Swagger: /swagger-ui.html
./gradlew test            # 테스트는 H2 인메모리(test 프로파일)로 돌아가므로 도커가 없어도 됨
```

더미 데이터가 필요하면 `db/seed-dummy.sql`을 pgAdmin 등에서 수동 실행합니다. 파일 안의 `user_id`는 본인 member id 로 바꿔서 실행합니다.

Windows PowerShell:

```powershell
.\gradlew.bat bootRun
.\gradlew.bat test
```
