package com.selfface.common;

import com.selfface.security.CryptoService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.validation.BindException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.UUID;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BizException.class)
    public R<Void> handleBiz(BizException e) {
        return R.fail(e.getCode(), e.getMessage());
    }

    @ExceptionHandler(RateLimitException.class)
    public R<Void> handleRateLimit(RateLimitException e) {
        return R.fail(429, e.getMessage());
    }

    /**
     * 解密类故障的提示语是我们自己写的，可以安全地回给用户（例如「换过密钥请重新填写」）。
     */
    @ExceptionHandler(CryptoService.CryptoException.class)
    public R<Void> handleCrypto(CryptoService.CryptoException e) {
        log.error("加解密失败", e);
        return R.fail(500, e.getMessage());
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, BindException.class})
    public R<Void> handleValidation(BindException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(f -> f.getField() + " " + f.getDefaultMessage())
                .orElse("参数校验失败");
        return R.fail(422, msg);
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public R<Void> handleUploadTooLarge(MaxUploadSizeExceededException e) {
        return R.fail(413, "文件过大，请上传 10MB 以内的简历");
    }

    /**
     * 路径不存在。不单独处理的话会被 Spring 当作静态资源找不到，报成 500 并泄露内部路径。
     */
    @ExceptionHandler(NoResourceFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public R<Void> handleNotFound(NoResourceFoundException e) {
        return R.fail(404, "接口不存在：" + e.getResourcePath());
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    @ResponseStatus(HttpStatus.METHOD_NOT_ALLOWED)
    public R<Void> handleMethodNotSupported(HttpRequestMethodNotSupportedException e) {
        return R.fail(405, "请求方法不支持：" + e.getMethod());
    }

    /**
     * 兜底。异常详情只进日志，不回给前端 —— 原始的 e.getMessage() 常带包名、
     * SQL 片段甚至连接串，属于典型的信息泄露。
     * 返回的问题编号可与日志中的 traceId 对应，便于用户报障时定位。
     */
    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public R<Void> handleOther(Exception e) {
        String traceId = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        log.error("未处理异常 traceId={}", traceId, e);
        return R.fail(500, "服务器内部错误，请稍后重试（问题编号 " + traceId + "）");
    }
}
