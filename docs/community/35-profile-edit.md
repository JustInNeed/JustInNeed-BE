# Profile editing: consolidated contract

This issue consolidates COM-1-1 through COM-1-8 from the still-open PRs #29-#34.
It includes those commits; merge either the consolidated PR or the original
series, not duplicate implementations. No existing PR is closed automatically.

The reference is the user-provided shared design:
https://chatgpt.com/s/t_6abd0fc8daf481919f2f5d23c683ae1b

- PATCH /community/me/visibility accepts visibilityScope; legacy visibility is an alias.
- GET /community/me/hashtags returns stable numeric hashtagId values.
- PATCH /community/me/hashtags/{hashtagId}/visibility accepts isPublic.
- POST /community/me/pinned-sessions accepts sessionId and returns 201.
- DELETE /community/me/pinned-sessions/{sessionId} preserves the original.
- PATCH /community/me/pinned-sessions/order accepts the exact sessionIds set.
- GET /community/sessions/{sessionId} enforces pin ownership and public projection.
- POST /community/me/interests returns 201 with interestId, hashtag, interestCount.
- GET /community/me/interests returns interests with their IDs.
- DELETE /community/me/interests/{interestId} uses an owner-scoped numeric ID.

Old shared-sessions routes and string-based hashtag visibility remain supported.
Delete an interest by name with /community/me/interests/by-name/{hashtag};
nonnumeric legacy names are also accepted. Pure numeric names must use by-name
to avoid ambiguity with the new numeric IDs. Pin positions are zero-based.

Tag identity is scoped to its profile, case-insensitive and durable across
removal/reintroduction. Index creation is serialized by the existing member
lock. Private counts/content are never returned through a visitor projection.
The interest API response changed to the supplied design; update old clients
that expected POST to return a plain array.

The application uses Hibernate schema update; community_hashtags is additive.
No production database was changed by this task.
