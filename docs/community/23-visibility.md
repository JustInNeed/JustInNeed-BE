# Community visibility (COM-1-1)

All community routes require the existing JWT access token. Writes derive the owner
from that token; a caller cannot submit a different owner ID.

| Method | Path | Body / result |
| --- | --- | --- |
| GET | /community/me/visibility | Current visibility, default URL_ONLY |
| PATCH | /community/me/visibility | {"visibility":"URL_ONLY"} or CONTENT |
| GET | /community/profiles/{userId}/nodes | Hashtag nodes from public, nondeleted sessions |

Responses use ApiResponse. Unknown members return 404; invalid values return 400.
URL_ONLY returns session IDs, hashtags and source URLs, excluding titles, summaries,
insights and source excerpts. CONTENT explicitly enables those content fields.
Owners can preview full content. Private and deleted sessions are excluded even
from this shared view; the existing /sessions API is the owner's private workspace.

The community_profiles aggregate stores visibility, hidden hashtags, ordered pin
IDs and interests. Subsequent issue branches expose the corresponding mutations.
CommunityProfiles.lock locks the member row before creating/updating a profile,
serializing first creation and list updates in the same transaction. Reads never
create database rows. JSON list order is preserved.

CommunitySharing centralizes projection and hidden-tag checks. A session carrying
any hidden hashtag is suppressed entirely for visitors, including mixed-tag
sessions, to avoid disclosing hidden content through a second tag or detail URL.

Branch #23 is the shared base of the other community PRs. Merge it first, then
retarget its dependent PRs to the integration branch. The requested existing
branch names are retained; commits use the README's feature: prefix.
The repository currently has no develop branch, so this base PR targets main.

Tests use real JWTs through the security filter, MockMvc and H2. PostgreSQL-specific
locking is not fully verified by H2; run the concurrency scenarios on PostgreSQL
before deployment.
