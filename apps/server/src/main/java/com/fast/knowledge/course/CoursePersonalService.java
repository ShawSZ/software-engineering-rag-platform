package com.fast.knowledge.course;

import com.fast.knowledge.common.*;
import com.fast.knowledge.security.UserContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.time.*;
import java.sql.Timestamp;
import java.util.*;

@Service
public class CoursePersonalService {
    private final JdbcTemplate jdbc;
    public CoursePersonalService(JdbcTemplate jdbc) {this.jdbc=jdbc;}
    public record Favorite(long qaId,boolean favorited) { }
    public record Feedback(long id,long qaId,String rating,String reason,String updatedAt) { }
    @Transactional
    public Favorite favorite(long qaId,boolean enabled) {
        String state=lockOwned(qaId);
        if(enabled && !"SUCCESS".equals(state)) throw ApiException.invalid();
        long user=UserContext.currentUserId();
        if(enabled) jdbc.update("INSERT INTO kb_favorite(user_id,qa_id) SELECT ?,? WHERE NOT EXISTS (SELECT 1 FROM kb_favorite WHERE user_id=? AND qa_id=?)",user,qaId,user,qaId);
        else jdbc.update("DELETE FROM kb_favorite WHERE user_id=? AND qa_id=?",user,qaId);
        return new Favorite(qaId,enabled);
    }
    @Transactional
    public Feedback feedback(long qaId,String rating,String reason) {
        if(!Set.of("HELPFUL","UNHELPFUL").contains(rating==null?"":rating) || reason==null) throw ApiException.invalid();
        CourseInputs.optionalText(reason,500);
        if(!"SUCCESS".equals(lockOwned(qaId))) throw ApiException.invalid();
        long user=UserContext.currentUserId();Timestamp now=Timestamp.valueOf(LocalDateTime.now(ZoneId.of("Asia/Shanghai")));
        int changed=jdbc.update("UPDATE kb_feedback SET rating=?,reason=?,updated_at=? WHERE user_id=? AND qa_id=?",rating,reason,now,user,qaId);
        if(changed==0) jdbc.update("INSERT INTO kb_feedback(user_id,qa_id,rating,reason,created_at,updated_at) VALUES (?,?,?,?,?,?)",user,qaId,rating,reason,now,now);
        return feedbackFor(user,qaId);
    }
    private String lockOwned(long qaId) {
        CourseInputs.id(qaId);
        var states=jdbc.query("SELECT status FROM kb_qa_record WHERE id=? AND user_id=? FOR UPDATE",(r,n)->r.getString(1),qaId,UserContext.currentUserId());
        if(states.isEmpty()) throw ApiException.notFound();return states.getFirst();
    }
    public boolean isFavorite(long user,long qaId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM kb_favorite WHERE user_id=? AND qa_id=?",Integer.class,user,qaId)>0;
    }
    public Feedback feedbackFor(long user,long qaId) {
        var rows=jdbc.query("SELECT * FROM kb_feedback WHERE user_id=? AND qa_id=?",(r,n)->new Feedback(r.getLong("id"),r.getLong("qa_id"),r.getString("rating"),r.getString("reason"),CourseQaStore.time(r.getTimestamp("updated_at"))),user,qaId);
        return rows.isEmpty()?null:rows.getFirst();
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public PageResult<CourseQaStore.Summary> favorites(long page,long pageSize) {
        PageResult.validate(page,pageSize);long user=UserContext.currentUserId();
        String join=" FROM kb_favorite f JOIN kb_qa_record q ON q.id=f.qa_id WHERE f.user_id=? AND q.user_id=? AND q.status='SUCCESS'";
        long total=jdbc.queryForObject("SELECT COUNT(*)"+join,Long.class,user,user);
        var rows=jdbc.query("SELECT q.*"+join+" ORDER BY f.created_at DESC,f.id DESC LIMIT ? OFFSET ?",(r,n)->CourseQaStore.summary(r,true),user,user,pageSize,(page-1)*pageSize);
        return new PageResult<>(rows,total,page,pageSize);
    }
}
