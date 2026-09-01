ALTER TABLE chunk ADD COLUMN terms_english tsvector
  GENERATED ALWAYS AS (to_tsvector('english', heading || ' ' || content)) STORED;
CREATE INDEX chunk_english_lexical ON chunk USING gin(terms_english);
