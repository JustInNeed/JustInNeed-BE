# Recommendations (COM-6)

Depends on #39. All routes require JWT:

| GET path | Query defaults |
| --- | --- |
| /community/recommendations/mindmaps/interests | limit=3 |
| /community/recommendations/mindmaps/recent-bookmarks | limit=3 |
| /community/recommendations/hashtags/trending | period=today, limit=10 |

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

`GET /community/recommendations/hashtags/trending?period=today|week` (period ignores
case, anything else is 400) returns `{period, hashtags: [{rank, name, count, trend}]}`.
`period` in the response is `TODAY` or `WEEK`.

All windows are Asia/Seoul calendar windows, from their start through the injected
Clock's current instant. Future events are ignored.

| period | Window | Previous window |
| --- | --- | --- |
| today | today 00:00 to now | all of yesterday |
| week | this calendar week (Monday 00:00) to now | the 7 days before that Monday |

The current window is partial and the previous one is complete, so trend leans
negative early in a day or week.

`count` is explore + bookmark activity in the window. `trend` is count minus the
count of the previous window under the same rules, so a hashtag absent from that
window has trend = count. Ranking uses count descending, then normalized name;
hashtags with no activity in the current window are not listed.

Historical state changes are counted, not current checkbox totals: undo does not
remove history. A user contributes at most one explore and one bookmark per
normalized tag per Asia/Seoul day, regardless of repeated toggles, multiple nodes or
different owners, and weekly values are sums of those daily contributions. The
caller's own actions count. Actions of withdrawn members are left out at read time;
their rows stay. The lowest visible hashtag ID names a tag shared across owners.

Only tags public right now contribute, in both windows. A hashtag target needs the
tag on one of the owner's public sessions or in their interests. A node target needs
the node's session to be public and to still carry that tag. A hashtag the owner made
private leaves on its own; the other tags of that session stay. The source list is
not checked, so a URL removed from a session does not remove its node's activity.

Activity recording starts with #38; existing legacy actions are not fabricated or
backfilled. The reader is three read-only queries over the two windows, without
locks or per-target graph reads, and uses the `occurred_at` index. On Postgres with
50k rows in the windows, a week request takes about 130 ms; 450k older rows do not
change that materially and need no further index.

RecommendationTest uses a fixed Clock to cover personalized ownership, empty basis,
related URLs, authentication and validation. TrendingTest moves the Clock to times
where the Korean and UTC dates differ and covers the windows, trend, deduplication,
withdrawn actors and visibility.
