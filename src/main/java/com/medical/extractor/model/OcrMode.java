package com.medical.extractor.model;

/**
 * OCR mode enumeration for Medical OCR pipeline
 */
public enum OcrMode {
    PADDLE("paddle", "PaddleOCR only"),
    TESSERACT("tesseract", "Tesseract only"),
    HYBRID("hybrid", "PaddleOCR with Tesseract fallback");

    private final String value;
    private final String description;

    OcrMode(String value, String description) {
        this.value = value;
        this.description = description;
    }

    public String getValue() {
        return value;
    }

    public String getDescription() {
        return description;
    }

    public static OcrMode fromString(String value) {
        if (value == null || value.isBlank()) {
            return HYBRID;
        }
        for (OcrMode mode : OcrMode.values()) {
            if (mode.value.equalsIgnoreCase(value.trim())) {
                return mode;
            }
        }
        return HYBRID;
    }
}
