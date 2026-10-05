-- What Supabase pre-creates. The migrations GRANT to / REVOKE from these roles and
-- reference auth.users, so a plain Postgres needs them first.
CREATE ROLE anon NOLOGIN;
CREATE ROLE authenticated NOLOGIN;
CREATE ROLE service_role NOLOGIN;

-- Stand-in for Supabase's auth.users: the migrations only ever need its id column.
CREATE SCHEMA auth;
CREATE TABLE auth.users (id uuid PRIMARY KEY);
