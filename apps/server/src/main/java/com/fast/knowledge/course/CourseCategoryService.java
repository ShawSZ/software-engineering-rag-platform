package com.fast.knowledge.course;

import com.fast.knowledge.common.ApiException;
import com.fast.knowledge.model.entity.KbCategory;
import com.fast.knowledge.mapper.CategoryMapper;
import org.springframework.dao.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;

@Service
public class CourseCategoryService {
    private final JdbcTemplate jdbc;
    private final CategoryMapper categories;
    public CourseCategoryService(JdbcTemplate jdbc, CategoryMapper categories) { this.jdbc=jdbc; this.categories=categories; }
    public List<KbCategory> list() {
        return jdbc.query("SELECT id,name,sort_order FROM kb_category ORDER BY sort_order,id", (r,n)-> {
            KbCategory c=new KbCategory(); c.setId(r.getLong(1)); c.setName(r.getString(2)); c.setSortOrder(r.getInt(3)); return c;
        });
    }
    @Transactional
    public KbCategory save(Long id, String name, Integer order) {
        String normalized=CourseInputs.text(name,50);
        if(order==null || order<0) throw ApiException.invalid();
        if(id!=null) lock(id);
        KbCategory c=new KbCategory();c.setId(id);c.setName(normalized);c.setSortOrder(order);
        try { if(id==null) categories.insert(c); else categories.updateById(c); }
        catch(DuplicateKeyException e) {throw new ApiException(40902,"分类名称已存在");}
        return c;
    }
    @Transactional
    public void delete(long id) {
        lock(id);
        if(jdbc.queryForObject("SELECT COUNT(*) FROM kb_document WHERE category_id=?",Long.class,id)>0)
            throw new ApiException(40903,"分类仍被资料引用");
        try {categories.deleteById(id);}
        catch(DataIntegrityViolationException e) {throw new ApiException(40903,"分类仍被资料引用");}
    }
    void lock(long id) {
        CourseInputs.id(id);
        if(jdbc.query("SELECT id FROM kb_category WHERE id=? FOR UPDATE",(r,n)->r.getLong(1),id).isEmpty())
            throw ApiException.notFound();
    }
}
