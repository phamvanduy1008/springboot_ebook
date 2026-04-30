
CREATE TABLE catalog_metric_config(singleton boolean PRIMARY KEY DEFAULT true CHECK(singleton),key_material bytea NOT NULL CHECK(octet_length(key_material)=32));
INSERT INTO catalog_metric_config(singleton,key_material) VALUES(true,decode(replace(gen_random_uuid()::text||gen_random_uuid()::text,'-',''),'hex'));
CREATE TABLE book_interactions(
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
 book_id uuid NOT NULL REFERENCES books(id) ON DELETE CASCADE,
 kind varchar(10) NOT NULL CHECK(kind IN ('VIEW','READ')),
 viewer_hash char(64) NOT NULL CHECK(viewer_hash ~ '^[0-9a-f]{64}$'),
 occurred_at timestamptz NOT NULL,
 event_day date GENERATED ALWAYS AS ((occurred_at AT TIME ZONE 'UTC')::date) STORED,
 UNIQUE(book_id,kind,viewer_hash,event_day)
);
CREATE INDEX book_interactions_window_idx ON book_interactions(occurred_at,book_id,kind);
