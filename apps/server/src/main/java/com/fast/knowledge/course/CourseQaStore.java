package com.fast.knowledge.course;

import com.fast.knowledge.common.*;
import com.fast.knowledge.security.UserContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.sql.*;
import java.time.*;
import java.util.*;

@Service
public class CourseQaStore {
    private final JdbcTemplate jdbc;
    private final CoursePersonalService personal;
    public CourseQaStore(JdbcTemplate jdbc,CoursePersonalService personal) {this.jdbc=jdbc;this.personal=personal;}
    public record Source(long sourceId,String evidenceLabel,long documentId,long versionId,int versionNumber,long chunkId,
            String documentTitle,Integer pageNo,int chunkIndex,String excerpt,String availability) { }
    public record Detail(long qaId,String status,String answer,List<Source> sources,long latencyMs,String errorCode,
            String errorMessage,boolean retryable,String question,String createdAt,boolean favorited,Object feedback,Long parentQaId) { }
    public record Summary(long qaId,String question,String status,String answerPreview,long latencyMs,String errorCode,
            String errorMessage,String createdAt,boolean favorited) { }
    @Transactional
    public long save(long userId,String question,CourseQaOutcome outcome,List<CourseEvidence> evidence,long latency) {
        return save(userId,question,outcome,evidence,latency,null);
    }
    @Transactional
    public long save(long userId,String question,CourseQaOutcome outcome,List<CourseEvidence> evidence,long latency,Long parentQaId) {
        if(parentQaId!=null) owned(parentQaId);
        long savingStart=System.nanoTime();
        CourseQaOutcome result=outcome;
        if("SUCCESS".equals(result.status()) && !current(evidence)) result=CourseQaOutcome.failed("EVIDENCE_CHANGED");
        var key=new GeneratedKeyHolder();CourseQaOutcome saved=result;
        jdbc.update(connection->{
            var statement=connection.prepareStatement("INSERT INTO kb_qa_record(user_id,question,status,answer,error_code,error_message,retryable,latency_ms) VALUES (?,?,?,?,?,?,?,?)",new String[]{"id"});
            statement.setLong(1,userId);statement.setString(2,question);statement.setString(3,saved.status());statement.setString(4,saved.answer());
            statement.setString(5,saved.errorCode());statement.setString(6,saved.errorMessage());statement.setBoolean(7,saved.retryable());statement.setLong(8,latency);return statement;
        },key);
        long id=Objects.requireNonNull(key.getKey()).longValue();
        if(parentQaId!=null) jdbc.update("UPDATE kb_qa_record SET parent_qa_id=? WHERE id=?",parentQaId,id);
        if("SUCCESS".equals(result.status())) for(var item:evidence) if(result.citations().contains(item.label())) {
            jdbc.update("INSERT INTO kb_qa_source(qa_id,evidence_label,document_id,version_id,chunk_id,document_title,version_number,page_no,chunk_index,excerpt) VALUES (?,?,?,?,?,?,?,?,?,?)",
                    id,item.label(),item.documentId(),item.versionId(),item.chunkId(),item.documentTitle(),item.versionNumber(),item.pageNo(),item.chunkIndex(),item.content());
        }
        jdbc.update("UPDATE kb_qa_record SET latency_ms=? WHERE id=?",latency+java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-savingStart),id);
        return id;
    }
    private boolean current(List<CourseEvidence> evidence) {
        for(long id:evidence.stream().map(CourseEvidence::documentId).distinct().sorted().toList()) {
            if(jdbc.queryForList("SELECT id FROM kb_document WHERE id=? FOR UPDATE",id).isEmpty()) return false;
        }
        for(var item:evidence) {
            var rows=jdbc.queryForList("SELECT c.content,c.page_no,c.chunk_index,v.version_number FROM kb_document d "
                    +"JOIN kb_document_version v ON v.id=d.current_version_id AND v.document_id=d.id "
                    +"JOIN kb_document_chunk c ON c.document_id=d.id AND c.version_id=v.id "
                    +"WHERE d.id=? AND d.kb_id=0 AND d.enabled=1 AND v.id=? AND v.status='READY' AND c.id=?",item.documentId(),item.versionId(),item.chunkId());
            if(rows.size()!=1) return false;var row=rows.getFirst();
            if(!((String)row.get("content")).startsWith(item.content()) || !Objects.equals(row.get("page_no"),item.pageNo())
                    || ((Number)row.get("chunk_index")).intValue()!=item.chunkIndex() || ((Number)row.get("version_number")).intValue()!=item.versionNumber()) return false;
        }
        return !evidence.isEmpty();
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Detail detail(long id) {
        var row=owned(id);
        return new Detail(id,(String)row.get("status"),string(row.get("answer")),sources(id),((Number)row.get("latency_ms")).longValue(),
                (String)row.get("error_code"),(String)row.get("error_message"),(Boolean)row.get("retryable"),(String)row.get("question"),
                time(row.get("created_at")),personal.isFavorite(UserContext.currentUserId(),id),personal.feedbackFor(UserContext.currentUserId(),id),
                row.get("parent_qa_id")==null?null:((Number)row.get("parent_qa_id")).longValue());
    }
    /** Each link is checked against the authenticated owner; a continuation may branch from an earlier turn. */
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public List<Detail> conversation(long id) {
        var turns=new ArrayList<Detail>();var seen=new HashSet<Long>();Long next=id;
        while(next!=null) {
            if(turns.size()>=100 || !seen.add(next)) throw new ApiException(42201,"对话已达100轮，请开始新对话");
            var turn=detail(next);turns.add(turn);next=turn.parentQaId();
        }
        Collections.reverse(turns);return turns;
    }
    public Map<String,Object> owned(long id) {
        CourseInputs.id(id);
        var rows=jdbc.queryForList("SELECT * FROM kb_qa_record WHERE id=? AND user_id=?",id,UserContext.currentUserId());
        if(rows.isEmpty()) throw ApiException.notFound();return rows.getFirst();
    }
    public List<Source> sources(long id) {
        return jdbc.query("SELECT s.*,CASE WHEN d.id IS NULL OR d.enabled<>1 THEN 'OFFLINE' WHEN d.current_version_id IS NULL OR d.current_version_id<>s.version_id "
                +"THEN 'UPDATED' ELSE 'CURRENT' END availability FROM kb_qa_source s LEFT JOIN kb_document d ON d.id=s.document_id WHERE s.qa_id=? ORDER BY s.id",
                (r,n)->new Source(r.getLong("id"),r.getString("evidence_label"),r.getLong("document_id"),r.getLong("version_id"),r.getInt("version_number"),r.getLong("chunk_id"),
                        r.getString("document_title"),(Integer)r.getObject("page_no"),r.getInt("chunk_index"),r.getString("excerpt"),r.getString("availability")),id);
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Source source(long qaId,long sourceId) {
        owned(qaId);CourseInputs.id(sourceId);return sources(qaId).stream().filter(s->s.sourceId()==sourceId).findFirst().orElseThrow(ApiException::notFound);
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public PageResult<Summary> history(long page,long pageSize,String status) {
        PageResult.validate(page,pageSize);
        if(status!=null && !Set.of("SUCCESS","NO_EVIDENCE","FAILED").contains(status)) throw ApiException.invalid();
        String where=" WHERE user_id=?"+(status==null?"":" AND status=?");var args=new ArrayList<Object>();args.add(UserContext.currentUserId());if(status!=null) args.add(status);
        long total=jdbc.queryForObject("SELECT COUNT(*) FROM kb_qa_record"+where,Long.class,args.toArray());args.add(pageSize);args.add((page-1)*pageSize);
        var items=jdbc.query("SELECT *,EXISTS(SELECT 1 FROM kb_favorite f WHERE f.user_id=kb_qa_record.user_id AND f.qa_id=kb_qa_record.id) favorited FROM kb_qa_record"+where+" ORDER BY created_at DESC,id DESC LIMIT ? OFFSET ?",(r,n)->summary(r,r.getBoolean("favorited")),args.toArray());
        return new PageResult<>(items,total,page,pageSize);
    }
    static Summary summary(ResultSet r,boolean favorited) throws SQLException {
        String answer=r.getString("answer");
        if(answer!=null && answer.codePointCount(0,answer.length())>200) answer=answer.substring(0,answer.offsetByCodePoints(0,200));
        return new Summary(r.getLong("id"),r.getString("question"),r.getString("status"),answer,r.getLong("latency_ms"),r.getString("error_code"),r.getString("error_message"),time(r.getTimestamp("created_at")),favorited);
    }
    static String time(Object value) {return value instanceof Timestamp timestamp?timestamp.toLocalDateTime().atZone(ZoneId.of("Asia/Shanghai")).toOffsetDateTime().toString():null;}
    private static String string(Object value) {
        if(value==null) return null;
        if(value instanceof Clob clob) try {return clob.getSubString(1,(int)clob.length());}catch(SQLException e){throw new IllegalStateException("Cannot read stored answer");}
        return value.toString();
    }
}
