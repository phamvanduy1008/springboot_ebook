ALTER TABLE users ALTER COLUMN password_hash DROP NOT NULL;
CREATE TABLE user_identities (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  provider varchar(16) NOT NULL CHECK (provider='GOOGLE'),
  issuer varchar(255) NOT NULL CHECK (issuer='https://accounts.google.com'),
  subject varchar(255) NOT NULL CHECK (length(subject)>0),
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(provider,issuer,subject),
  UNIQUE(user_id,provider)
);
