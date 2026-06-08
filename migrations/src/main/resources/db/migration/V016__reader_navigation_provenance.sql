ALTER TABLE book_versions ADD COLUMN toc_source varchar(12) NOT NULL DEFAULT 'LEGACY'
 CHECK(toc_source IN ('LEGACY','NAV','NCX','FALLBACK'));
COMMENT ON COLUMN book_versions.toc_source IS 'Navigation provenance; FALLBACK resources are not counted as authored chapters.';
