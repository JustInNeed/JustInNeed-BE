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
| POST | `/auth/extension-code` | 로그인 필요. 확장에 넘길 일회용 코드 `{code, expiresInSeconds}` 발급. 확장이 `POST /auth/token` 으로 바꾸면 웹과 별도의 토큰을 받는다. 묶음 없는 토큰 · 로그아웃된 묶음은 401 |
| POST | `/auth/refresh` | refresh 토큰으로 access/refresh 재발급 (회전). 이미 쓴 토큰 · 없는 토큰은 401, 같은 토큰으로 동시에 보내면 한 요청만 성공하고 나머지는 401 |
| POST | `/auth/logout` | 같은 묶음(웹 + 연결한 확장)의 refresh 토큰 폐기 + 보낸 access 토큰 즉시 무효화 (무효화 목록은 DB 저장이라 재시작 후에도 유지) |
| GET | `/members/me` | 내 정보 조회. 탈퇴한 회원의 토큰은 401 |
| PATCH | `/members/me/nickname` | 닉네임 설정/변경. 영문 시작 3~8자(영문·숫자), 대소문자 무시 중복 불가 (중복이면 409, 같은 닉네임을 동시에 저장해도 한 명만 성공) |
| GET | `/members/nickname/available?nickname=` | 닉네임 중복 확인. `{nickname, available}` 반환. 형식 규칙은 PATCH 와 같음(위반 시 400), 본인 닉네임은 사용 가능 |

로그인 흐름: FE `/oauth2/authorization/{kakao|naver}` 진입 → 소셜 로그인 → BE 콜백 → FE `/oauth/callback?code=..` → FE 가 `POST /auth/token` 으로 토큰 교환 → `needsNickname=true` 면 닉네임 입력 화면.
탈퇴(`DELETE /settings/account`)하면 회원 행의 소셜 식별자 · 닉네임 · 이메일을 비우므로, 같은 소셜 계정으로 다시 로그인하면 새 회원으로 가입되고 예전 닉네임도 다시 쓸 수 있습니다. 탈퇴한 회원은 커뮤니티(프로필 · 검색 · 추천 · 북마크 해시태그)에서도 보이지 않습니다. 그 회원이 남긴 좋아요 · 나도 탐색중 · 활동 행은 지우지 않고 읽을 때 숫자에서만 뺍니다. 탈퇴하면 다른 기기에 남은 access 토큰도 모든 보호 API 에서 401 입니다.

### 세션 / 해시태그 그룹

| Method | Path | 설명 |
| --- | --- | --- |
| GET | `/sessions` | 세션 목록 조회 (무한 스크롤). `page`(기본 0) · `size`(기본 10, 1~50) · `favorite`(기본 false, true 면 즐겨찾기만). `{ sessions, hasNext, totalCount, favoriteCount }` 반환 — `totalCount` · `favoriteCount` 는 필터 · 페이지와 상관없는 내 전체 세션 수 / 즐겨찾기 수. 음수 page · 범위 밖 size 는 400. 정렬: 진행 중(완료 · 실패가 아닌) 세션이 맨 위(시작 시각 최신순), 나머지는 종료 시각 최신순(종료 시각 없으면 뒤) → 시작 시각 최신순 → id 역순 |
| GET | `/sessions/hashtags` | 그룹 만들기 해시태그 후보. 내 세션 전체(삭제 제외, 모든 상태)의 태그를 목록과 같은 순서로 훑어 처음 나온 순서대로, 대소문자 무시 중복 제거(처음 나온 표기 유지). `data` 는 문자열 배열, 세션이 없으면 `[]` |
| GET | `/sessions/{id}` | 세션 상세 조회. 요약 본문과 출처를 함께 반환. 없거나 삭제한 세션은 404. 소유자는 어떤 상태든 전체(`isOwner: true`). 다른 사용자는 공개 세션(공개 · 분석 완료 · 작성자 탈퇴 전)만 읽기 전용으로 보고(`isOwner: false`, `isFavorite: false`, 작성자가 비공개로 둔 해시태그는 `tags` 에서 빠진다), 작성자의 노드 공개 범위가 `URL_ONLY` 면 제목 · 본문 · 출처 내용 없이 링크만 받는다. 그 밖에는 403 |
| GET | `/sessions/{id}/mindmap` | 세션 마인드맵. 접근 규칙은 상세와 같다. `{ nodes: [{ id, type(root/topic/unit), keyword, score, childCount, citation }], edges: [{ from, to, type(hierarchy/related), primary }] }`. `citation`(`title`, `url`, `excerpt`, `visitedAt`)은 컷오프 유닛 노드에만 온다. 분석이 끝나지 않았거나 결과가 없으면 빈 그래프, `URL_ONLY` 작성자의 세션은 다른 사용자에게 빈 그래프 |
| PATCH | `/sessions/{id}` | 세션 편집. `title`, `editedMarkdown`, `isPublic`, `isFavorite`, `tags` 중 보낸 필드만 수정. 분석이 끝나기 전에는 `isFavorite` 만 바꿀 수 있고 나머지는 409. 소유자가 아니면 404 |
| POST | `/sessions/{id}/reanalyze` | 실패한 세션 다시 분석. 소유자만(아니면 404), `FAILED` 가 아니면 409, 원본이 없으면 404. `READY` 상태의 세션 상세를 반환하고 AI 요청은 커밋 뒤 비동기로 보낸다 |
| DELETE | `/sessions/{id}` | 세션 삭제. 실제 삭제가 아니라 soft delete 처리. 어떤 상태에서도 가능 |
| POST | `/sessions/chunks` | 확장 프로그램이 세션 원본 청크를 보냄. `sessionId`(수집기 세션 id) · `segId` · `n` · `payload`, 마지막 청크는 `final` · `maxN` · `stopReason`. 처음 받으면 201, 이미 받은 청크면 저장하지 않고 200(`duplicate: true`) |
| GET | `/tag-groups` | 해시태그 그룹 목록 조회. 그룹별 매칭 세션 포함 (세션 순서는 `GET /sessions` 와 같음, 생성 · 수정 · 순서 변경 응답도 동일) |
| POST | `/tag-groups` | 새 해시태그 그룹 생성. `hashtags` 는 1개 이상 필수(비었거나 없으면 400). `position` 생략 시 맨 뒤, 지정 시 해당 위치에 끼워 넣음 |
| PATCH | `/tag-groups/{id}` | 그룹 이름과 해시태그 수정. 보낸 필드만 수정, 이름을 보낼 때 빈 값 · 공백만은 400, `hashtags` 를 빈 배열로 보내면 400 |
| DELETE | `/tag-groups/{id}` | 그룹 삭제 |
| PATCH | `/tag-groups/order` | 그룹 표시 순서 변경 |
| GET | `/home` | 홈 대시보드 한 번에 조회. 내 완료 · 미삭제 세션 기준이고 날짜는 시작 시각(KST), 이번 주는 오늘 포함 최근 7일 · 지난주는 그 앞 7일. `{ graph, stats, weekActivity, topTags, todayTimeline, weekSessions, sessions }` 반환 — `graph` 는 세션별 마인드맵을 키워드(공백 · 대소문자 무시)로 합친 그래프(`nodes` · `edges`, 중심 노드 `core` 포함 최대 150개)와 `nodeMeta` · `tagGroups` · `filterTags`, `sessions` 는 시작 시각 최신순 최대 300개. 세션이 없으면 0 · 빈 목록 |

### 설정

| Method | Path | 설명 |
| --- | --- | --- |
| GET | `/settings/collection/excluded-urls` | 수집 제외 URL 전체 목록 (페이징 없음). 체크된 항목 먼저, 그 안에서 도메인 · URL 순. 처음 조회 때 기본 URL 5개를 채움 |
| POST | `/settings/collection/excluded-urls` | URL 추가. 프로토콜 · `www.` 를 뗀 표시형(`tistory.com/feed`)으로 저장, 같은 표시형이면 409. 기본 URL 포함 100개까지 |
| PATCH | `/settings/collection/excluded-urls/selections` | 체크 변경 `{excludedUrlIds, isExcluded}` |
| DELETE | `/settings/collection/excluded-urls` | 선택 삭제 `{excludedUrlIds}`. 기본 URL 은 지우지 않고 체크만 해제 |
| GET | `/settings/extension` | 확장 프로그램 설정 조회. 저장한 적이 없으면 기본값(모두 켜짐) |
| PATCH | `/settings/extension/{popup-visible\|auto-end-notification\|manual-scrap}` | 토글 저장 `{enabled}`. 자동 종료 알림은 5초 알림만 끄고 자동 종료는 항상 동작 |

### 분석 / 추천

대상은 내 완료 · 미삭제 세션, 기간은 오늘 포함 최근 7일(세션 시작 시각 KST)입니다. 보여줄 데이터가 없으면 `data` 는 `null` 입니다.

| Method | Path | 설명 |
| --- | --- | --- |
| GET | `/analytics/week-sessions` | 이번 주 인사이트 후보. 세션 목록과 같은 항목 `{sessions}`, 최신순. 없으면 빈 목록 |
| GET | `/analytics/exploration` | 탐색 성향 `{avgUrlsPerSession, avgDwellSec, avgSessionMinutes, type, note}`. 세션별로 판정해 가장 많이 나온 성향 |
| GET | `/analytics/reading-behavior` | 읽기 행동 분포 `{shares, note}`. 유닛 수 기준, Idle 포함, 합 100 |
| GET | `/analytics/categories` | 카테고리별 방문 비율 `{categories: [{name, pct, count}], note}`. 재방문 포함, 많은 순 |
| GET | `/analytics/time-patterns` | 시간대별 패턴 `{surfing, focus, note}`. 요일 7(월~일) × 3시간 구간 8, 값 0~1 |
| GET | `/analytics/recommendations` | 추천 행동 `{recommendations: [{title, body}]}`. 조건에 맞는 것이 없으면 빈 목록 |

판정 기준값은 `AnalyticsRules` 한 곳에 있습니다. `note` 의 `**강조**` 는 FE 가 강조 글씨로 보여줍니다.

인증이 필요한 API(`/sessions/**`, `/tag-groups/**`, `/home`, `/analytics/**`, `/members/**`, `/settings/**`)는 `Authorization: Bearer {accessToken}` 헤더가 필수입니다. 토큰이 없거나 · 잘못됐거나 · 만료 · 무효화됐거나 · refresh 토큰이거나 · 탈퇴한 회원의 토큰이면 401 과 함께 `{"success":false,"data":null,"message":"로그인이 만료되었습니다. 다시 로그인해 주세요."}` 를 반환합니다 (`/auth/refresh` 실패는 "유효하지 않은 리프레시 토큰입니다."). 사용자 식별은 access 토큰의 memberId 로 합니다.

모든 응답은 `{ success, data, message }` 형식입니다. 에러도 같은 형식으로 내려가며 `success=false`, `message`에 사유가 담깁니다.

아직 없는 기능은 다음과 같습니다.

- 세션 생성(`POST /sessions`) 및 행동 로그 수집·분석·LLM 요약 파이프라인
- 마인드맵 뷰
- 어드민 관련 기능

현재 세션 및 커뮤니티 API는 `Authorization: Bearer <access-token>` 인증을 사용합니다. 사용자 ID는 JWT에서 가져오며, `X-User-Id` 헤더나 기본 사용자 ID를 사용하지 않습니다.

## 커뮤니티 백엔드

이슈 #35~#40의 프로필 편집, 비공개 저장 목록, 공개 마인드맵 조회, 인터랙션, 검색, 추천 기능을 구현했습니다.
API, 데이터 모델, 공개 범위 처리, 테스트 및 PR 병합 순서는 [커뮤니티 개발 문서](docs/community/README.md)를 참고하세요.

커뮤니티 GET 은 읽기만 합니다 (쓰기 · 행 잠금 없음). 공개 세션 1개 = 카드 1장이고, `community_cards` · `community_nodes` · `community_hashtags` 는 세션이 바뀌는 쓰기 트랜잭션에서 `CommunityCardSync` 가 맞춥니다 (세션 수정 · 삭제, 공유 핀 · 해제, AI 결과 저장, 재분석). 앱 시작 때 `CommunityCardBackfill` 이 기존 공개 세션의 빠진 행을 채웁니다. 공개 범위 · 비공개 해시태그는 저장하지 않고 읽을 때 적용합니다: 공개 그래프는 `CONTENT` 면 AI 마인드맵(hierarchy), `URL_ONLY` 면 태그 → URL 이고, 비공개 해시태그가 섞인 공개 세션은 그 태그만 빠진 채 보입니다.

### 팔로우 / 비슷한 사람

| Method | Path | 설명 |
| --- | --- | --- |
| PATCH | `/community/profiles/{userId}/follow` | 팔로우 설정 `{following}` → `{following}`. 같은 값을 다시 보내거나 동시에 보내도 200. 자기 자신은 400, 없거나 탈퇴한 회원은 404. 탈퇴해도 팔로우 행은 남고 읽을 때 뺀다 |
| GET | `/community/recommendations/profiles/similar` | 관심사가 비슷한 사람 `{people: [{userId, nickname, matchedHashtags, following}]}`. `limit` 기본 3, 1~20(밖이면 400). 태그 = 공개 · 완료 · 미삭제 세션의 태그 + 관심 해시태그에서 각자의 비공개 해시태그를 뺀 것(대소문자 무시). 겹치는 태그 수가 많은 순 → 최근 공개 활동(세션 종료 시각, 없으면 시작 시각) 순, 활동이 없으면 뒤 → id 순. 나 · 탈퇴 회원 · 겹치는 태그가 없는 사람은 빠진다. 닉네임을 정하지 않은 사람은 `nickname: null` 로 온다. 조회는 쓰기 · 잠금을 하지 않는다 |

### 카드 반응

공개 세션 1개 = 카드 1장, `{mindmapId}` = 세션 id. 공개 카드가 아니면(비공개 · 미완료 · 삭제 · 탈퇴한 작성자) 켜기 · 끄기 모두 404. 같은 값을 다시 보내거나 동시에 보내도 200 이고 두 번 세지 않습니다. 카드가 공개에서 빠져도 행은 남아 다시 공개하면 숫자가 돌아옵니다. 숫자에서 탈퇴 회원 행동은 빠지고 내 행동은 포함됩니다.

| Method | Path | 설명 |
| --- | --- | --- |
| PATCH | `/community/mindmaps/{mindmapId}/like` | 좋아요 `{liked}` → `{likeCount, likedByMe}`. 내 카드는 400 |
| PATCH | `/community/mindmaps/{mindmapId}/exploring` | 나도 탐색중 `{exploring}` → `{exploringCount, exploringByMe}`. 새로 켜면 공개 해시태그마다 활동을 남깁니다 |
| PATCH | `/community/mindmaps/{mindmapId}/bookmark` | 북마크 `{bookmarked}` → `{bookmarkCount, bookmarkId}`. 켜면 카드의 노드를 미니 마인드맵으로 저장(이미 있으면 그 id), 끄면 삭제. 노드가 없는 카드는 400 |

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
