package com.medical.extractor.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration for Medical OCR hybrid pipeline
 */
@Component
@ConfigurationProperties(prefix = "medical.ocr")
public class MedicalOcrConfig {

    /**
     * OCR mode: paddle, tesseract, or hybrid
     */
    private String mode = "hybrid";

    /**
     * PaddleOCR external service configuration
     */
    private PaddleOcrConfig paddle = new PaddleOcrConfig();

    /**
     * Tesseract configuration
     */
    private TesseractConfig tesseract = new TesseractConfig();

    /**
     * Extraction configuration
     */
    private ExtractionConfig extraction = new ExtractionConfig();

    /**
     * Validation configuration
     */
    private ValidationConfig validation = new ValidationConfig();

    /**
     * Logging configuration
     */
    private LoggingConfig logging = new LoggingConfig();

    // Getters and setters
    public String getMode() {
        return mode;
    }

    public void setMode(String mode) {
        this.mode = mode;
    }

    public PaddleOcrConfig getPaddle() {
        return paddle;
    }

    public void setPaddle(PaddleOcrConfig paddle) {
        this.paddle = paddle;
    }

    public TesseractConfig getTesseract() {
        return tesseract;
    }

    public void setTesseract(TesseractConfig tesseract) {
        this.tesseract = tesseract;
    }

    public ExtractionConfig getExtraction() {
        return extraction;
    }

    public void setExtraction(ExtractionConfig extraction) {
        this.extraction = extraction;
    }

    public ValidationConfig getValidation() {
        return validation;
    }

    public void setValidation(ValidationConfig validation) {
        this.validation = validation;
    }

    public LoggingConfig getLogging() {
        return logging;
    }

    public void setLogging(LoggingConfig logging) {
        this.logging = logging;
    }

    // Inner classes

    public static class PaddleOcrConfig {
        private String serviceUrl = "http://localhost:8000";
        private String endpoint = "/ocr";
        private int timeoutSeconds = 30;
        private boolean enabled = true;
        private double minConfidence = 0.5;

        public String getServiceUrl() { return serviceUrl; }
        public void setServiceUrl(String serviceUrl) { this.serviceUrl = serviceUrl; }
        public String getEndpoint() { return endpoint; }
        public void setEndpoint(String endpoint) { this.endpoint = endpoint; }
        public int getTimeoutSeconds() { return timeoutSeconds; }
        public void setTimeoutSeconds(int timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public double getMinConfidence() { return minConfidence; }
        public void setMinConfidence(double minConfidence) { this.minConfidence = minConfidence; }

        public String getFullUrl() {
            return serviceUrl + endpoint;
        }
    }

    public static class TesseractConfig {
        private String language = "eng";
        private int psm = 6;
        private boolean enabled = true;

        public String getLanguage() { return language; }
        public void setLanguage(String language) { this.language = language; }
        public int getPsm() { return psm; }
        public void setPsm(int psm) { this.psm = psm; }
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
    }

    public static class ExtractionConfig {
        private int organizationNameTopPercentage = 20;
        private int minApplicantNameLength = 3;
        private int maxApplicantNameLength = 80;
        private int minOrganizationNameLength = 3;
        private boolean enableMultiCandidateScoring = true;

        public int getOrganizationNameTopPercentage() { return organizationNameTopPercentage; }
        public void setOrganizationNameTopPercentage(int organizationNameTopPercentage) { this.organizationNameTopPercentage = organizationNameTopPercentage; }
        public int getMinApplicantNameLength() { return minApplicantNameLength; }
        public void setMinApplicantNameLength(int minApplicantNameLength) { this.minApplicantNameLength = minApplicantNameLength; }
        public int getMaxApplicantNameLength() { return maxApplicantNameLength; }
        public void setMaxApplicantNameLength(int maxApplicantNameLength) { this.maxApplicantNameLength = maxApplicantNameLength; }
        public int getMinOrganizationNameLength() { return minOrganizationNameLength; }
        public void setMinOrganizationNameLength(int minOrganizationNameLength) { this.minOrganizationNameLength = minOrganizationNameLength; }
        public boolean isEnableMultiCandidateScoring() { return enableMultiCandidateScoring; }
        public void setEnableMultiCandidateScoring(boolean enableMultiCandidateScoring) { this.enableMultiCandidateScoring = enableMultiCandidateScoring; }
    }

    public static class ValidationConfig {
        private boolean validateDateSequence = true;
        private boolean validateDaysDifference = true;
        private boolean allowNullFields = false;
        private int minNameLength = 3;

        public boolean isValidateDateSequence() { return validateDateSequence; }
        public void setValidateDateSequence(boolean validateDateSequence) { this.validateDateSequence = validateDateSequence; }
        public boolean isValidateDaysDifference() { return validateDaysDifference; }
        public void setValidateDaysDifference(boolean validateDaysDifference) { this.validateDaysDifference = validateDaysDifference; }
        public boolean isAllowNullFields() { return allowNullFields; }
        public void setAllowNullFields(boolean allowNullFields) { this.allowNullFields = allowNullFields; }
        public int getMinNameLength() { return minNameLength; }
        public void setMinNameLength(int minNameLength) { this.minNameLength = minNameLength; }
    }

    public static class LoggingConfig {
        private boolean enableOcrTypeLogging = true;
        private boolean enableFallbackLogging = true;
        private boolean enableExtractionLogging = true;

        public boolean isEnableOcrTypeLogging() { return enableOcrTypeLogging; }
        public void setEnableOcrTypeLogging(boolean enableOcrTypeLogging) { this.enableOcrTypeLogging = enableOcrTypeLogging; }
        public boolean isEnableFallbackLogging() { return enableFallbackLogging; }
        public void setEnableFallbackLogging(boolean enableFallbackLogging) { this.enableFallbackLogging = enableFallbackLogging; }
        public boolean isEnableExtractionLogging() { return enableExtractionLogging; }
        public void setEnableExtractionLogging(boolean enableExtractionLogging) { this.enableExtractionLogging = enableExtractionLogging; }
    }
}
