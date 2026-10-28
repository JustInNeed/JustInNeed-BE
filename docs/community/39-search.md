# Community search (COM-5)

Depends on #38. Authenticated routes:

- GET /community/search/profiles?hashtag=Java&page=0&size=10
- GET /community/search/mindmaps?hashtag=Java&page=0&size=10

Search uses an exact case-insensitive hashtag after trimming whitespace, without
a leading #. The existing English/Korean/digit 1..10 character validator applies.
Unknown valid tags return empty results. page >= 0, size 1..100; defaults 0/10.
Results are ordered by owner ID and deduplicated per owner. hasNext is calculated
after privacy filtering, not from raw database row counts.

Profiles can match a publicly visible interest alone. Mindmaps require an actual
public leaf attached to the matching tag. Hidden tags, sessions containing hidden
tags, private sessions and soft-deleted sessions never contribute to matches or
counts. Requests by the owner do not bypass discovery visibility.

Because session hashtags are JSON and the graph index is populated lazily, this
initial implementation scans candidate owner IDs in keyset batches of 100 and
stops after the requested page plus one result. Each graph is read in its own
transaction; the scan does not retain locks across owners. This avoids database-
specific JSON SQL but is not a production-scale search index. Before large-scale
deployment, add an indexed public-tag projection updated with session mutations
and benchmark it. No arbitrary scan cap silently drops valid matches.

CommunitySearchTest verifies paging, case handling, duplicate owners, private and
deleted sources, hidden mixed-tag sessions, interest-only profiles and validation.
