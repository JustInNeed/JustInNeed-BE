# Analytics dashboard (#53)

Depends on #52. All six GET routes require Bearer JWT and return ApiResponse.
Optional from/to are ISO dates (inclusive), defaulting to the last seven calendar
dates in Asia/Seoul. Statistics allow at most 90 dates, never future dates.
Timeline is restricted to today and the previous six dates.

| Route | Requirements | Response |
| --- | --- | --- |
| /analytics/source-statistics | ANA-1-1..4 | Category visit counts/ratios, deep-reading time/ratios, top domain, frequency pattern, suggestions |
| /analytics/timeline | ANA-2-1..2 | Visit timestamp, URL/title, session ID and analyzed session hashtags |
| /analytics/time-patterns | ANA-2-3..5 | Average session seconds, 24 hourly buckets, busiest hour and recommended reading hour |
| /analytics/reading-behaviors | ANA-3-1..2 | Model behavior counts/active time/ratios, dominant behavior and explanation |
| /analytics/exploration-patterns | ANA-4 | Per-session unique URLs/active dwell, weekday averages, pattern and suggestion |
| /analytics/tips | ANA-5 | Random predefined tip ID/text |

Only the JWT owner's ended surfing sessions overlapping the range contribute.
Sessions spanning midnight are included; events are clipped to the selected date
interval. Session duration is the full duration of each overlapping session.
Sessions with a deleted generated record are omitted.
Ongoing collection is excluded. Failed/unclassified data remains UNKNOWN, never
fabricated as ML evidence. Legacy sessions without surfing events do not invent
visit/behavior data. Session hashtags are the analysis output associated with each
visit; they are not independently inferred for each event's timestamp.

Visits are PAGE_VIEW events, not mouse/scroll counts; repeated genuine visits with
distinct client IDs count separately. Reading ratios use activeMillis (all behaviors,
including UNKNOWN, in the denominator), not idle wall time or mouse event count.
Deep-reading category ratios use total DEEP_READING active time as denominator.
The extension must send nonoverlapping active deltas, not cumulative dwell time.
This is client-reported measurement, not browser-attested telemetry.

Category precedence: BLOG -> OFFICIAL -> COMMUNITY -> NEWS -> OTHER. Exact and
subdomain matching prevents lookalike suffixes. Blog: tistory.com, velog.io,
medium.com, naver.com. Official: docs.*, developer.*, github.com, .go.kr, .edu.
Community: reddit.com, dcinside.com, stackoverflow.com. News: bbc.com/bbc.co.uk,
reuters.com, nytimes.com, cnn.com, yna.co.kr, hani.co.kr, chosun.com, khan.co.kr.
This explicit initial news list is intentionally finite; extend DomainCategory
when product owners add publishers.

Frequency is CONCENTRATED at >=60% for the leading category, otherwise BALANCED.
Suggestions trigger for blog+community >=60%, community >=50%, or one domain >=60%.
Hour concentration is >=30% of active time in the leading hour; otherwise DISTRIBUTED.
Reading-hour recommendation maximizes (DEEP_READING+READING)/active time among
hours with >=60s evidence, then active time, then earlier hour. No evidence yields
null and zero-safe percentages. Session duration is end-start wall seconds.
Deep-reading domain is the domain with the highest deep-reading active time.

ANA-4 policyVersion RULE_V1 is a documented heuristic, not an additional ML model:
>=10 unique URLs and <30 active seconds/URL => DISTRACTED;
otherwise focused active time >=60% => FOCUSED;
otherwise >=10 URLs => BROAD; otherwise QUICK_CHECK.
No URL evidence => NO_DATA. Weekday summaries give dominant pattern and arithmetic
means across sessions. Thresholds require product calibration with real data.

AnalyticsTest verifies categories, model-derived aggregates, owner isolation,
soft deletion, seven-day boundary, empty data and JWT protection for all routes.
The implementation uses owner-scoped repository queries and per-session event
reads. Before high-volume deployment, replace this initial bounded-date read
path with indexed database aggregates/pagination and measure PostgreSQL workloads.
Session metadata is restricted by owner and overlapping dates in the database.
