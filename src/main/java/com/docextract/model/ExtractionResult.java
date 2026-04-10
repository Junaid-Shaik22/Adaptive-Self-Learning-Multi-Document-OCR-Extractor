package com.docextract.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * Generic extraction result returned by every extractor.
 * Fields are serialized into the JSON response by JsonResponseBuilder.
 * Null / missing fields are excluded from output (JsonInclude.NON_NULL).
 */
@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ExtractionResult {

    @JsonProperty("document_type")
    private String documentType;

    // ─── Aadhaar & Common ─────────────────────────────────────────────────────
    @JsonProperty("name")
    private String name;

    @JsonProperty("dob")
    private String dob;

    @JsonProperty("gender")
    private String gender;

    @JsonProperty("address")
    private String address;

    // ─── Aadhaar ──────────────────────────────────────────────────────────────
    @JsonProperty("aadhaar_number")
    private String aadhaarNumber;

    // ─── PAN ──────────────────────────────────────────────────────────────────
    @JsonProperty("father_name")
    private String fatherName;

    @JsonProperty("pan_number")
    private String panNumber;

    // ─── Driving License ──────────────────────────────────────────────────────
    @JsonProperty("dl_number")
    private String dlNumber;

    @JsonProperty("valid_from")
    private String validFrom;

    @JsonProperty("valid_to")
    private String validTo;

    // ─── Meta / Validation ────────────────────────────────────────────────────
    @JsonProperty("validation_errors")
    private List<String> validationErrors;

    @JsonProperty("confidence")
    private String confidence;

    @JsonProperty("pages_processed")
    private Integer pagesProcessed;
}
