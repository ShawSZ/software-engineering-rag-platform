package com.fast.knowledge.course;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fast.knowledge.common.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import java.util.Set;

@RestController
public class CourseQaController {
    private final CourseQaService qa;private final CourseQaStore store;
    public CourseQaController(CourseQaService qa,CourseQaStore store) {this.qa=qa;this.store=store;}
    public record Ask(@JsonDeserialize(using=AccountRequests.Text.class) String question,
            @JsonDeserialize(using=CourseDocumentController.PositiveId.class) Long parentQaId) { }
    @PostMapping("/qa")
    public ApiResponse<CourseQaStore.Detail> ask(@RequestBody Ask body,HttpServletRequest request) {
        CourseAuthController.query(request,Set.of());return ApiResponse.ok(qa.ask(body.question(),body.parentQaId()));
    }
    @GetMapping("/qa/history")
    public ApiResponse<PageResult<CourseQaStore.Summary>> history(@RequestParam(defaultValue="1") long page,@RequestParam(defaultValue="10") long pageSize,
            @RequestParam(required=false) String status,HttpServletRequest request) {
        CourseAuthController.query(request,Set.of("page","pageSize","status"));return ApiResponse.ok(store.history(page,pageSize,status));
    }
    @GetMapping("/qa/{qaId}")
    public ApiResponse<CourseQaStore.Detail> detail(@PathVariable long qaId,HttpServletRequest request) {
        CourseAuthController.query(request,Set.of());return ApiResponse.ok(store.detail(qaId));
    }
    @GetMapping("/qa/{qaId}/sources/{sourceId}")
    public ApiResponse<CourseQaStore.Source> source(@PathVariable long qaId,@PathVariable long sourceId,HttpServletRequest request) {
        CourseAuthController.query(request,Set.of());return ApiResponse.ok(store.source(qaId,sourceId));
    }
    @GetMapping("/qa/{qaId}/conversation")
    public ApiResponse<java.util.List<CourseQaStore.Detail>> conversation(@PathVariable long qaId,HttpServletRequest request) {
        CourseAuthController.query(request,Set.of());return ApiResponse.ok(store.conversation(qaId));
    }
}
