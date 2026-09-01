CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE corpus_generation (
  id uuid PRIMARY KEY,
  profile text NOT NULL,
  manifest_hash text NOT NULL,
  fingerprint text NOT NULL,
  state text NOT NULL CHECK (state IN ('READY', 'REVOKED')),
  published_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE active_corpus (
  singleton boolean PRIMARY KEY DEFAULT true CHECK (singleton),
  generation_id uuid REFERENCES corpus_generation(id),
  policy_epoch bigint NOT NULL DEFAULT 0,
  last_complete_check timestamptz
);
INSERT INTO active_corpus(singleton) VALUES (true);

CREATE TABLE document (
  generation_id uuid NOT NULL REFERENCES corpus_generation(id),
  source_id text NOT NULL,
  service text NOT NULL,
  url text NOT NULL,
  title text NOT NULL,
  raw_hash text NOT NULL,
  content_hash text NOT NULL,
  snapshot_path text NOT NULL,
  fetched_at timestamptz NOT NULL,
  etag text,
  last_modified text,
  PRIMARY KEY(generation_id, source_id)
);
CREATE TABLE chunk (
  generation_id uuid NOT NULL,
  id text NOT NULL,
  source_id text NOT NULL,
  heading text NOT NULL,
  anchor text NOT NULL,
  ordinal integer NOT NULL,
  content text NOT NULL,
  embedding_input text NOT NULL,
  input_hash text NOT NULL,
  embedding vector(768) NOT NULL,
  terms tsvector GENERATED ALWAYS AS (to_tsvector('simple', heading || ' ' || content)) STORED,
  PRIMARY KEY(generation_id, id),
  FOREIGN KEY(generation_id, source_id) REFERENCES document(generation_id, source_id)
);
CREATE INDEX chunk_lexical ON chunk USING gin(terms);
CREATE INDEX document_service ON document(generation_id, service);
CREATE TABLE embedding_checkpoint (
  input_hash text NOT NULL,
  profile text NOT NULL,
  embedding vector(768) NOT NULL,
  PRIMARY KEY(input_hash, profile)
);
CREATE TABLE ingestion_job (
  id uuid PRIMARY KEY,
  manifest_hash text NOT NULL,
  state text NOT NULL CHECK(state IN ('RUNNING', 'SUCCEEDED', 'FAILED')),
  started_at timestamptz NOT NULL DEFAULT now(),
  finished_at timestamptz,
  error text
);
CREATE TABLE source_check (
  source_id text PRIMARY KEY,
  last_attempt timestamptz NOT NULL,
  last_verified timestamptz,
  error text
);
CREATE TABLE revoked_source (
  source_id text PRIMARY KEY,
  revoked_at timestamptz NOT NULL DEFAULT now()
);
