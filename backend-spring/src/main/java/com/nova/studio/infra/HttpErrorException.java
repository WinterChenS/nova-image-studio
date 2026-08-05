package com.nova.studio.infra;

/**
 * HTTP error carrying the Node backend's error envelope
 * ({@code createHttpError} in {@code backend/server.js}):
 * {@code {error, code, retryAfter}} with a matching {@code Retry-After} header.
 * Thrown by the rate limiter / queue capacity checks; converted to a response
 * by {@link com.nova.studio.web.GlobalExceptionHandler}.
 */
public class HttpErrorException extends RuntimeException {

    private final int statusCode;
    private final String code;
    private final Integer retryAfter;

    public HttpErrorException(int statusCode, String code, String message, Integer retryAfter) {
        super(message);
        this.statusCode = statusCode;
        this.code = code;
        this.retryAfter = retryAfter;
    }

    public HttpErrorException(int statusCode, String code, String message) {
        this(statusCode, code, message, null);
    }

    public int getStatusCode() {
        return statusCode;
    }

    public String getCode() {
        return code;
    }

    public Integer getRetryAfter() {
        return retryAfter;
    }
}
