package com.fast.knowledge.model.entity;
import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
@Data
@TableName("kb_category")
public class KbCategory {
    @TableId(type = IdType.AUTO) private Long id;
    private String name;
    private Integer sortOrder;
}
