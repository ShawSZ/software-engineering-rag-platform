package com.fast.knowledge.course;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;

/** Database leases fence every mutation; parsing and file I/O never run in these transactions. */
@Service
public class CourseIngestTasks {
    private final JdbcTemplate jdbc;
    private final long leaseSeconds;
    public CourseIngestTasks(JdbcTemplate jdbc,@Value("${course.ingest.lease-seconds:300}") long leaseSeconds) {
        if(leaseSeconds<1) throw new IllegalArgumentException("Invalid ingest lease");
        this.jdbc=jdbc;this.leaseSeconds=leaseSeconds;
    }
    public record Claim(long taskId,long documentId,long versionId,String key,String type,String owner,String sha256) { }
    public List<Long> pending() {
        return jdbc.query("SELECT t.id FROM kb_index_task t JOIN kb_document d ON d.id=t.document_id "
                +"WHERE d.kb_id=0 AND t.version_id IS NOT NULL AND (t.status='QUEUED' OR "
                +"(t.status='PARSING' AND (t.locked_at IS NULL OR t.locked_at<?))) ORDER BY t.id LIMIT 20",
                (r,n)->r.getLong(1),cutoff());
    }
    private Timestamp cutoff() {return Timestamp.valueOf(LocalDateTime.now().minusSeconds(leaseSeconds));}
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public Claim claim(long id) {
        var rows=jdbc.queryForList("SELECT * FROM kb_index_task WHERE id=? FOR UPDATE",id);
        if(rows.isEmpty()) return null;
        var task=rows.getFirst();String state=(String)task.get("status");
        Timestamp locked=(Timestamp)task.get("locked_at");
        if(!"QUEUED".equals(state) && !("PARSING".equals(state) && (locked==null || locked.before(cutoff())))) return null;
        Number versionId=(Number)task.get("version_id");if(versionId==null) return null;
        long documentId=((Number)task.get("document_id")).longValue();
        var docs=jdbc.queryForList("SELECT id FROM kb_document WHERE id=? AND kb_id=0 FOR UPDATE",documentId);
        if(docs.isEmpty()) return null;
        var versions=jdbc.queryForList("SELECT * FROM kb_document_version WHERE id=? AND document_id=? FOR UPDATE",versionId,documentId);
        if(versions.isEmpty()) return null;
        var version=versions.getFirst();String versionState=(String)version.get("status");
        if(!Set.of("QUEUED","PARSING").contains(versionState)) {
            jdbc.update("UPDATE kb_index_task SET status=?,locked_by=NULL,locked_at=NULL WHERE id=?",
                    "READY".equals(versionState)?"DONE":"FAILED",id);return null;
        }
        String owner=UUID.randomUUID().toString();
        jdbc.update("UPDATE kb_index_task SET status='PARSING',locked_by=?,locked_at=CURRENT_TIMESTAMP,retry_count=retry_count+1,error_msg=NULL WHERE id=?",owner,id);
        var claim=new Claim(id,documentId,versionId.longValue(),(String)version.get("object_key"),(String)version.get("file_type"),owner,(String)version.get("sha256"));
        jdbc.update("DELETE FROM kb_document_chunk WHERE version_id=?",versionId);
        if(((Number)task.get("retry_count")).intValue()>=3) {
            markFailed(claim,"PARSE_ERROR","解析任务多次中断，请重新上传文件");return null;
        }
        jdbc.update("UPDATE kb_document_version SET status='PARSING',chunk_count=0,error_code=NULL,error_message=NULL WHERE id=?",versionId);
        jdbc.update("UPDATE kb_document SET index_status='PARSING',index_error=NULL WHERE id=? AND current_version_id IS NULL",documentId);
        return claim;
    }
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public boolean append(Claim claim,List<CourseFileParser.Chunk> chunks) {
        if(!owns(claim)) return false;
        List<Object[]> arguments=chunks.stream().map(c->new Object[]{claim.documentId(),claim.versionId(),c.index(),c.content(),c.pageNo()}).toList();
        jdbc.batchUpdate("INSERT INTO kb_document_chunk(kb_id,document_id,version_id,chunk_index,content,page_no,anchor_type,token_count) "
                +"VALUES(0,?,?,?,?,?,'text',0)",arguments);
        jdbc.update("UPDATE kb_index_task SET locked_at=CURRENT_TIMESTAMP WHERE id=? AND locked_by=?",claim.taskId(),claim.owner());
        return true;
    }
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public boolean complete(Claim claim,int count) {
        if(!owns(claim)) return false;
        jdbc.queryForList("SELECT id FROM kb_document WHERE id=? FOR UPDATE",claim.documentId());
        var versions=jdbc.queryForList("SELECT version_number,status FROM kb_document_version WHERE id=? AND document_id=? FOR UPDATE",claim.versionId(),claim.documentId());
        if(versions.isEmpty() || !"PARSING".equals(versions.getFirst().get("status"))) return false;
        int latest=jdbc.queryForObject("SELECT MAX(version_number) FROM kb_document_version WHERE document_id=?",Integer.class,claim.documentId());
        if(((Number)versions.getFirst().get("version_number")).intValue()!=latest) {
            markFailed(claim,"PARSE_ERROR","候选版本已改变，请重新上传");return false;
        }
        if(count<1 || jdbc.queryForObject("SELECT COUNT(*) FROM kb_document_chunk WHERE version_id=?",Integer.class,claim.versionId())!=count)
            throw new IllegalStateException("Incomplete parsed chunks");
        jdbc.update("UPDATE kb_document_version SET status='READY',chunk_count=?,error_code=NULL,error_message=NULL WHERE id=?",count,claim.versionId());
        jdbc.update("UPDATE kb_document SET current_version_id=?,index_status='READY',chunk_count=?,index_error=NULL,updated_at=CURRENT_TIMESTAMP,"
                +"file_name=(SELECT original_filename FROM kb_document_version WHERE id=?),file_type=(SELECT LOWER(file_type) FROM kb_document_version WHERE id=?),"
                +"file_size=(SELECT file_size FROM kb_document_version WHERE id=?),file_path=(SELECT object_key FROM kb_document_version WHERE id=?) WHERE id=?",
                claim.versionId(),count,claim.versionId(),claim.versionId(),claim.versionId(),claim.versionId(),claim.documentId());
        jdbc.update("UPDATE kb_index_task SET status='DONE',locked_by=NULL,locked_at=NULL,error_msg=NULL WHERE id=? AND locked_by=?",claim.taskId(),claim.owner());
        return true;
    }
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public boolean fail(Claim claim,String code,String message) {
        if(!owns(claim)) return false;
        markFailed(claim,code,message);return true;
    }
    private void markFailed(Claim claim,String code,String message) {
        jdbc.update("DELETE FROM kb_document_chunk WHERE version_id=?",claim.versionId());
        jdbc.update("UPDATE kb_document_version SET status='FAILED',chunk_count=0,error_code=?,error_message=? WHERE id=?",code,message,claim.versionId());
        jdbc.update("UPDATE kb_document SET index_status='FAILED',index_error=?,chunk_count=0 WHERE id=? AND current_version_id IS NULL",message,claim.documentId());
        jdbc.update("UPDATE kb_index_task SET status='FAILED',locked_by=NULL,locked_at=NULL,error_msg=? WHERE id=? AND locked_by=?",message,claim.taskId(),claim.owner());
    }
    private boolean owns(Claim claim) {
        return !jdbc.queryForList("SELECT id FROM kb_index_task WHERE id=? AND version_id=? AND status='PARSING' AND locked_by=? "
                +"AND locked_at>=? FOR UPDATE",claim.taskId(),claim.versionId(),claim.owner(),cutoff()).isEmpty();
    }
}
