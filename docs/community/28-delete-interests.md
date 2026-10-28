# Delete an interest (COM-1-8)

Depends on #23; #27 supplies registration/list routes on the same model.

DELETE /community/me/interests/{hashtag}

URL-encode the hashtag path segment, including Korean values. A successful
200 ApiResponse contains the remaining interests in their original order.
Matching ignores case. Only the JWT owner's interest list changes; session
tags, visibility preferences and shared pins are untouched.

Missing interests return 404, invalid hashtag formats return 400, and requests
without authentication return 401. Removal and concurrent additions share the
same member lock, so list changes cannot silently overwrite each other.
