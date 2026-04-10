package com.docextract.exception;

import com.docextract.model.ApiResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;

/**
 * GlobalExceptionHandler – centralised exception handling for all REST controllers.
 *
 * Catches known and unexpected exceptions and converts them into a consistent
 * ApiResponse JSON format with appropriate HTTP status codes.
 */
@Slf4j
@RestControllerAdvice(basePackages = "com.docextract.controller")
public class GlobalExceptionHandler {

    // ── File too large ─────────────────────────────────────────────────────────
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiResponse> handleMaxSizeExceeded(MaxUploadSizeExceededException ex) {
        log.warn("File upload exceeded size limit: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(ApiResponse.fail(
                        "File too large",
                        "Maximum allowed file size is 50 MB. Please upload a smaller file."
                ));
    }

    // ── Missing required request parameter ────────────────────────────────────
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiResponse> handleMissingParam(MissingServletRequestParameterException ex) {
        log.warn("Missing request parameter: {}", ex.getParameterName());
        return ResponseEntity.badRequest()
                .body(ApiResponse.fail(
                        "Missing parameter: " + ex.getParameterName(),
                        "Please include the 'file' multipart parameter in your request."
                ));
    }

    @ExceptionHandler(MissingServletRequestPartException.class)
    public ResponseEntity<ApiResponse> handleMissingPart(MissingServletRequestPartException ex) {
        log.warn("Missing multipart part: {}", ex.getRequestPartName());
        return ResponseEntity.badRequest()
                .body(ApiResponse.fail(
                        "Missing multipart part: " + ex.getRequestPartName(),
                        "Please include the 'file' multipart field in your request."
                ));
    }

    // ── Multipart / form-data errors ──────────────────────────────────────────
    @ExceptionHandler(MultipartException.class)
    public ResponseEntity<ApiResponse> handleMultipart(MultipartException ex) {
        log.warn("Multipart error: {}", ex.getMessage());
        return ResponseEntity.badRequest()
                .body(ApiResponse.fail(
                        "Invalid multipart request",
                        "Ensure the request uses Content-Type: multipart/form-data with a 'file' field."
                ));
    }

    // ── Illegal argument (e.g. unsupported file type) ─────────────────────────
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponse> handleIllegalArgument(IllegalArgumentException ex) {
        log.warn("Illegal argument: {}", ex.getMessage());
        return ResponseEntity.badRequest()
                .body(ApiResponse.fail("Bad request", ex.getMessage()));
    }

    // ── Unsupported operation ─────────────────────────────────────────────────
    @ExceptionHandler(UnsupportedOperationException.class)
    public ResponseEntity<ApiResponse> handleUnsupported(UnsupportedOperationException ex) {
        log.warn("Unsupported operation: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED)
                .body(ApiResponse.fail("Not implemented", ex.getMessage()));
    }

    // ── Generic catch-all ─────────────────────────────────────────────────────
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse> handleGeneric(Exception ex) {
        log.error("Unexpected error during document processing", ex);
        return ResponseEntity.internalServerError()
                .body(ApiResponse.fail(
                        "Internal server error",
                        "An unexpected error occurred: " + ex.getMessage() +
                        ". Check server logs for details."
                ));
    }
}
