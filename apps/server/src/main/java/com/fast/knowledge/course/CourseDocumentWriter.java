package com.fast.knowledge.course;

import com.fast.knowledge.mapper.*;
import com.fast.knowledge.model.entity.*;
import com.fast.knowledge.security.UserContext;
import com.fast.knowledge.common.ApiException;
import com.fast.knowledge.storage.*;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.*;
import java.io.IOException;

/** The file exists before this short transaction; rollback removes only that new object. */
@Service
public class CourseDocumentWriter {
    private final DocumentMapper documents;
    private final DocumentVersionMapper versions;
    private final CourseCategoryService categories;
    private final JdbcTemplate jdbc;
    private final ApplicationEventPublisher events;
    private final StorageProvider storage;
    public CourseDocumentWriter(DocumentMapper documents,DocumentVersionMapper versions,CourseCategoryService categories,
            JdbcTemplate jdbc,ApplicationEventPublisher events,StorageProvider storage) {
        this.documents=documents;this.versions=versions;this.categories=categories;this.jdbc=jdbc;this.events=events;this.storage=storage;
    }
    public record Queued(long taskId) { }
    public record Created(KbDocument document,DocumentVersion version,String categoryName,DocumentVersion currentVersion) { }
    @Transactional(rollbackFor=Exception.class)
    public Created create(StoredObject stored,CourseFileParser.Upload file,String title,long categoryId) {
        cleanupOnRollback(stored);
        categories.lock(categoryId); // Serializes reference creation against category deletion.
        KbDocument doc=new KbDocument();doc.setKbId(0L);doc.setCategoryId(categoryId);doc.setTitle(title);
        doc.setFileName(file.filename());doc.setFileType(file.type().toLowerCase(java.util.Locale.ROOT));
        doc.setFileSize(stored.size());doc.setFilePath(stored.absolutePath());doc.setIndexStatus("QUEUED");
        doc.setChunkCount(0);doc.setEnabled(1);doc.setCreatedBy(UserContext.currentUserId());documents.insert(doc);
        return queue(doc,stored,file,1);
    }
    @Transactional(rollbackFor=Exception.class)
    public Created replace(long id,StoredObject stored,CourseFileParser.Upload file) {
        cleanupOnRollback(stored);KbDocument doc=lock(id);active(doc);
        if(jdbc.queryForObject("SELECT COUNT(*) FROM kb_document_version WHERE document_id=? AND status IN ('QUEUED','PARSING')",Long.class,id)>0)
            throw new ApiException(40904,"已有版本正在解析，请等待完成");
        int number=jdbc.queryForObject("SELECT COALESCE(MAX(version_number),0)+1 FROM kb_document_version WHERE document_id=?",Integer.class,id);
        jdbc.update("UPDATE kb_document SET updated_at=CURRENT_TIMESTAMP WHERE id=?",id);
        return queue(doc,stored,file,number);
    }
    @Transactional
    public long metadata(long id,String title,long categoryId) {
        categories.lock(categoryId);KbDocument doc=lock(id);active(doc);
        jdbc.update("UPDATE kb_document SET title=?,category_id=?,updated_at=CURRENT_TIMESTAMP WHERE id=?",title,categoryId,id);
        return id;
    }
    @Transactional
    public void offline(long id) {
        KbDocument doc=lock(id);
        if(Integer.valueOf(1).equals(doc.getEnabled())) jdbc.update("UPDATE kb_document SET enabled=0,updated_at=CURRENT_TIMESTAMP WHERE id=?",id);
    }
    private KbDocument lock(long id) {
        CourseInputs.id(id);
        if(jdbc.queryForList("SELECT id FROM kb_document WHERE id=? AND kb_id=0 FOR UPDATE",id).isEmpty()) throw ApiException.notFound();
        return documents.selectById(id);
    }
    private void active(KbDocument doc) {
        if(!Integer.valueOf(1).equals(doc.getEnabled())) throw new ApiException(40906,"资料已下架，不能修改");
    }
    private void cleanupOnRollback(StoredObject stored) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCompletion(int status) {
                if(status!=STATUS_COMMITTED) try {storage.delete(stored.absolutePath());}
                catch(IOException e) {org.slf4j.LoggerFactory.getLogger(getClass()).error("Rolled-back upload cleanup failed");}
            }
        });
    }
    private Created queue(KbDocument doc,StoredObject stored,CourseFileParser.Upload file,int number) {
        DocumentVersion current=doc.getCurrentVersionId()==null?null:versions.selectById(doc.getCurrentVersionId());
        DocumentVersion version=new DocumentVersion();version.setDocumentId(doc.getId());version.setVersionNumber(number);
        version.setStatus("QUEUED");version.setObjectKey(stored.absolutePath());version.setOriginalFilename(file.filename());
        version.setFileType(file.type());version.setFileSize(stored.size());version.setSha256(file.sha256());version.setChunkCount(0);versions.insert(version);
        jdbc.update("INSERT INTO kb_index_task(document_id,version_id,status,retry_count) VALUES (?,?,'QUEUED',0)",doc.getId(),version.getId());
        long taskId=jdbc.queryForObject("SELECT id FROM kb_index_task WHERE version_id=?",Long.class,version.getId());
        String categoryName=jdbc.queryForObject("SELECT name FROM kb_category WHERE id=?",String.class,doc.getCategoryId());
        events.publishEvent(new Queued(taskId));return new Created(doc,version,categoryName,current);
    }
}
