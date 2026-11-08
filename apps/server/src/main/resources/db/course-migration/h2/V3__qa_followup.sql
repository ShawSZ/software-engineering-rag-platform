-- Existing single-turn records remain independent conversation roots.
ALTER TABLE kb_qa_record ADD COLUMN parent_qa_id BIGINT;
ALTER TABLE kb_qa_record ADD CONSTRAINT fk_course_qa_parent_owner
    FOREIGN KEY(user_id,parent_qa_id) REFERENCES kb_qa_record(user_id,id);
ALTER TABLE kb_qa_record ADD CONSTRAINT ck_course_qa_parent_order
    CHECK(parent_qa_id IS NULL OR parent_qa_id < id);
CREATE INDEX idx_course_qa_parent ON kb_qa_record(user_id,parent_qa_id);
