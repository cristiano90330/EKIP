-- Tighten the V4 read-only role without rewriting an already-applied migration.
-- The Python RAG retriever reads only document_chunk. Tenant filtering remains an application
-- boundary in Java/Python; this role limits tables and writes, not rows within document_chunk.
ALTER ROLE rag_readonly
    LOGIN
    NOSUPERUSER
    NOCREATEDB
    NOCREATEROLE
    NOINHERIT
    NOREPLICATION
    NOBYPASSRLS;

REVOKE ALL PRIVILEGES ON ALL TABLES IN SCHEMA public FROM rag_readonly;
REVOKE CREATE ON SCHEMA public FROM rag_readonly;
GRANT USAGE ON SCHEMA public TO rag_readonly;
GRANT SELECT ON TABLE public.document_chunk TO rag_readonly;

-- V4 granted SELECT on every future public table. Remove that default for the postgres owner.
ALTER DEFAULT PRIVILEGES FOR ROLE postgres IN SCHEMA public
    REVOKE ALL PRIVILEGES ON TABLES FROM rag_readonly;
