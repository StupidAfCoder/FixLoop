package com.fixloop.common;

import java.util.LinkedHashMap;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/** Turns exceptions into the one error shape; never leaks a stack trace. */
@RestControllerAdvice
public class ApiExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiError> handleApiException(ApiException exception, HttpServletRequest request) {
        return respond(request, exception.status(), exception.code(), exception.getMessage(), exception.details());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException exception,
                                                     HttpServletRequest request) {
        Map<String, String> details = new LinkedHashMap<>();
        for (FieldError error : exception.getBindingResult().getFieldErrors()) {
            details.putIfAbsent(error.getField(), error.getDefaultMessage() == null
                ? "is not valid" : error.getDefaultMessage());
        }
        return respond(request, HttpStatus.BAD_REQUEST, "VALIDATION_FAILED",
            "Some fields are not valid.", details);
    }

    /** A uniqueness race, for example two registrations for one address at once. */
    @ExceptionHandler(DuplicateKeyException.class)
    public ResponseEntity<ApiError> handleDuplicate(HttpServletRequest request) {
        return respond(request, HttpStatus.CONFLICT, "CONFLICT",
            "That value is already in use.", null);
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    public ResponseEntity<ApiError> handleUnreadable(HttpServletRequest request) {
        return respond(request, HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST",
            "The request body or parameters could not be read.", null);
    }

    /** Method-level role checks (bible 14.2) must read as 403, never as 500. */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiError> handleAccessDenied(HttpServletRequest request) {
        return respond(request, HttpStatus.FORBIDDEN, "FORBIDDEN", "Access is not allowed.", null);
    }

    /** A photograph over the configured limit is a client problem, not a server one. */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiError> handleTooLarge(HttpServletRequest request) {
        return respond(request, HttpStatus.PAYLOAD_TOO_LARGE, "FILE_TOO_LARGE",
            "That upload is larger than the server accepts.", null);
    }

    /** A multipart request without the part the endpoint needs. */
    @ExceptionHandler({MissingServletRequestPartException.class, MultipartException.class})
    public ResponseEntity<ApiError> handleBrokenUpload(HttpServletRequest request) {
        return respond(request, HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST",
            "The upload is incomplete or could not be read.", null);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiError> handleNotFound(HttpServletRequest request) {
        return respond(request, HttpStatus.NOT_FOUND, "NOT_FOUND",
            "The requested resource does not exist.", null);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiError> handleMethodNotAllowed(HttpServletRequest request) {
        return respond(request, HttpStatus.METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED",
            "That method is not allowed for this resource.", null);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception exception, HttpServletRequest request) {
        String traceId = traceId(request);
        log.error("Unhandled error, traceId={}", traceId, exception);
        return respond(request, HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR",
            "Something went wrong. Quote the trace id if you report this.", null);
    }

    private static ResponseEntity<ApiError> respond(HttpServletRequest request, HttpStatus status,
                                                    String code, String message,
                                                    Map<String, String> details) {
        return ResponseEntity.status(status).body(new ApiError(code, message, traceId(request), details));
    }

    private static String traceId(HttpServletRequest request) {
        Object traceId = request.getAttribute(TraceIdFilter.ATTRIBUTE);
        return traceId == null ? null : traceId.toString();
    }
}
