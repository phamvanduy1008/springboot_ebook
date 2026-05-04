
ALTER TABLE books ADD COLUMN quote varchar(500);
ALTER TABLE book_versions ADD COLUMN chapter_count integer CHECK(chapter_count>=0);
CREATE TABLE book_characters(
 id uuid PRIMARY KEY,book_id uuid NOT NULL REFERENCES books(id) ON DELETE CASCADE,
 name varchar(120) NOT NULL,role varchar(100) NOT NULL DEFAULT '',description varchar(1000) NOT NULL DEFAULT '',
 initials varchar(8) NOT NULL DEFAULT '',color varchar(7) NOT NULL DEFAULT '#3343db' CHECK(color ~ '^#[0-9a-fA-F]{6}$'),
 position integer NOT NULL CHECK(position>=0),revision bigint NOT NULL DEFAULT 0 CHECK(revision>=0),UNIQUE(book_id,position)
);
