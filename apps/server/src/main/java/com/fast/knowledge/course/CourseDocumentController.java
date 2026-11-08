package com.fast.knowledge.course;

import com.fast.knowledge.common.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

@RestController
public class CourseDocumentController {
    private final CourseDocumentService documents;
    public CourseDocumentController(CourseDocumentService documents) { this.documents = documents; }
    @GetMapping("/documents")
    public ApiResponse<PageResult<CourseDocumentService.DocumentResult>> list(
            @RequestParam(defaultValue = "1") long page, @RequestParam(defaultValue = "10") long pageSize,
            @RequestParam(required = false) Long categoryId, @RequestParam(required = false) String title,
            @RequestParam(required = false) String keyword, HttpServletRequest request) {
        query(request, Set.of("page", "pageSize", "categoryId", "title", "keyword"));
        return ApiResponse.ok(documents.list(page, pageSize, categoryId, title, keyword, false,null));
    }
    @GetMapping("/admin/documents")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<PageResult<CourseDocumentService.DocumentResult>> adminList(
            @RequestParam(defaultValue = "1") long page, @RequestParam(defaultValue = "10") long pageSize,
            @RequestParam(required = false) Long categoryId, @RequestParam(required = false) String title,
            @RequestParam(required = false) String keyword,@RequestParam(required=false) String status,HttpServletRequest request) {
        query(request, Set.of("page", "pageSize", "categoryId", "title", "keyword","status"));
        return ApiResponse.ok(documents.list(page, pageSize, categoryId, title, keyword, true,status));
    }
    @PostMapping("/documents")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<CourseDocumentService.DocumentResult> upload(@RequestParam MultipartFile file,
            @RequestParam String title, @RequestParam long categoryId, HttpServletRequest request) throws IOException {
        query(request, Set.of("title", "categoryId"));
        if (request instanceof org.springframework.web.multipart.MultipartHttpServletRequest multipart
                && (!multipart.getMultiFileMap().keySet().equals(Set.of("file"))
                || multipart.getMultiFileMap().get("file").size()!=1)) throw ApiException.invalid();
        return ApiResponse.ok(documents.upload(file, title, categoryId));
    }
    @GetMapping("/documents/{id}")
    public ApiResponse<CourseDocumentService.DocumentResult> detail(@PathVariable long id,HttpServletRequest request) {
        query(request,Set.of());
        return ApiResponse.ok(documents.detail(id));
    }
    @PutMapping("/documents/{id}/file") @ResponseStatus(HttpStatus.ACCEPTED) @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<CourseDocumentService.DocumentResult> replace(@PathVariable long id,@RequestParam MultipartFile file,
            HttpServletRequest request) throws IOException {
        query(request,Set.of());
        if(request instanceof org.springframework.web.multipart.MultipartHttpServletRequest multipart
                && (!multipart.getMultiFileMap().keySet().equals(Set.of("file")) || multipart.getMultiFileMap().get("file").size()!=1))
            throw ApiException.invalid();
        return ApiResponse.ok(documents.replace(id,file));
    }
    public record Metadata(@JsonDeserialize(using=AccountRequests.Text.class) String title,
            @JsonDeserialize(using=PositiveId.class) Long categoryId) { }
    public static class PositiveId extends com.fasterxml.jackson.databind.JsonDeserializer<Long> {
        @Override public Long deserialize(com.fasterxml.jackson.core.JsonParser parser,com.fasterxml.jackson.databind.DeserializationContext context) throws IOException {
            if(!parser.hasToken(com.fasterxml.jackson.core.JsonToken.VALUE_NUMBER_INT) || parser.getBigIntegerValue().signum()<1
                    || parser.getBigIntegerValue().compareTo(java.math.BigInteger.valueOf(9007199254740991L))>0)
                return (Long)context.handleUnexpectedToken(Long.class,parser);
            return parser.getLongValue();
        }
    }
    @PatchMapping("/documents/{id}") @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<CourseDocumentService.DocumentResult> metadata(@PathVariable long id,@RequestBody Metadata body,HttpServletRequest request) {
        query(request,Set.of());return ApiResponse.ok(documents.metadata(id,body.title(),body.categoryId()));
    }
    @DeleteMapping("/documents/{id}") @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<Void> offline(@PathVariable long id,HttpServletRequest request) throws IOException {
        query(request,Set.of());if(request.getInputStream().read()!=-1) throw ApiException.invalid();
        documents.offline(id);return ApiResponse.ok();
    }
    @GetMapping("/documents/{id}/status")
    public ApiResponse<CourseDocumentService.DocumentStatus> status(@PathVariable long id,HttpServletRequest request) {
        query(request,Set.of());return ApiResponse.ok(documents.status(id));
    }
    @GetMapping("/documents/{id}/chunks")
    public ApiResponse<PageResult<CourseDocumentService.ChunkResult>> chunks(@PathVariable long id,
            @RequestParam(defaultValue="1") long page,@RequestParam(defaultValue="10") long pageSize,
            @RequestParam(required=false) Integer chunkIndex,HttpServletRequest request) {
        query(request,Set.of("page","pageSize","chunkIndex"));return ApiResponse.ok(documents.chunks(id,page,pageSize,chunkIndex));
    }
    @GetMapping("/documents/{id}/download")
    public ResponseEntity<byte[]> download(@PathVariable long id,HttpServletRequest request) throws IOException {
        query(request,Set.of());
        var file = documents.download(id);
        return ResponseEntity.ok().contentType("TXT".equals(file.type()) ? new MediaType("text","plain",StandardCharsets.UTF_8) : MediaType.parseMediaType(CourseFileParser.mime(file.type())))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(file.filename(), StandardCharsets.UTF_8).build().toString())
                .header("X-Content-Type-Options", "nosniff").body(file.bytes());
    }
    private void query(HttpServletRequest request, Set<String> allowed) {
        if (!allowed.containsAll(request.getParameterMap().keySet())
                || request.getParameterMap().values().stream().anyMatch(values -> values.length != 1)) throw ApiException.invalid();
    }
}
