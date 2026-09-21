# Hashtag visibility (COM-1-2)

Depends on #23; this PR targets 23-community---visibility until that base is merged.

| Method | Path | Body / result |
| --- | --- | --- |
| GET | /community/me/hashtags | Owned hashtags with isPublic flags |
| PATCH | /community/me/hashtags/visibility | {"hashtag":"Java","isPublic":false} |

New hashtags are visible by default, subject to the session's own public flag.
Matching ignores case. Invalid, unknown or foreign-only tags return 400.
A preference persists even if the last corresponding session is removed; the
owner can still see and restore it. Only the JWT owner's preferences are changed.

A hidden tag suppresses its nodes and every mixed-tag session containing it in
visitor projections. Shared list/detail routes use the same CommunitySharing
policy. Owners retain access through their authenticated profile and /sessions.
