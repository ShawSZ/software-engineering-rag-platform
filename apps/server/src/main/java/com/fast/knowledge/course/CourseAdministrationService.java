package com.fast.knowledge.course;

import com.fast.knowledge.common.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.util.*;

@Service
public class CourseAdministrationService {
    private final JdbcTemplate jdbc;
    public CourseAdministrationService(JdbcTemplate jdbc) {this.jdbc=jdbc;}
    public record AdminFeedback(long id,long qaId,String rating,String reason,String updatedAt,long userId,String username,String question) { }
    public record Stats(String from,String to,long documentCount,long userCount,long qaSuccessCount,long qaNoEvidenceCount,long qaFailedCount,
            long feedbackHelpfulCount,long feedbackUnhelpfulCount) { }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public PageResult<AdminFeedback> feedback(long page,long pageSize,String rating,String from,String to) {
        PageResult.validate(page,pageSize);var dates=CourseDates.parse(from,to);
        if(rating!=null && !Set.of("HELPFUL","UNHELPFUL").contains(rating)) throw ApiException.invalid();
        String where=" FROM kb_feedback f JOIN kb_qa_record q ON q.id=f.qa_id AND q.user_id=f.user_id JOIN kb_user u ON u.id=f.user_id WHERE 1=1"
                +dates.sql("f.updated_at")+(rating==null?"":" AND f.rating=?");
        var args=dates.arguments();if(rating!=null) args.add(rating);
        long total=jdbc.queryForObject("SELECT COUNT(*)"+where,Long.class,args.toArray());args.add(pageSize);args.add((page-1)*pageSize);
        var rows=jdbc.query("SELECT f.*,q.question,u.username"+where+" ORDER BY f.updated_at DESC,f.id DESC LIMIT ? OFFSET ?",(r,n)->new AdminFeedback(r.getLong("id"),r.getLong("qa_id"),r.getString("rating"),r.getString("reason"),
                CourseQaStore.time(r.getTimestamp("updated_at")),r.getLong("user_id"),r.getString("username"),r.getString("question")),args.toArray());
        return new PageResult<>(rows,total,page,pageSize);
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Stats stats(String from,String to) {
        var dates=CourseDates.parse(from,to);var args=dates.arguments().toArray();
        long documents=jdbc.queryForObject("SELECT COUNT(*) FROM kb_document WHERE kb_id=0"+dates.sql("created_at"),Long.class,args);
        long users=jdbc.queryForObject("SELECT COUNT(*) FROM kb_user WHERE 1=1"+dates.sql("created_at"),Long.class,args);
        var qa=jdbc.queryForMap("SELECT COUNT(CASE WHEN status='SUCCESS' THEN 1 END) success,COUNT(CASE WHEN status='NO_EVIDENCE' THEN 1 END) no_evidence,COUNT(CASE WHEN status='FAILED' THEN 1 END) failed FROM kb_qa_record WHERE 1=1"+dates.sql("created_at"),args);
        var feedback=jdbc.queryForMap("SELECT COUNT(CASE WHEN rating='HELPFUL' THEN 1 END) helpful,COUNT(CASE WHEN rating='UNHELPFUL' THEN 1 END) unhelpful FROM kb_feedback WHERE 1=1"+dates.sql("updated_at"),args);
        return new Stats(from,to,documents,users,number(qa,"success"),number(qa,"no_evidence"),number(qa,"failed"),number(feedback,"helpful"),number(feedback,"unhelpful"));
    }
    private long number(Map<String,Object> row,String name) {return ((Number)row.get(name)).longValue();}
}
