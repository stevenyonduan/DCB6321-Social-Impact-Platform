-- Testcontainers-only fixture. Never execute against Supabase.
CREATE SCHEMA auth;
CREATE TABLE auth.users (id UUID PRIMARY KEY);
CREATE ROLE anon;
CREATE ROLE authenticated;
