package comg.fox.springai.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

/**
 * 统一异常出口：把控制器里抛出的参数错误转成 400、上传超限转成 413，
 * 避免前端只收到 500 + 一坨堆栈，也便于前端直接把 message 显示出来。
 * <p>
 * 这里刻意不处理 {@code Exception.class}：全量兜底会把 404（静态资源不存在）等
 * 框架自身的状态码压成 500，反而更难排查。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** 参数校验失败：工作区目录越界、附件超限（显式校验）等 */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<String> handleBadRequest(IllegalArgumentException e) {
        log.warn("请求参数不合法: {}", e.getMessage());
        return ResponseEntity.badRequest().body(e.getMessage());
    }

    /** Servlet 层的 multipart 上限（spring.servlet.multipart.max-*）被突破 */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<String> handleTooLarge(MaxUploadSizeExceededException e) {
        log.warn("上传超过大小上限: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.CONTENT_TOO_LARGE)
                .body("附件过大，已超过服务端上传上限（单文件 20MB / 单次 50MB）");
    }

    /** 目录/文件不可写等运行时状态错误 → 409，避免被当成服务端崩溃 */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<String> handleConflict(IllegalStateException e) {
        log.warn("请求无法完成: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(e.getMessage());
    }
}
