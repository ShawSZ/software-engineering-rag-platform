-- Successful logout and account revocation must survive immediate process termination.
SET WRITE_DELAY 0;
-- Additive course foundation: never drop or rewrite upstream records.
CREATE TABLE IF NOT EXISTS kb_category (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,
 name VARCHAR(50) NOT NULL UNIQUE,
 sort_order INT NOT NULL DEFAULT 0
);
ALTER TABLE kb_document ADD COLUMN IF NOT EXISTS category_id BIGINT;
ALTER TABLE kb_document ADD COLUMN IF NOT EXISTS current_version_id BIGINT;
CREATE TABLE IF NOT EXISTS kb_document_version (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,
 document_id BIGINT NOT NULL REFERENCES kb_document(id),
 version_number INT NOT NULL,
 status VARCHAR(16) NOT NULL,
 object_key VARCHAR(512) NOT NULL UNIQUE,
 original_filename VARCHAR(256) NOT NULL,
 file_type VARCHAR(8) NOT NULL,
 file_size BIGINT NOT NULL,
 chunk_count INT NOT NULL DEFAULT 0,
 error_code VARCHAR(64),
 error_message VARCHAR(256),
 created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 UNIQUE(document_id, version_number)
);
ALTER TABLE kb_document_chunk ADD COLUMN IF NOT EXISTS version_id BIGINT;
ALTER TABLE kb_index_task ADD COLUMN IF NOT EXISTS version_id BIGINT;
CREATE UNIQUE INDEX IF NOT EXISTS uk_course_task_version ON kb_index_task(version_id);

-- Account session version and singleton lock; neither contains initial credentials.
ALTER TABLE kb_user ADD COLUMN IF NOT EXISTS auth_version BIGINT NOT NULL DEFAULT 0;
CREATE TABLE IF NOT EXISTS kb_account_guard (id INT PRIMARY KEY);
INSERT INTO kb_account_guard(id) SELECT 1 WHERE NOT EXISTS (SELECT 1 FROM kb_account_guard WHERE id = 1);
-- H2 VARCHAR precision counts UTF-16 units; the API still caps displayName at 64 code points.
ALTER TABLE kb_user ALTER COLUMN display_name SET DATA TYPE VARCHAR(128);

-- B03: additive constraints; legacy non-course rows may retain NULL version/category IDs.
ALTER TABLE kb_category ALTER COLUMN name SET DATA TYPE VARCHAR(100);
ALTER TABLE kb_document ALTER COLUMN title SET DATA TYPE VARCHAR(400);
ALTER TABLE kb_document ALTER COLUMN file_name SET DATA TYPE VARCHAR(510);
ALTER TABLE kb_document_version ALTER COLUMN original_filename SET DATA TYPE VARCHAR(510);
ALTER TABLE kb_document ADD CONSTRAINT IF NOT EXISTS fk_course_document_category FOREIGN KEY(category_id) REFERENCES kb_category(id);
ALTER TABLE kb_document_chunk ADD CONSTRAINT IF NOT EXISTS fk_course_chunk_version FOREIGN KEY(version_id) REFERENCES kb_document_version(id);
CREATE UNIQUE INDEX IF NOT EXISTS uk_course_version_chunk ON kb_document_chunk(version_id,chunk_index);
CREATE INDEX IF NOT EXISTS idx_course_document_category ON kb_document(category_id);
CREATE INDEX IF NOT EXISTS idx_course_task_lease ON kb_index_task(status,locked_at);

-- Course questions and immutable provenance snapshots; never rewrite upstream history.
CREATE TABLE IF NOT EXISTS kb_qa_record (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,
 user_id BIGINT NOT NULL REFERENCES kb_user(id),
 question VARCHAR(1000) NOT NULL,
 status VARCHAR(16) NOT NULL CHECK(status IN ('SUCCESS','NO_EVIDENCE','FAILED')),
 answer CLOB,
 error_code VARCHAR(64), error_message VARCHAR(256),
 retryable BOOLEAN NOT NULL DEFAULT FALSE,
 latency_ms BIGINT NOT NULL DEFAULT 0,
 created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE IF NOT EXISTS kb_qa_source (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,
 qa_id BIGINT NOT NULL REFERENCES kb_qa_record(id),
 evidence_label VARCHAR(8) NOT NULL,
 document_id BIGINT NOT NULL REFERENCES kb_document(id),
 version_id BIGINT NOT NULL REFERENCES kb_document_version(id),
 chunk_id BIGINT NOT NULL REFERENCES kb_document_chunk(id),
 document_title VARCHAR(400) NOT NULL,
 version_number INT NOT NULL, page_no INT, chunk_index INT NOT NULL,
 excerpt CLOB NOT NULL,
 UNIQUE(qa_id,evidence_label)
);
CREATE INDEX IF NOT EXISTS idx_course_qa_user_created ON kb_qa_record(user_id,created_at,id);
CREATE INDEX IF NOT EXISTS idx_course_source_qa ON kb_qa_source(qa_id);

CREATE TABLE IF NOT EXISTS kb_favorite (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,
 user_id BIGINT NOT NULL REFERENCES kb_user(id),
 qa_id BIGINT NOT NULL REFERENCES kb_qa_record(id),
 created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 UNIQUE(user_id,qa_id)
);
CREATE TABLE IF NOT EXISTS kb_feedback (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,
 user_id BIGINT NOT NULL REFERENCES kb_user(id),
 qa_id BIGINT NOT NULL REFERENCES kb_qa_record(id),
 rating VARCHAR(16) NOT NULL CHECK(rating IN ('HELPFUL','UNHELPFUL')),
 reason VARCHAR(1000) NOT NULL DEFAULT '',
 created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 UNIQUE(user_id,qa_id)
);
CREATE INDEX IF NOT EXISTS idx_course_favorite_user ON kb_favorite(user_id,created_at,id);
CREATE INDEX IF NOT EXISTS idx_course_feedback_updated ON kb_feedback(updated_at,id);
