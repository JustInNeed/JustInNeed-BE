# Remove shared pin (COM-1-4)

Depends on #23; the add API in #25 uses the same profile model.

DELETE /community/me/shared-sessions/{sessionId}

> 2026-10-29: 이 경로는 중복이라 주석처리됨. 같은 서비스를 쓰는 `DELETE /community/me/pinned-sessions/{sessionId}` 를 사용한다.

Show the confirmation popup in the client before calling this API. A successful
200 ApiResponse removes the pin and sets isPublic=false in one transaction.
The session row, summary, sources and private workspace entry are preserved.
Remaining pins retain their relative order.

Missing, deleted, unpinned or another user's sessions return 404. Repeated deletes
therefore return 404 after the first success. The JWT identifies the owner.
Concurrent pin/order/unpin operations use the same member lock.
