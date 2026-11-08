package com.fast.knowledge.mapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fast.knowledge.model.entity.KbCategory;
import org.apache.ibatis.annotations.Mapper;
@Mapper
public interface CategoryMapper extends BaseMapper<KbCategory> { }
