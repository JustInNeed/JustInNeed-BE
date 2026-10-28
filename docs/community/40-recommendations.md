# Recommendations (COM-6)

Depends on #39. All routes require JWT:

| GET path | Query defaults |
| --- | --- |
| /community/recommendations/mindmaps/interests | limit=3 |
| /community/recommendations/mindmaps/recent-bookmarks | limit=3 |
| /community/recommendations/hashtags/trending | period=TODAY, limit=10 |

limit must be 1..100. Interests come from the caller's own profile. Recent means
the caller's last 20 still-saved bookmarks, ordered by savedAt descending then ID.
Basis hashtags are case-insensitively deduplicated in input order. No basis gives
an empty result, not unrelated fallback recommendations.

Mindmaps exclude the caller and require a matching tag with actual visible leaves.
Ranking is matched-tag count descending, then owner ID ascending. A maximum of
10 distinct public source URLs attached to matching tags is returned as relatedUrls.
This is a deterministic tag-based recommender, not an external URL/ML service.
Search's batched directory traversal is reused with only the best limit results
retained in memory. It still scans candidates; production indexing is future work.

## Trending Semantics

TODAY starts at Asia/Seoul midnight. WEEK starts Monday at 00:00 in that zone.
The window is inclusive from its start through the injected Clock's current
instant. Future events are ignored. Historical state changes are counted, not
current checkbox totals. Undo does not remove history.

A user contributes at most one explore and one bookmark per normalized tag per
calendar day, regardless of repeated toggles, multiple nodes or different owners.
Weekly values are sums of those daily contributions. Rank uses exploreCount +
bookmarkCount descending, normalized name then stable tag ID for ties. Only tags
still attached to currently public targets contribute; hiding/deleting a source
removes its contribution from public rankings. The lowest visible hashtag ID
represents a name shared across owners.

Activity recording starts with #38; existing legacy actions are not fabricated or
backfilled. The initial trend reader scans the bounded date window and caches
resolved targets per request. Large traffic requires an aggregated activity read
model and PostgreSQL workload testing.

RecommendationTest uses a fixed Clock to cover Korean midnight, Monday, future
events, deduplication, private/revoked sources, personalized ownership, empty
basis, related URLs, authentication and validation.
