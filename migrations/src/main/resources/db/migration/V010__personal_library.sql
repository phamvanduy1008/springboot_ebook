-- Personal metadata state. Shelf, favorite and view history are independent.
-- Tombstones retain revision counters so stale requests cannot resurrect removed state.
CREATE TABLE personal_book_state (
    user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    book_id uuid NOT NULL REFERENCES books(id) ON DELETE CASCADE,
    shelf varchar(8) CHECK (shelf IN ('want','reading','finished')),
    favorite boolean NOT NULL DEFAULT false,
    liked_at timestamptz,
    viewed_at timestamptz,
    saved_at timestamptz,
    revision bigint NOT NULL DEFAULT 0 CHECK (revision >= 0),
    updated_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY(user_id,book_id),
    CHECK (favorite = (liked_at IS NOT NULL)),
    CHECK ((shelf IS NULL) = (saved_at IS NULL))
);
CREATE INDEX personal_shelf_idx ON personal_book_state(user_id,saved_at DESC,book_id) WHERE shelf IS NOT NULL;
CREATE INDEX personal_history_idx ON personal_book_state(user_id,viewed_at DESC,book_id) WHERE viewed_at IS NOT NULL;
CREATE INDEX personal_likes_idx ON personal_book_state(book_id,liked_at,user_id) WHERE favorite;

CREATE TABLE personal_settings (
    user_id uuid PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    theme varchar(6) NOT NULL DEFAULT 'light' CHECK (theme IN ('light','dark','system')),
    reader_theme varchar(5) NOT NULL DEFAULT 'light' CHECK (reader_theme IN ('light','dark','sepia')),
    font_size integer NOT NULL DEFAULT 22 CHECK (font_size BETWEEN 14 AND 40),
    line_height numeric(3,2) NOT NULL DEFAULT 1.8 CHECK (line_height BETWEEN 1.2 AND 2.4),
    reader_font varchar(5) NOT NULL DEFAULT 'serif' CHECK (reader_font IN ('serif','sans')),
    revision bigint NOT NULL DEFAULT 0 CHECK (revision >= 0)
);

-- Receipts are private to the authenticated user and retained for 24 hours.
CREATE TABLE personal_mutations (
    user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    mutation_key uuid NOT NULL,
    fingerprint char(64) NOT NULL CHECK (fingerprint ~ '^[0-9a-f]{64}$'),
    result jsonb NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY(user_id,mutation_key)
);
CREATE INDEX personal_mutations_expiry_idx ON personal_mutations(created_at);
