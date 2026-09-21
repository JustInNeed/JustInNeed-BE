# Pin, order and read shared sessions (COM-1-3, COM-1-5, COM-1-6)

Depends on #23. This PR targets its branch and also covers the reorder/detail
requirements, which have no separately assigned issue in the supplied list.

| Method | Path | Body / result |
| --- | --- | --- |
| POST | /community/me/shared-sessions | {"sessionId":123} |
| PATCH | /community/me/shared-sessions/order | {"sessionIds":[456,123]} |
| GET | /community/profiles/{userId}/shared-sessions | Ordered visible pins |
| GET | /community/profiles/{userId}/shared-sessions/{sessionId} | Shared detail |

POST returns 200, atomically makes an owned active session public and appends it
to the pins. Repeating it keeps one entry and the existing position.
Foreign/deleted/missing sessions return 404. Order must contain exactly the live
pin set with no duplicates or nulls; invalid requests return 400 without changes.

Visibility is still enforced on all visitor reads. URL_ONLY omits content;
hidden-tag sessions are excluded even if pinned. A detail ID cannot bypass
these checks. Owners see content for their active public pins. The existing
/sessions API continues to provide private editing and private detail access.

Session deletion or an isPublic=false edit immediately removes the session from
shared reads. Old pin IDs are ignored and cleaned on the next add/reorder. Drag
and drop must send the complete owner's live pin list, including pins hidden
from visitors by tag preferences.
