package com.fast.knowledge.common;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.web.servlet.error.ErrorController;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ApiErrorController implements ErrorController {
    @RequestMapping("/error")
    public ResponseEntity<ApiResponse<Void>> error(HttpServletRequest request) {
        Object raw = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
        int status = raw instanceof Integer value ? value : 500;
        int code = switch (status) { case 401 -> 40101; case 403 -> 40301; case 404 -> 40401;
            case 405 -> 40501; case 413 -> 42203; default -> 50001; };
        int http = status == 413 ? 422 : (code == 50001 ? 500 : status);
        return ResponseEntity.status(http).contentType(org.springframework.http.MediaType.APPLICATION_JSON).body(ApiResponse.fail(code,
                code == 40401 ? "资源不存在" : code == 42203 ? "单文件不能超过 20 MB" : "请求未能完成"));
    }
}
