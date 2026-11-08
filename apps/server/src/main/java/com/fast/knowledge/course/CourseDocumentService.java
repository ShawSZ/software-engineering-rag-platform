package com.fast.knowledge.course;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fast.knowledge.common.*;
import com.fast.knowledge.mapper.*;
import com.fast.knowledge.model.entity.*;
import com.fast.knowledge.security.UserContext;
import com.fast.knowledge.storage.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.io.IOException;
import java.time.*;
import java.util.*;

/** Course queries only expose current READY content; failed/pending metadata is admin-only. */
@Service
public class CourseDocumentService {
    private final DocumentMapper documents;
    private final DocumentVersionMapper versions;
    private final StorageProvider storage;
    private final JdbcTemplate jdbc;
    private final CourseFileParser parser;
    private final CourseDocumentWriter writer;
    public CourseDocumentService(DocumentMapper documents, DocumentVersionMapper versions, StorageProvider storage, JdbcTemplate jdbc,
            CourseFileParser parser,CourseDocumentWriter writer) {
        this.documents = documents; this.versions = versions; this.storage = storage; this.jdbc = jdbc;
        this.parser=parser;this.writer=writer;
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public PageResult<DocumentResult> list(long page, long pageSize, Long categoryId, String title, String keyword, boolean admin,String status) {
        PageResult.validate(page, pageSize);
        if(categoryId!=null) CourseInputs.id(categoryId);
        CourseInputs.optionalText(title,200);CourseInputs.optionalText(keyword,100);
        if(status!=null && (!admin || !Set.of("ACTIVE","OFFLINE").contains(status))) throw ApiException.invalid();
        var query = Wrappers.<KbDocument>lambdaQuery().eq(KbDocument::getKbId, 0L)
                .eq(categoryId != null, KbDocument::getCategoryId, categoryId)
                .like(title != null && !title.isBlank(), KbDocument::getTitle, title == null ? null : title.strip());
        if(status!=null) query.eq(KbDocument::getEnabled,"ACTIVE".equals(status)?1:0);
        if (!admin) query.eq(KbDocument::getEnabled, 1).isNotNull(KbDocument::getCurrentVersionId)
                .apply("EXISTS (SELECT 1 FROM kb_document_version v WHERE v.id=kb_document.current_version_id AND v.document_id=kb_document.id AND v.status='READY')");
        if (keyword != null && !keyword.isBlank()) {
            String term = keyword.strip();
            query.and(q -> q.like(KbDocument::getTitle, term).or().apply(
                    "EXISTS (SELECT 1 FROM kb_document_chunk c JOIN kb_document_version v ON v.id=c.version_id WHERE c.document_id = kb_document.id AND v.document_id=kb_document.id AND v.status='READY' AND c.version_id = kb_document.current_version_id AND c.content LIKE {0})", "%" + term + "%"));
        }
        query.orderByDesc(KbDocument::getCreatedAt).orderByDesc(KbDocument::getId);
        var result = documents.selectPage(new Page<>(page, pageSize), query);
        return new PageResult<>(result.getRecords().stream().map(this::view).toList(), result.getTotal(), page, pageSize);
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public DocumentResult detail(long id) { return view(require(id, false)); }
    public DocumentResult upload(MultipartFile file, String title, long categoryId) throws IOException {
        String normalizedTitle=CourseInputs.text(title,200);CourseInputs.id(categoryId);
        var validated=parser.validate(file);
        StoredObject stored=storage.storeUpload(0L,file);
        CourseDocumentWriter.Created created;
        try {created=writer.create(stored,validated,normalizedTitle,categoryId);}
        catch(RuntimeException e) {
            try {storage.delete(stored.absolutePath());} catch(IOException cleanup) {e.addSuppressed(cleanup);}
            throw e;
        }
        var doc=created.document();
        // Return the accepted snapshot even if the async worker has already progressed.
        return new DocumentResult(doc.getId(),doc.getTitle(),categoryId,created.categoryName(),"ACTIVE",versionView(created.currentVersion()),
                versionView(created.version()),timestamp(doc.getCreatedAt()),timestamp(doc.getUpdatedAt()));
    }
    public DocumentResult replace(long id,MultipartFile file) throws IOException {
        CourseInputs.id(id);var validated=parser.validate(file);StoredObject stored=storage.storeUpload(0L,file);
        CourseDocumentWriter.Created created;
        try {created=writer.replace(id,stored,validated);}
        catch(RuntimeException e) {try {storage.delete(stored.absolutePath());}catch(IOException cleanup){e.addSuppressed(cleanup);}throw e;}
        var doc=created.document();
        return new DocumentResult(doc.getId(),doc.getTitle(),doc.getCategoryId(),created.categoryName(),"ACTIVE",
                versionView(created.currentVersion()),versionView(created.version()),timestamp(doc.getCreatedAt()),timestamp(doc.getUpdatedAt()));
    }
    public DocumentResult metadata(long id,String title,Long categoryId) {
        CourseInputs.id(id);if(categoryId==null) throw ApiException.invalid();CourseInputs.id(categoryId);
        return detail(writer.metadata(id,CourseInputs.text(title,200),categoryId));
    }
    public void offline(long id) {writer.offline(id);}
    public record DocumentStatus(Long documentId,String status,VersionResult currentVersion,VersionResult latestVersion) { }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public DocumentStatus status(long id) {
        var doc=detail(id);return new DocumentStatus(doc.id(),doc.status(),doc.currentVersion(),doc.latestVersion());
    }
    public record ChunkResult(Long chunkId,Long versionId,Integer pageNo,int chunkIndex,String content) { }
    public PageResult<ChunkResult> chunks(long id,long page,long pageSize,Integer chunkIndex) {
        PageResult.validate(page,pageSize);if(chunkIndex!=null && chunkIndex<1) throw ApiException.invalid();
        var doc=require(id,true);
        String where=" WHERE document_id=? AND version_id=?"+(chunkIndex==null?"":" AND chunk_index=?");
        List<Object> args=new ArrayList<>(List.of(id,doc.getCurrentVersionId()));if(chunkIndex!=null) args.add(chunkIndex);
        long total=jdbc.queryForObject("SELECT COUNT(*) FROM kb_document_chunk"+where,Long.class,args.toArray());
        args.add(pageSize);args.add((page-1)*pageSize);
        var rows=jdbc.query("SELECT id,version_id,page_no,chunk_index,content FROM kb_document_chunk"+where+" ORDER BY chunk_index LIMIT ? OFFSET ?",
                (r,n)->new ChunkResult(r.getLong(1),r.getLong(2),(Integer)r.getObject(3),r.getInt(4),r.getString(5)),args.toArray());
        return new PageResult<>(rows,total,page,pageSize);
    }
    public Download download(long id) throws IOException {
        KbDocument doc = require(id, true);
        DocumentVersion version = versions.selectById(doc.getCurrentVersionId());
        if (version == null || !"READY".equals(version.getStatus()) || !doc.getId().equals(version.getDocumentId()))
            throw ApiException.notFound();
        // Read before committing headers so a missing/unreadable file still produces a JSON error.
        try (var in = storage.openInputStream(version.getObjectKey())) {
            return new Download(version.getOriginalFilename(), version.getFileType(), in.readAllBytes());
        } catch (java.nio.file.NoSuchFileException | java.io.FileNotFoundException e) { throw new ApiException(40402, "原文件缺失，请联系管理员"); }
    }
    private KbDocument require(long id, boolean download) {
        if (id < 1 || id > 9007199254740991L) throw ApiException.invalid();
        KbDocument doc = documents.selectById(id);
        if (doc == null || !Long.valueOf(0).equals(doc.getKbId())) throw ApiException.notFound();
        if (download || !"ADMIN".equals(UserContext.get().getRole())) {
            if (!Integer.valueOf(1).equals(doc.getEnabled()) || doc.getCurrentVersionId() == null) throw ApiException.notFound();
            var version = versions.selectById(doc.getCurrentVersionId());
            if (version == null || !"READY".equals(version.getStatus()) || !doc.getId().equals(version.getDocumentId())) throw ApiException.notFound();
        }
        return doc;
    }
    private DocumentResult view(KbDocument doc) {
        var all = versions.selectList(Wrappers.<DocumentVersion>lambdaQuery().eq(DocumentVersion::getDocumentId, doc.getId())
                .orderByDesc(DocumentVersion::getVersionNumber));
        var current = all.stream().filter(v -> v.getId().equals(doc.getCurrentVersionId())).findFirst().orElse(null);
        String category = jdbc.queryForObject("SELECT name FROM kb_category WHERE id = ?", String.class, doc.getCategoryId());
        return new DocumentResult(doc.getId(), doc.getTitle(), doc.getCategoryId(), category,
                doc.getEnabled() == 1 ? "ACTIVE" : "OFFLINE", versionView(current),
                all.isEmpty() ? null : versionView(all.getFirst()), timestamp(doc.getCreatedAt()), timestamp(doc.getUpdatedAt()));
    }
    private VersionResult versionView(DocumentVersion v) {
        return v == null ? null : new VersionResult(v.getId(), v.getVersionNumber(), v.getStatus(), v.getOriginalFilename(),
                v.getFileType(), v.getFileSize(), v.getChunkCount(), v.getErrorCode(), v.getErrorMessage(), timestamp(v.getCreatedAt()));
    }
    private String timestamp(LocalDateTime date) { return date == null ? null : date.atZone(ZoneId.of("Asia/Shanghai")).toOffsetDateTime().toString(); }
    public record Download(String filename, String type, byte[] bytes) { }
    public record VersionResult(Long id, Integer versionNumber, String status, String originalFilename, String fileType,
            Long fileSize, Integer chunkCount, String errorCode, String errorMessage, String createdAt) { }
    public record DocumentResult(Long id, String title, Long categoryId, String categoryName, String status,
            VersionResult currentVersion, VersionResult latestVersion, String createdAt, String updatedAt) { }
}
