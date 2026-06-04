CREATE TABLE epub_publication_receipts (
 user_id uuid NOT NULL REFERENCES users(id),mutation_key uuid NOT NULL,
 book_id uuid NOT NULL REFERENCES books(id),version_id uuid,base_revision bigint NOT NULL CHECK(base_revision>=0),
 action varchar(12) NOT NULL CHECK(action IN ('PUBLISH','UNPUBLISH')),
 result_revision bigint NOT NULL,result_status varchar(12) NOT NULL,result_version_id uuid,
 created_at timestamptz NOT NULL DEFAULT now(),PRIMARY KEY(user_id,mutation_key)
);
