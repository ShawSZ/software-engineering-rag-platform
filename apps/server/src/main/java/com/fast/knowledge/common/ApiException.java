package com.fast.knowledge.common;

/** Public messages must never be populated from infrastructure exception text. */
public class ApiException extends BusinessException {
    public ApiException(int code, String message) { super(code, message); }
    public static ApiException invalid() { return new ApiException(42201, "请求参数不符合要求"); }
    public static ApiException notFound() { return new ApiException(40401, "资源不存在"); }
}
