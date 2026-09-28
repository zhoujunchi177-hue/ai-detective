package com.mindtrace.exception;

import com.mindtrace.common.ApiResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 全局异常处理的返回码与文案约束。
 * <p>
 * 这里钉住的是**玩家能看到的东西**：状态码与文案。这些分支正常调用走不到，
 * 只能直接构造异常来测 —— 但它们一旦写错，前端会拿错误的状态码做出错误决策
 * （例如把 403 当成「未登录」而反复跳登录页，或把 404 当成「服务器崩了」）。
 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    @DisplayName("访问不存在的路径返回 404，而不是 500")
    void missingResourceMapsToNotFound() {
        ResponseEntity<ApiResponse<Void>> response = handler.handleNoResource(
                new NoResourceFoundException(org.springframework.http.HttpMethod.GET, "/api/profile"));

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("请求的接口不存在", response.getBody().message());
    }

    @Test
    @DisplayName("兜底 handler 不把异常原文回传给客户端")
    void unknownExceptionDoesNotLeakInternalMessage() {
        String secret = "Failed to convert value of type 'java.lang.String' to 'java.lang.Long'";
        ResponseEntity<ApiResponse<Void>> response = handler.handleUnknown(new RuntimeException(secret));

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        assertNotNull(response.getBody());
        String message = response.getBody().message();
        assertFalse(message.contains("java.lang"), "兜底文案不能包含内部类型名：" + message);
        assertFalse(message.contains("Failed to convert"), "兜底文案不能包含原始异常文本：" + message);
        assertEquals("服务器处理失败，请稍后重试", message);
    }

    @Test
    @DisplayName("权限不足返回 403 且文案明确")
    void accessDeniedMapsToForbidden() {
        ResponseEntity<ApiResponse<Void>> response =
                handler.handleAccessDenied(new org.springframework.security.access.AccessDeniedException("denied"));

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("没有权限执行此操作", response.getBody().message());
    }
}
