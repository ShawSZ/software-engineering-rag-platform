package com.fast.knowledge.course;

import com.fasterxml.jackson.annotation.*;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fast.knowledge.common.*;
import com.fast.knowledge.model.entity.KbCategory;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.util.*;
import java.io.IOException;

@RestController
@RequestMapping("/categories")
public class CourseCategoryController {
    private final CourseCategoryService categories;
    public CourseCategoryController(CourseCategoryService categories) { this.categories = categories; }
    @GetMapping
    public ApiResponse<List<KbCategory>> list(HttpServletRequest request) {
        CourseAuthController.query(request,Set.of());return ApiResponse.ok(categories.list());
    }
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<KbCategory> create(@RequestBody CreateCategory body,HttpServletRequest request) {
        CourseAuthController.query(request,Set.of());return ApiResponse.ok(categories.save(null,body.name,body.sortOrder));
    }
    @PatchMapping("/{id}") @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<KbCategory> update(@PathVariable long id,@RequestBody EditCategory body,HttpServletRequest request) {
        CourseAuthController.query(request,Set.of());return ApiResponse.ok(categories.save(id,body.name(),body.sortOrder()));
    }
    @DeleteMapping("/{id}") @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<Void> delete(@PathVariable long id,HttpServletRequest request) throws IOException {
        CourseAuthController.query(request,Set.of());
        if(request.getInputStream().read()!=-1) throw ApiException.invalid();
        categories.delete(id);return ApiResponse.ok();
    }
    public static final class CreateCategory {
        @JsonProperty @JsonDeserialize(using=AccountRequests.Text.class) private String name;
        @JsonProperty @JsonDeserialize(using=CourseInputs.NonNegativeInteger.class) private Integer sortOrder=0;
        @JsonSetter(value="sortOrder",nulls=Nulls.FAIL) public void setSortOrder(Integer value) {sortOrder=value;}
    }
    public record EditCategory(@JsonDeserialize(using=AccountRequests.Text.class) String name,
            @JsonDeserialize(using=CourseInputs.NonNegativeInteger.class) Integer sortOrder) { }
}
