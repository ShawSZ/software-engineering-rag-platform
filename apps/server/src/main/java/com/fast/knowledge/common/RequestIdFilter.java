package com.fast.knowledge.common;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.util.UUID;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {
    @Override
    protected boolean shouldNotFilterErrorDispatch() { return false; }
    @Override
    protected boolean shouldNotFilterAsyncDispatch() { return false; }
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String id = (String) request.getAttribute(RequestIds.ATTRIBUTE);
        if (id == null) {
            id = UUID.randomUUID().toString();
            request.setAttribute(RequestIds.ATTRIBUTE, id);
        }
        response.setHeader("X-Request-Id", id);
        String previous = MDC.get("requestId");
        MDC.put("requestId", id);
        try { chain.doFilter(request, response); }
        finally {
            if (previous == null) MDC.remove("requestId");
            else MDC.put("requestId", previous);
        }
    }
}
