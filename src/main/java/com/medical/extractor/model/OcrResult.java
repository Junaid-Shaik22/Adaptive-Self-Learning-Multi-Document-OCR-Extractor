package com.medical.extractor.model;

/**
 * OCR Result from a single OCR engine
 */
public class OcrResult {
    private String text;
    private double confidence;
    private String source; // "PADDLE" or "TESSERACT"
    private long processingTimeMs;
    private boolean success;
    private String errorMessage;

    private OcrResult() {}

    public static OcrResult success(String text, String source, double confidence, long processingTimeMs) {
        OcrResult result = new OcrResult();
        result.text = text;
        result.source = source;
        result.confidence = confidence;
        result.processingTimeMs = processingTimeMs;
        result.success = true;
        return result;
    }

    public static OcrResult success(String text) {
        return success(text, "PIPELINE", 1.0, 0);
    }

    public static OcrResult failure(String source, String errorMessage) {
        OcrResult result = new OcrResult();
        result.source = source;
        result.errorMessage = errorMessage;
        result.success = false;
        result.text = "";
        result.confidence = 0.0;
        return result;
    }

    public static OcrResult failure(String errorMessage) {
        return failure("PIPELINE", errorMessage);
    }

    // Getters
    public String getText() { return text; }
    public double getConfidence() { return confidence; }
    public String getSource() { return source; }
    public long getProcessingTimeMs() { return processingTimeMs; }
    public boolean isSuccess() { return success; }
    public String getErrorMessage() { return errorMessage; }
}
