package com.fast.knowledge.course;

import com.fast.knowledge.common.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.util.Set;

@RestController
@RequestMapping("/admin/users")
@PreAuthorize("hasRole('ADMIN')")
public class CourseUserController {
    private final CourseAccountService accounts;
    public CourseUserController(CourseAccountService accounts) { this.accounts = accounts; }
    @GetMapping
    public ApiResponse<PageResult<AccountUser>> list(@RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "10") long pageSize, @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String status, HttpServletRequest request) {
        CourseAuthController.query(request, Set.of("page", "pageSize", "keyword", "status"));
        return ApiResponse.ok(accounts.list(page, pageSize, keyword, status));
    }
    @PatchMapping("/{userId}/status")
    public ApiResponse<AccountUser> status(@PathVariable long userId, @RequestBody AccountRequests.Status body,
            HttpServletRequest request) {
        CourseAuthController.query(request, Set.of());
        return ApiResponse.ok(accounts.changeStatus(userId, body.status()));
    }
}
