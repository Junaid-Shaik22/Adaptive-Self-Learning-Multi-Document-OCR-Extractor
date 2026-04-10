package com.docextract.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Wrapper for all API responses – both success and error paths.
 */
@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ApiResponse {

    @JsonProperty("success")
    private boolean success;

    @JsonProperty("timestamp")
    private String timestamp;

    @JsonProperty("file_name")
    private String fileName;

    @JsonProperty("data")
    private ExtractionResult data;

    @JsonProperty("error")
    private String error;

    @JsonProperty("error_details")
    private String errorDetails;

    // ─── Factory helpers ──────────────────────────────────────────────────────

    public static ApiResponse ok(ExtractionResult result, String fileName) {
        return ApiResponse.builder()
                .success(true)
                .timestamp(now())
                .fileName(fileName)
                .data(result)
                .build();
    }

    public static ApiResponse fail(String error, String details) {
        return ApiResponse.builder()
                .success(false)
                .timestamp(now())
                .error(error)
                .errorDetails(details)
                .build();
    }

    private static String now() {
        return LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }
}
