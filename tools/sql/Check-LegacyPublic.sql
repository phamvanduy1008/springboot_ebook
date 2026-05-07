-- Read-only preflight for the opt-in legacy adoption launcher.
DO $$ BEGIN
 IF current_schema()<>'public' OR to_regclass('public.flyway_schema_history') IS NOT NULL
  OR to_regclass('public.books') IS NULL OR to_regclass('public.users') IS NULL
  OR to_regclass('public.spring_session') IS NULL OR to_regclass('public.stored_objects') IS NULL
  OR to_regprocedure('public.guard_version_lifecycle()') IS NULL
  OR to_regprocedure('public.guard_book_version_pointer()') IS NULL
  OR NOT EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema='public' AND table_name='book_interactions' AND column_name='dedupe_key')
  OR NOT EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema='public' AND table_name='book_interactions' AND column_name='happened_at')
  OR NOT EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema='public' AND table_name='book_characters' AND column_name='role_label')
  OR NOT EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema='public' AND table_name='users' AND column_name='password_changed_at') THEN
  RAISE EXCEPTION 'Not the supported unversioned public baseline; inspect schema manually';
 END IF;
 IF EXISTS(SELECT 1 FROM books WHERE search_fold_version<>'vi-v1') THEN
  RAISE EXCEPTION 'Unknown search-fold policy';
 END IF;
END $$;
