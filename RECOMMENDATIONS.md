# Lightweight book recommendations

Scope: implement `GET /api/v1/recommendations?limit=6` (1–12), without changing
the existing `/home` response or deploying another service. Public catalog and
personal-library metadata are prerequisites. The frontend consumes this endpoint
separately; its local changes are not pushed with the backend.

## Ranking

The service reads at most 100 currently effective, public-book history records.
For each book it chooses the highest **decayed** signal: favorite 5, reading shelf
4, want shelf 3, detail view 1. A finished shelf alone is not a positive signal.
Each signal uses its own liked/saved/viewed timestamp, not generic updated_at.
`decay = 0.5^(max(0, ageSeconds) / (30 * 86400))`.

Feature contributions are divided by each book's number of distinct categories,
tags or authors. Each feature family's profile is normalized by its maximum.
Candidate affinity averages its profile matches within each family:
`affinity = .60 * categoryMatch + .25 * tagMatch + .15 * authorMatch`.
Tags use NFKC, lowercase and collapsed whitespace (accents remain meaningful).
The SQL candidate prefilter uses the existing catalog accent fold to avoid
locale-dependent Vietnamese casing; final scoring still uses exact normalized tags.

Candidate pools are public eligible books: newest 80, popular 60 and matching
metadata 60, deduplicated to at most 200. All sources exclude the requesting
user's shelves (want/reading/finished) and favorites, including entries outside
the 100-record history bound. Mere detail views do not exclude books. Public
eligibility reuses CatalogRepository.PUBLIC, including explicitly listed
metadata-only books; those keep readerAvailable=false.

`popularityRaw = viewsInLast30Days + 5 * activeUsersCurrentLikesInLast30Days`.
Normalize log1p(raw) by the candidate pool maximum. Future/stale events are not
counted. READ exists in the schema but is not used because this feature has not
established reliable read-event collection. No synthetic tracking is added.
Freshness uses the same 30-day half-life on created_at: the current book model
has no first-publication timestamp. Metadata edits do not reset freshness.

`score = .55 * affinity + .25 * popularity + .20 * freshness`.
Weights are initial heuristics, not measured or learned parameters.

For six results, select up to four affinity matches, one remaining popular book,
then one book outside the strongest category when available. Fill shortfalls by
score. Guests/empty profiles use popular and new books. Cap each author at two
books; only relax this cap after exhausting diverse eligible candidates. Ties
use UUID. No random sorting. Return fewer results if the eligible catalog is small.

Reasons reflect selection: SAME_CATEGORY, SAME_AUTHOR, SAME_TAG, POPULAR, NEW,
DISCOVERY. No editorial flag exists, so a catalog without interactions falls
back to new books instead of inventing editorial selections.

## Privacy and implementation

Controller → service → repository; bounded SQL candidate pools and batch metadata
loading avoid per-book queries. A repeatable-read transaction gives one snapshot.
No migration or additional cache is needed at the current catalog size; existing
window/history/relationship indexes are reused. The current-account filter
validates authenticated sessions. User identity never comes from request fields.
All responses set `Cache-Control: private, no-store`. Scores, profile and history
are not returned. Private books do not influence public suggestions.

Example response (book is the existing PublicCatalogBook DTO):

```json
{"items":[{"book":{"id":"...","title":"..."},"reasonCode":"SAME_CATEGORY","reasonText":"Vì bạn quan tâm Tiên Hiệp"}]}
```

The frontend shows loading, retry/error and empty states, plus each reason. Its
request subtree remounts on identity changes and unmounts while session identity
is being refreshed, aborting stale requests and discarding previous results.
No demo fallback or cross-user cache is used.

## Limits and validation

Shelf saved_at records initial saving, not every transition to reading; this is
the available timestamp, so a shelf change does not pretend to be recent activity.
Removing a favorite/view removes that signal immediately, but independent
remaining shelf/view/favorite signals may legitimately retain an interest.
Relative profile normalization preserves an old sole interest; decay chiefly
changes its importance relative to newer interests. The bounded candidate pools
can miss some relevant books in much larger catalogs. No CTR improvement is claimed.

Tests: RecommendationScorerTest checks decay, normalization, reasons, diversity,
fallback and deterministic ties. RecommendationIT uses an isolated PostgreSQL
database and fixed Clock to check HTTP limits, public visibility, per-session
exclusions, ignored forged user IDs, removals and candidate bounds. Frontend
recommendations.spec.ts covers response rendering and identity races with explicit
API fixtures. Run Maven verify with the database harness and frontend API contract,
build and Playwright checks; report actual outcomes in the delivery summary.
