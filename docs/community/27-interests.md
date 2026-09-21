# Add and read interests (COM-1-7)

Depends on #23.

| Method | Path | Body / result |
| --- | --- | --- |
| POST | /community/me/interests | {"hashtag":"Java"} |
| GET | /community/me/interests | Own ordered interest strings |
| GET | /community/profiles/{userId}/interests | Visible interest strings |

POST returns the updated list in a 200 ApiResponse. Each tag must consist of
1-10 Korean syllables, English letters or digits. Spaces and special characters
are rejected. Duplicate matching ignores case; display spelling is preserved.
At most ten interests are stored. Existing shared HashtagValidator errors return
400 for malformed, duplicate and over-limit tags.

Profile mutation holds a member row lock through validation and persistence.
Two requests adding the tenth tag cannot both succeed. A real two-transaction
test verifies this with H2; PostgreSQL locking needs deployment-environment
verification. Hidden interests are excluded from visitor reads but remain
available to their owner.
