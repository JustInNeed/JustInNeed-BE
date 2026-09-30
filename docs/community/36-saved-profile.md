# Saved profile lists (COM-2-1, COM-2-2)

Depends on issue #35. GET /community/me/bookmarks/hashtag-bundles and
GET /community/me/bookmarks/mindmaps use page=0, size=10 by default (size 1-100).
Both return code, the named collection, and hasNext, ordered by savedAt DESC,
id DESC. All repository reads are scoped to the authenticated user.

community_bookmarks stores a JSON snapshot with copied hashtags, leaf nodes and
tag-to-node edges. No lazy source entity is serialized. Original edits or deletion
do not modify a saved mini mindmap. Snapshot content reflects only what was public
at save time. Original-view highlighting is provided by the subsequent graph API.
Rendering the blurred background and colored selection is a client responsibility.

The unique (user_id, selection_key) constraint supports idempotent registration
in #38. Bookmark creation never accepts arbitrary client-provided snapshot content.
This PR introduces the storage and read contracts, not a placeholder creation API.
