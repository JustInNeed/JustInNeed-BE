# Surfing API (#52)

All endpoints require the existing Bearer JWT. POST /surfing/extension-connections
issues a 30-day extension token (only a SHA-256 digest is stored). Linking again
rotates it. Transfer this token to the signed-in extension using the web client's
approved messaging flow; never put it in URLs. X-Extension-Token is required on
start, event ingestion, end and scrap creation. This is a new integration contract;
the backend cannot prove which browser client made a JWT-authenticated request.

Issue endpoints: POST /surfing/sessions; PATCH /surfing/sessions/{id}/end;
POST /surfing/sessions/{id}/events; GET /surfing/sessions/{id}/analysis-status;
POST /surfing/sessions/{id}/reanalyze; POST /surfing/sessions/{id}/scraps.
GET /surfing/sessions/{id}/scraps additionally lists the owner's separate scraps.

End body: {"reason":"USER"} or BROWSER_CLOSED. The extension must send the latter
on browser shutdown (best effort). Server inactivity closes disconnected clients.
Status supplies startedAt, endedAt, elapsedSeconds, inactivityNoticeAt (30 minutes),
automaticEndAt (30 minutes + 5 seconds), recordId, failure and attempts. The client
renders the timer, five-second warning, context menu and separate scrap section.
These are not implemented by the backend. The worker checks deadlines every 10s,
so actual processing can lag the effective timestamp by one polling interval.

Events body: {"events":[{"clientId":"uuid","type":"PAGE_VIEW","url":"https://example.com",
"title":"Page","content":"Text","occurredAt":"2026-10-29T01:00:00Z","activeMillis":1000}]}.
Types: PAGE_VIEW, MOUSE_MOVE, SCROLL, HOVER, CLICK. activeMillis is a nonoverlapping
active-time delta, maximum 60 seconds per event; the client must not repeat dwell
time on each mouse event or report background-tab time. Timestamps must be inside
the session and no more than 5s in the future. Retry IDs are unique within a session.
Limits: 100 events/request, 5000/session, 20k content/event, 100 scraps/session.
Scrap body: clientId, url, title, text (1..10000 chars). Duplicate scrap IDs are
idempotent. Enabled MEM-5 rules apply to events and scraps, including lazy defaults.
Domain rules include subdomains, with path-segment matching; suffix lookalikes do
not match. Excluded events are counted as ignored without storing their content.

COLLECTING -> ANALYZING -> COMPLETED or FAILED. A persisted ANALYZING row is a
restart-resumable job, processed under a database row lock (at most 50 per poll).
Model HTTP timeout is 20s. FAILED can be retried, up to five total attempts. Status
and duplicate end requests never create another record. Successful analysis makes
one private BrowsingSession/Summary; its ID is returned by the status endpoint.

Set app.surfing.analysis-url (environment APP_SURFING_ANALYSIS_URL) to a trusted
internal model service. POST sends serialized SurfEvent[] including clientId,
type, URL/content, timestamp and activeMillis. Return JSON:
{"title":"Title","markdown":"Summary","tags":["Java"],
"behaviors":{"event-client-id":"DEEP_READING"}}.
Every event must have DEEP_READING, READING, SCANNING, NAVIGATION or UNKNOWN.
Titles <=100, summary <=100000, tags follow existing 10x10 validation. No external
model is bundled or trained by this PR. Missing configuration, timeout or invalid
model data produces FAILED rather than fabricated classification or summaries.

New JPA tables: surf_sessions, surf_events, surf_scrap, extension_connection.
Review generated DDL through the existing deployment migration process. HTTP
processing currently holds one session row lock; worker throughput/load testing
and production model contract verification remain deployment requirements.

SurfTest covers exclusion boundaries, event retries, private record creation,
analysis retry, ownership, authentication, scraps and inactivity deadlines.
