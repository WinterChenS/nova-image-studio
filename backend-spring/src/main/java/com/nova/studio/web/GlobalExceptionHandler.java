package com.nova.studio.web;

import com.nova.studio.infra.HttpErrorException;
import com.nova.studio.infra.NormalizedError;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Global error normalization — port of the Node backend's {@code sendHttpError}
 * / {@code handleApi} catch block ({@code backend/server.js}):
 * <ul>
 *   <li>{@link HttpErrorException} → its status/code/retryAfter with a
 *       {@code Retry-After} header (413 adds {@code Connection: close});</li>
 *   <li>payload-too-large → 413 PAYLOAD_TOO_LARGE with the Node message;</li>
 *   <li>malformed JSON body → 400 (Node {@code 请求 JSON 格式无效});</li>
 *   <li>anything else → 400 with the normalized message (no internals leaked).</li>
 * </ul>
 * The {@code error}/{@code code}/{@code retryAfter} fields are omitted when
 * absent, matching {@code JSON.stringify} dropping {@code undefined}.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private final long requestTimeoutMs;

    public GlobalExceptionHandler(@Value("${nova.task.request-timeout-ms:1800000}") long requestTimeoutMs) {
        this.requestTimeoutMs = requestTimeoutMs;
    }

    @ExceptionHandler(HttpErrorException.class)
    public ResponseEntity<Map<String, Object>> httpError(HttpErrorException e) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", e.getMessage());
        if (e.getCode() != null) {
            body.put("code", e.getCode());
        }
        if (e.getRetryAfter() != null) {
            body.put("retryAfter", e.getRetryAfter());
        }
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(e.getStatusCode());
        if (e.getRetryAfter() != null) {
            builder.header("Retry-After", String.valueOf(e.getRetryAfter()));
        }
        if (e.getStatusCode() == 413) {
            builder.header("Connection", "close");
        }
        return builder.body(body);
    }

    @ExceptionHandler({MaxUploadSizeExceededException.class})
    public ResponseEntity<Map<String, Object>> payloadTooLarge() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "请求体过大：参考图过多或分辨率过高，请减少参考图数量或降低分辨率后重试。");
        body.put("code", "PAYLOAD_TOO_LARGE");
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .header("Retry-After", "60")
                .header("Connection", "close")
                .body(body);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> unreadableBody() {
        return ResponseEntity.badRequest().body(Map.of("error", "请求 JSON 格式无效"));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> unexpected(Exception e) {
        return ResponseEntity.badRequest().body(Map.of("error", NormalizedError.normalize(e, requestTimeoutMs)));
    }
}
