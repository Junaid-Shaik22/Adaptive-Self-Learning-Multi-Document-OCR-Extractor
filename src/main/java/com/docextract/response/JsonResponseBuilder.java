package com.docextract.response;

import com.docextract.model.ExtractionResult;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * JsonResponseBuilder – converts an ExtractionResult into a clean,
 * document-type-specific ordered JSON map.
 *
 * Fields are ordered logically based on document type (not alphabetically),
 * and null/blank fields are excluded from the final output.
 */
@Slf4j
@Component
public class JsonResponseBuilder {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .setDefaultPropertyInclusion(JsonInclude.Include.NON_NULL);

    /**
     * Build a nicely ordered Map from an ExtractionResult.
     * The Spring JSON serializer will output this in insertion order.
     */
    public Map<String, Object> build(ExtractionResult result) {
        if (result == null) return Map.of("error", "null result");

        String docType = result.getDocumentType();
        Map<String, Object> map = new LinkedHashMap<>();

        map.put("document_type", docType);

        if ("AADHAAR".equals(docType)) {
            putIfNotNull(map, "name",           result.getName());
            putIfNotNull(map, "aadhaar_number", result.getAadhaarNumber());
            putIfNotNull(map, "dob",            result.getDob());
            putIfNotNull(map, "gender",         result.getGender());
            putIfNotNull(map, "address",        result.getAddress());

        } else if ("PAN".equals(docType)) {
            putIfNotNull(map, "name",        result.getName());
            putIfNotNull(map, "father_name", result.getFatherName());
            putIfNotNull(map, "dob",         result.getDob());
            putIfNotNull(map, "pan_number",  result.getPanNumber());

        } else if ("DRIVING_LICENSE".equals(docType)) {
            putIfNotNull(map, "name",       result.getName());
            putIfNotNull(map, "dl_number",  result.getDlNumber());
            putIfNotNull(map, "dob",        result.getDob());
            putIfNotNull(map, "valid_from", result.getValidFrom());
            putIfNotNull(map, "valid_to",   result.getValidTo());
            putIfNotNull(map, "address",    result.getAddress());

        } else {
            // UNKNOWN: show whatever is available
            putIfNotNull(map, "name",           result.getName());
            putIfNotNull(map, "aadhaar_number", result.getAadhaarNumber());
            putIfNotNull(map, "pan_number",     result.getPanNumber());
            putIfNotNull(map, "dl_number",      result.getDlNumber());
            putIfNotNull(map, "dob",            result.getDob());
            putIfNotNull(map, "gender",         result.getGender());
            putIfNotNull(map, "address",        result.getAddress());
        }

        // Always include meta
        putIfNotNull(map, "confidence",       result.getConfidence());
        putIfNotNull(map, "pages_processed",  result.getPagesProcessed());
        putIfNotNull(map, "validation_errors", result.getValidationErrors());

        return map;
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private void putIfNotNull(Map<String, Object> map, String key, Object value) {
        if (value == null) return;
        if (value instanceof String s && s.isBlank()) return;
        map.put(key, value);
    }

    /** Serialize a result directly to a pretty-printed JSON string (for logging). */
    public String toJson(ExtractionResult result) {
        try {
            return MAPPER.writeValueAsString(build(result));
        } catch (Exception e) {
            log.error("JSON serialization error", e);
            return "{}";
        }
    }
}
