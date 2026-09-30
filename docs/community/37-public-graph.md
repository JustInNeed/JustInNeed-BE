# Public profiles and graph (COM-3-1 through COM-3-4)

Depends on #36. GET /community/profiles/{userId} returns visible leaf count, top
five hashtags, interests, actual like count/state and visible pinned sessions.
GET /community/profiles/{userId}/mindmap accepts optional numeric hashtagId.

The current data model has sessions, tags and sources, not an independent graph
editor. A profile is one mindmap: mindmapId = ownerId. A leaf represents a distinct
(sessionId, source URL); its durable community_nodes ID survives source reordering.
Edges use typed keys tag:<id> -> node:<id>, expressing real session membership.
We do not invent semantic relations. totalNodeCount counts distinct public URL
leaves, excluding hashtag grouping nodes; tag ranks count incident leaves.
Ties use case-insensitive name then ID. Up to five nonempty groups are returned.

URL_ONLY labels are URLs and content is null; private titles and excerpts are not
exposed. CONTENT enables source titles/excerpts. Public graph reads apply visitor
rules even for the owner. Mixed sessions containing a hidden tag are excluded.
Only HTTP(S) source URLs without embedded credentials are returned.

GET /community/me/bookmarks/{bookmarkId}/original returns the currently public
graph and the intersection with saved node IDs. It requires bookmark ownership.
Private/deleted original nodes are not included or highlighted; the independent
saved snapshot remains available from #36. The client renders blur/highlighting.

The like table/read repository is introduced here for real profile counts; #38
adds transactional like mutations. Numeric graph/tag indexes are lazily populated
under the profile owner's row lock, without changing original sessions.
