package com.fast.knowledge.course;

import com.fast.knowledge.common.*;
import com.fast.knowledge.security.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import java.util.Set;
import java.io.IOException;

@RestController
@RequestMapping("/auth")
public class CourseAuthController {
    private final CourseAccountService accounts;
    private final TokenBlacklistService blacklist;
    public CourseAuthController(CourseAccountService accounts, TokenBlacklistService blacklist) {
        this.accounts = accounts; this.blacklist = blacklist;
    }
    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    @RateLimit(maxRequests = 10, windowSeconds = 60)
    public ApiResponse<AccountUser> register(@RequestBody AccountRequests.Register body, HttpServletRequest request) {
        query(request, Set.of());
        return ApiResponse.ok(accounts.register(body));
    }
    @PostMapping("/login")
    @RateLimit(maxRequests = 10, windowSeconds = 60)
    public ApiResponse<CourseAccountService.LoginResult> login(@RequestBody AccountRequests.Login body, HttpServletRequest request) {
        query(request, Set.of());
        return ApiResponse.ok(accounts.login(body));
    }
    @GetMapping("/me")
    public ApiResponse<AccountUser> me(HttpServletRequest request) {
        query(request, Set.of()); return ApiResponse.ok(accounts.me());
    }
    @PostMapping("/logout")
    public ApiResponse<Void> logout(HttpServletRequest request) throws IOException {
        query(request, Set.of());
        if (request.getInputStream().read() != -1) throw ApiException.invalid();
        blacklist.blacklist(request.getHeader("Authorization").substring(7));
        return ApiResponse.ok();
    }
    static void query(HttpServletRequest request, Set<String> allowed) {
        if (!allowed.containsAll(request.getParameterMap().keySet())
                || request.getParameterMap().values().stream().anyMatch(v -> v.length != 1)) throw ApiException.invalid();
    }
}
