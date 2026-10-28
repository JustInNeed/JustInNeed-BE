# Community Backend

API design reference: https://chatgpt.com/s/t_6abd0fc8daf481919f2f5d23c683ae1b

## Reading Order

| Issue | Scope | Implementation notes |
| --- | --- | --- |
| #35 | Profile editing, pins, interests | [35-profile-edit.md](35-profile-edit.md) |
| #36 | Private saved collections | [36-saved-profile.md](36-saved-profile.md) |
| #37 | Public profile and graph | [37-public-graph.md](37-public-graph.md) |
| #38 | Likes, exploration, bookmark commands | [38-interactions.md](38-interactions.md) |
| #39 | Hashtag search | [39-search.md](39-search.md) |
| #40 | Personalized recommendations and trends | [40-recommendations.md](40-recommendations.md) |

All community routes use the existing Bearer JWT authentication and
ApiResponse envelope. Payload fields from the design are under data.
Validation errors return 400, unauthenticated requests 401, and missing,
private or foreign-owned resources 404. Controllers accept desired boolean
states for repeatable operations. No client-provided user ID grants ownership.

The repository has sessions/sources rather than a standalone graph editor.
One user's public graph is one mindmap (mindmapId = ownerId). Stable numeric
leaf IDs index session/source URL pairs, and edges express tag membership.
Mini mindmap responses include hashtags, nodes and edges so tag:<id> endpoints
can be rendered without consulting private or changed original data.
Graph visualization, confirmation dialogs, dragging and URL navigation remain
frontend responsibilities.

## Review Order

The six requested branch names are preserved, including their existing naming
exceptions to the README prefix/hyphen convention. Commit and PR titles use
feature:, fix: or docs:. Every PR references its issue and uses the repository
template and Feature label.

Review/merge dependency order is #35 -> #36 -> #37 -> #38 -> #39 -> #40.
There is no upstream develop branch at preparation time; #35 targets main,
and subsequent PRs target the preceding feature branch to isolate each diff.
Do not merge out of order. After an ancestor is merged, retarget the next PR to
main and inspect its diff. If Squash and Merge is used, rebase the remaining
stack onto the new base so already-reviewed changes are not introduced again.

#35 consolidates the earlier community implementation from open PRs #29-34.
Those PRs are left open and unchanged; maintainers should choose the consolidated
path or the old path, not independently merge both without inspecting overlap.
The existing 39-community---search_othersprofile alias is left untouched; the
requested 39-community---search_profile branch contains the new work.

## Verification and Deployment

Run ./gradlew test (Windows: .\\gradlew.bat test).
Tests use H2 in PostgreSQL mode, dummy OAuth settings and signed test JWTs.
The integration suite covers ownership/privacy, snapshot JSON round trips,
committed concurrent requests, lock ordering, search paging and fixed-clock
Korean calendar windows. PostgreSQL load/locking and production migrations
still require environment-specific verification.

New tables: community_profiles, community_hashtags, community_nodes,
community_bookmarks, community_profile_likes, community_explorations and
community_activities. Schema creation follows this repository's existing
Hibernate ddl-auto configuration. Before deployment, review generated DDL,
back up the database and apply the project's production migration process.
No production database was accessed by this work.

Search/recommendations currently traverse candidate profiles in bounded batches;
they are not an indexed search service. Activity starts when the new interaction
endpoints are deployed. See #39/#40 notes for scaling and counting semantics.
