package com.fast.knowledge.common;

import org.slf4j.MDC;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import java.util.UUID;

public final class RequestIds {
    public static final String ATTRIBUTE = RequestIds.class.getName();
    private RequestIds() { }
    public static String current() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs) {
            Object id = attrs.getRequest().getAttribute(ATTRIBUTE);
            if (id instanceof String value) return value;
        }
        String id = MDC.get("requestId");
        return id == null ? UUID.randomUUID().toString() : id;
    }
}
