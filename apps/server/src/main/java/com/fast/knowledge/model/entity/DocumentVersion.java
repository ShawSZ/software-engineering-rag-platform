package com.fast.knowledge.model.entity;
import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;
@Data
@TableName("kb_document_version")
public class DocumentVersion {
    @TableId(type = IdType.AUTO) private Long id;
    private Long documentId;
    private Integer versionNumber;
    private String status;
    private String objectKey;
    private String originalFilename;
    private String fileType;
    private Long fileSize;
    private String sha256;
    private Integer chunkCount;
    private String errorCode;
    private String errorMessage;
    @TableField(fill = FieldFill.INSERT) private LocalDateTime createdAt;
}
