package com.fast.knowledge.mapper;

import com.fast.knowledge.model.entity.KbUser;
import org.apache.ibatis.annotations.*;
import java.util.List;

@Mapper
public interface AccountGuardMapper {
    @Select("SELECT id FROM kb_account_guard WHERE id = 1 FOR UPDATE")
    Integer lockGuard();
    @Select("SELECT * FROM kb_user WHERE role = 'ADMIN' ORDER BY id FOR UPDATE")
    List<KbUser> lockAdministrators();
    @Select("SELECT * FROM kb_user WHERE id = #{id} FOR UPDATE")
    KbUser lockUser(@Param("id") Long id);
}
