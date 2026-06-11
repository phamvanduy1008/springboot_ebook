-- Listing metadata is independent of publishing a sanitized, readable EPUB.
-- Existing drafts stay private to administration until explicitly listed by an importer.
ALTER TABLE books ADD COLUMN IF NOT EXISTS catalog_listed boolean NOT NULL DEFAULT false;

CREATE TABLE IF NOT EXISTS local_epub_sources (
    source_root char(64) NOT NULL CHECK (source_root ~ '^[0-9a-f]{64}$'),
    relative_path text NOT NULL CHECK (length(relative_path)>0),
    book_id uuid NOT NULL REFERENCES books(id) ON DELETE CASCADE,
    byte_size bigint NOT NULL CHECK (byte_size>0),
    modified_at timestamptz NOT NULL,
    scanned_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY(source_root,relative_path)
);
CREATE INDEX IF NOT EXISTS local_epub_sources_book_idx ON local_epub_sources(book_id);
