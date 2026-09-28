package com.mindtrace.exception;

import com.mindtrace.common.ApiResponse;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.BindException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.stream.Collectors;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusiness(BusinessException exception) {
        return ResponseEntity.status(exception.getStatus()).body(ApiResponse.error(exception.getMessage()));
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, BindException.class})
    public ResponseEntity<ApiResponse<Void>> handleValidation(Exception exception) {
        String message;
        if (exception instanceof MethodArgumentNotValidException methodException) {
            message = methodException.getBindingResult().getFieldErrors().stream()
                    .map(error -> error.getField() + ": " + error.getDefaultMessage())
                    .collect(Collectors.joining("; "));
        } else {
            BindException bindException = (BindException) exception;
            message = bindException.getBindingResult().getFieldErrors().stream()
                    .map(error -> error.getField() + ": " + error.getDefaultMessage())
                    .collect(Collectors.joining("; "));
        }
        return ResponseEntity.badRequest().body(ApiResponse.error(message));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiResponse<Void>> handleConstraint(ConstraintViolationException exception) {
        return ResponseEntity.badRequest().body(ApiResponse.error(exception.getMessage()));
    }

    /**
     * 路径变量 / 查询参数类型不对（例如 /api/cases/abc、?npcId=abc）。
     * <p>
     * 这类是**客户端错误**，必须回 400。此前它们落进了下面的兜底 handler，
     * 变成 500，并且把 Spring 的原始异常文本（含 java.lang.String / required type
     * java.lang.Long 等内部实现细节）直接写进了响应体。
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponse<Void>> handleTypeMismatch(MethodArgumentTypeMismatchException exception) {
        return ResponseEntity.badRequest()
                .body(ApiResponse.error("参数 " + exception.getName() + " 格式不正确"));
    }

    /** 缺少必填查询参数（例如 GET /chat 没带 npcId）。 */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiResponse<Void>> handleMissingParam(MissingServletRequestParameterException exception) {
        return ResponseEntity.badRequest()
                .body(ApiResponse.error("缺少必要参数：" + exception.getParameterName()));
    }

    /** 请求体不是合法 JSON，或字段类型无法解析。 */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleUnreadable(HttpMessageNotReadableException exception) {
        return ResponseEntity.badRequest().body(ApiResponse.error("请求内容格式不正确"));
    }

    /**
     * 数据层约束（超长、唯一键冲突等）属于输入问题，回 400 而不是 500。
     * 具体原因只记到服务端日志，不返回给客户端。
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiResponse<Void>> handleDataIntegrity(DataIntegrityViolationException exception) {
        log.warn("数据约束校验失败：{}", exception.getMostSpecificCause().getMessage());
        return ResponseEntity.badRequest().body(ApiResponse.error("提交的内容不符合要求，请检查长度或格式"));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiResponse<Void>> handleAccessDenied(AccessDeniedException exception) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiResponse.error("没有权限执行此操作"));
    }

    /**
     * 访问了不存在的路径（例如前端把 /api/user/profile 写成 /api/profile）。
     * <p>
     * 不处理的话它会落进下面的兜底 handler 变成 **500**，同时打出一整条 ERROR 堆栈 ——
     * 一个纯粹的「路径写错了」既污染 5xx 监控、又让排查方向完全跑偏。
     * 这类请求本质是 404，必须回 404。
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleNoResource(NoResourceFoundException exception) {
        log.warn("访问了不存在的路径：{}", exception.getResourcePath());
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiResponse.error("请求的接口不存在"));
    }

    /**
     * 兜底：对外只给一句通用文案，**不把异常 message 回传**（可能含类名、SQL、路径等内部信息）。
     * 真正的原因写进服务端日志，便于排查。
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnknown(Exception exception) {
        log.error("未预期的服务端异常", exception);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.error("服务器处理失败，请稍后重试"));
    }
}
