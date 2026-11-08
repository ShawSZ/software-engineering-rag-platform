package com.fast.knowledge.course;

import com.auth0.jwt.exceptions.JWTVerificationException;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.fast.knowledge.mapper.UserMapper;
import com.fast.knowledge.security.*;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.util.List;

@Component
public class CourseJwtFilter extends OncePerRequestFilter {
    private final JwtUtil jwt;
    private final TokenBlacklistService blacklist;
    private final UserMapper users;
    private final SecurityProblemHandler errors;
    public CourseJwtFilter(JwtUtil jwt, TokenBlacklistService blacklist, UserMapper users, SecurityProblemHandler errors) {
        this.jwt = jwt; this.blacklist = blacklist; this.users = users; this.errors = errors;
    }
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getPathInfo();
        return ("POST".equals(request.getMethod()) && ("/auth/login".equals(path) || "/auth/register".equals(path)))
                || "/actuator/health".equals(path);
    }
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        SecurityContextHolder.clearContext(); UserContext.clear();
        try {
            DecodedJWT decoded = decode(request.getHeader("Authorization"));
            if (decoded != null) {
                try {
                    if (!blacklist.isBlacklisted(decoded.getToken())) {
                        var user = users.selectById(decoded.getClaim("userId").asLong());
                        if (user != null && !Integer.valueOf(1).equals(user.getStatus())) {
                            errors.write(response, 401, 40103, "账号已停用，请重新登录"); return;
                        }
                        Long tokenVersion = decoded.getClaim("authVersion").asLong();
                        if (tokenVersion == null) tokenVersion = 0L; // Existing pre-B02 sessions use version zero.
                        if (user != null && CourseAccountService.allowedRole(user.getRole())
                                && tokenVersion.equals(user.getAuthVersion())) {
                            var principal = new AuthenticatedUser(user.getId(), user.getUsername(), user.getRole());
                            SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(principal,
                                    decoded.getToken(), List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole()))));
                            UserContext ctx = new UserContext();
                            ctx.setUserId(user.getId()); ctx.setUsername(user.getUsername()); ctx.setRole(user.getRole());
                            UserContext.set(ctx);
                        }
                    }
                } catch (Exception e) {
                    errors.write(response, 500, 50001, "身份服务暂时不可用，请稍后重试"); return;
                }
            }
            chain.doFilter(request, response);
        } finally {
            UserContext.clear(); SecurityContextHolder.clearContext();
        }
    }
    private DecodedJWT decode(String header) {
        if (header == null || !header.startsWith("Bearer ")) return null;
        try {
            DecodedJWT decoded = jwt.verify(header.substring(7));
            Long id = decoded.getClaim("userId").asLong();
            Long version = decoded.getClaim("authVersion").asLong();
            if (id == null || id < 1 || id > 9007199254740991L || decoded.getExpiresAt() == null
                    || (version != null && version < 0)) return null;
            return decoded;
        } catch (JWTVerificationException | IllegalArgumentException e) {
            return null;
        }
    }
}
