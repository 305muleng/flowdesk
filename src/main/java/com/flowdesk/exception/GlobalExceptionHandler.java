package com.flowdesk.exception;

import com.flowdesk.common.Result;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log =
            LoggerFactory.getLogger(GlobalExceptionHandler.class);


    // 1. 我们自己主动抛出的业务异常
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Result<Void>> handleBusinessException(
            BusinessException e) {

        Result<Void> result =
                Result.fail(
                        e.getCode(),
                        e.getMessage()
                );

        return ResponseEntity
                .status(e.getCode())
                .body(result);
    }


    // 2. DTO 参数校验失败
    // 例如 @NotBlank、@NotNull、@Size
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Result<Void>> handleValidationException(
            MethodArgumentNotValidException e) {

        String message = "参数校验失败";

        if (e.getBindingResult().getFieldError() != null) {
            message = e.getBindingResult()
                    .getFieldError()
                    .getDefaultMessage();
        }

        Result<Void> result =
                Result.fail(
                        400,
                        message
                );

        return ResponseEntity
                .badRequest()
                .body(result);
    }


    // 3. URL / 查询参数类型转换失败
    // 例如 /tasks/abc，但 taskId 要求 Long
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Result<Void>> handleTypeMismatchException(
            MethodArgumentTypeMismatchException e) {

        Result<Void> result =
                Result.fail(
                        400,
                        "请求参数类型错误"
                );

        return ResponseEntity
                .badRequest()
                .body(result);
    }


    // 4. JSON 无法正常解析
    // 例如日期格式乱写、JSON 本身格式错误
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Result<Void>> handleMessageNotReadableException(
            HttpMessageNotReadableException e) {

        Result<Void> result =
                Result.fail(
                        400,
                        "请求体格式错误"
                );

        return ResponseEntity
                .badRequest()
                .body(result);
    }


    // 5. 必填的查询参数缺失
    // 例如接口要求 ?status=...，但完全没有传
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<Result<Void>> handleMissingRequestParameterException(
            MissingServletRequestParameterException e) {

        Result<Void> result =
                Result.fail(
                        400,
                        "缺少必要的请求参数：" + e.getParameterName()
                );

        return ResponseEntity
                .badRequest()
                .body(result);
    }


    // 6. 请求了不存在的地址
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Result<Void>> handleNoResourceFoundException(
            NoResourceFoundException e) {

        Result<Void> result =
                Result.fail(
                        404,
                        "接口不存在"
                );

        return ResponseEntity
                .status(404)
                .body(result);
    }


    // 7. HTTP 方法用错
    // 例如接口只允许 POST，却发了 GET
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Result<Void>> handleMethodNotSupportedException(
            HttpRequestMethodNotSupportedException e) {

        Result<Void> result =
                Result.fail(
                        405,
                        "请求方法不支持"
                );

        return ResponseEntity
                .status(405)
                .body(result);
    }


    // 8. Content-Type 不支持
    // 例如接口要求 application/json，却发了其他不支持的格式
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<Result<Void>> handleMediaTypeNotSupportedException(
            HttpMediaTypeNotSupportedException e) {

        Result<Void> result =
                Result.fail(
                        415,
                        "请求数据格式不支持"
                );

        return ResponseEntity
                .status(415)
                .body(result);
    }


    // 9. 真正没有预料到的服务器异常
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result<Void>> handleException(
            Exception e) {

        log.error(
                "未处理的服务器异常",
                e
        );

        Result<Void> result =
                Result.fail(
                        500,
                        "服务器内部错误"
                );

        return ResponseEntity
                .internalServerError()
                .body(result);
    }
}