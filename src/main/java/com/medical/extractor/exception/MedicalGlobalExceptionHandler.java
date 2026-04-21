package com.medical.extractor.exception;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.lang.NonNull;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice(basePackages = {"com.medical.extractor.controller", "com.invoice.extractor.controller"})
public class MedicalGlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(MedicalGlobalExceptionHandler.class);

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, Object>> handleMaxSizeExceeded(MaxUploadSizeExceededException ex) {
        return build(HttpStatus.PAYLOAD_TOO_LARGE, "File too large", "Maximum allowed file size is 50 MB.", ex, false);
    }

    @ExceptionHandler({MissingServletRequestParameterException.class, MissingServletRequestPartException.class, MultipartException.class, IllegalArgumentException.class})
    public ResponseEntity<Map<String, Object>> handleBadRequest(Exception ex) {
        return build(HttpStatus.BAD_REQUEST, "Bad request", ex.getMessage(), ex, false);
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, Object>> handleProcessingFailure(IllegalStateException ex) {
        String message = ex.getMessage() != null ? ex.getMessage() : "Medical extraction failed";
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "Medical extraction failed", message, ex, true);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleUnexpected(Exception ex) {
        String message = ex.getMessage() != null ? ex.getMessage() : "Internal server error";
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "Internal server error", message, ex, true);
    }

    private ResponseEntity<Map<String, Object>> build(@NonNull HttpStatusCode status,
                                                      String error,
                                                      String message,
                                                      Exception ex,
                                                      boolean logError) {
        if (logError) {
            log.error("{}: {}", error, ex.getMessage(), ex);
        } else {
            log.warn("{}: {}", error, ex.getMessage());
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("success", false);
        payload.put("error", error);
        payload.put("message", message == null || message.isBlank() ? error : message);
        return ResponseEntity.status(status).body(payload);
    }
}
