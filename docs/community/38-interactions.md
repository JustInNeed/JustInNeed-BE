# Profile interactions and bookmarks (COM-4)

Depends on #37. All routes require JWT. Paths follow the supplied API design.

| Method | Path | Body |
| --- | --- | --- |
| PATCH | /community/profiles/{userId}/like | liked: boolean |
| PATCH | /community/exploring | targetType: NODE or HASHTAG, targetId, exploring: boolean |
| GET | /community/exploring | targetType and targetId query parameters |
| POST | /community/bookmarks | targetType, targetIds, saveType, sourceMindmapId |
| DELETE | /community/bookmarks/{bookmarkId} | none |

Like/exploring PATCH sets the requested state, not a blind toggle. Responses
include current count and state. Self-likes are rejected. A previously visible
target can be un-explored after becoming private without exposing its count.

Bookmarks accept 1..100 distinct positive IDs. saveType is HASHTAG_BUNDLE or
MINI_MINDMAP. The latter requires sourceMindmapId (profile owner ID) and targets
from that one graph. Bundle selections may span profiles. All targets are
revalidated against the current public graph before any writes. Duplicate
selections return the existing bookmark; source visibility/content changes do not
silently overwrite a saved snapshot. The server, not the client, builds snapshots.
Mini snapshots preserve selected edges and leaves. Bundles store hashtag roots.
Only the saving user can list/delete snapshots; original content is untouched.

Member row locks are taken in ascending ID order before resolving graphs. Database
unique constraints are a second guard against duplicate likes, exploring state
and bookmarks. Repeated state requests do not increment counts.

Activity is recorded on new exploration/bookmark actions using an injected UTC
Clock and Asia/Seoul calendar dates. One actor/target/tag/kind/day event is stored;
remove/re-add does not inflate that event. Undo does not erase historical activity.
The recommendation feature (#40) further deduplicates actor/tag/kind/day and
revalidates current public visibility before publishing trends.

Verification: InteractionTest covers JWT, ownership, idempotent counts/activity,
validation, snapshot independence, relations, revoked/deleted sources, and removal.
H2 integration coverage does not substitute for PostgreSQL load/deadlock testing.
