package com.medical.extractor.service.validation;

import com.medical.extractor.model.MedicalLeaveDataWithScores;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import com.medical.extractor.config.MedicalOcrConfig;
import com.medical.extractor.service.extraction.DateExtractor;

import java.util.HashMap;
import java.util.Map;

/**
 * Validates and cross-checks extracted medical data
 */
@Service
public class MedicalDataValidator {
    private static final Logger logger = LoggerFactory.getLogger(MedicalDataValidator.class);
    
    private final MedicalOcrConfig config;
    private final DateExtractor dateExtractor;

    public MedicalDataValidator(MedicalOcrConfig config, DateExtractor dateExtractor) {
        this.config = config;
        this.dateExtractor = dateExtractor;
    }

    /**
     * Validate complete medical leave data
     */
    public Map<String, Object> validateComplete(MedicalLeaveDataWithScores data) {
        Map<String, Object> validation = new HashMap<>();
        validation.put("isValid", true);
        validation.put("errors", new java.util.ArrayList<String>());
        validation.put("warnings", new java.util.ArrayList<String>());

        if (data == null) {
            validation.put("isValid", false);
            getList(validation, "errors").add("Data is null");
            return validation;
        }

        // Validate individual fields
        validateOrganizationName(data, validation);
        validateApplicantName(data, validation);
        validateDates(data, validation);
        validateTotalDays(data, validation);

        // Cross-field validation
        crossValidateFields(data, validation);

        return validation;
    }

    /**
     * Validate organization name
     */
    private void validateOrganizationName(MedicalLeaveDataWithScores data, Map<String, Object> validation) {
        if (data.getOrganizationName() == null || data.getOrganizationName().isBlank()) {
            if (!config.getValidation().isAllowNullFields()) {
                getList(validation, "warnings").add("Organization name is missing");
            }
            return;
        }

        String orgName = data.getOrganizationName().trim();

        // Check length
        if (orgName.length() < config.getExtraction().getMinOrganizationNameLength()) {
            getList(validation, "errors").add(
                    "Organization name too short: " + orgName);
            validation.put("isValid", false);
        }

        // Check for only text
        if (orgName.matches(".*\\d{4,}.*")) {
            getList(validation, "warnings").add(
                    "Organization name contains many numbers");
        }

        logger.debug("Organization name validated: {}", orgName);
    }

    /**
     * Validate applicant name
     */
    private void validateApplicantName(MedicalLeaveDataWithScores data, Map<String, Object> validation) {
        if (data.getApplicantName() == null || data.getApplicantName().isBlank()) {
            if (!config.getValidation().isAllowNullFields()) {
                getList(validation, "warnings").add("Applicant name is missing");
            }
            return;
        }

        String name = data.getApplicantName().trim();

        // Check length
        if (name.length() < config.getValidation().getMinNameLength()) {
            getList(validation, "errors").add(
                    "Applicant name too short: " + name);
            validation.put("isValid", false);
            return;
        }

        // Check for only alphabetic
        String alphaOnly = name.replaceAll("[^a-zA-Z\\s']", "");
        if (alphaOnly.trim().length() < 3) {
            getList(validation, "errors").add(
                    "Applicant name contains too many non-alphabetic characters: " + name);
            validation.put("isValid", false);
        }

        logger.debug("Applicant name validated: {}", name);
    }

    /**
     * Validate dates
     */
    private void validateDates(MedicalLeaveDataWithScores data, Map<String, Object> validation) {
        String fromDate = data.getFromDate();
        String toDate = data.getToDate();

        // Check if dates exist
        if ((fromDate == null || fromDate.isBlank()) && (toDate == null || toDate.isBlank())) {
            if (!config.getValidation().isAllowNullFields()) {
                getList(validation, "warnings").add("Both dates are missing");
            }
            return;
        }

        if (fromDate == null || fromDate.isBlank() || toDate == null || toDate.isBlank()) {
            getList(validation, "warnings").add(
                    "One or both dates are missing. From: " + fromDate + ", To: " + toDate);
            return;
        }

        // Validate date sequence
        if (config.getValidation().isValidateDateSequence()) {
            if (!dateExtractor.isValidDateSequence(fromDate, toDate)) {
                getList(validation, "errors").add(
                        "Invalid date sequence: from date is after to date. From: " + fromDate + ", To: " + toDate);
                validation.put("isValid", false);
            }
        }

        logger.debug("Dates validated: {} to {}", fromDate, toDate);
    }

    /**
     * Validate total days
     */
    private void validateTotalDays(MedicalLeaveDataWithScores data, Map<String, Object> validation) {
        String daysStr = data.getTotalAbsentDays();

        if (daysStr == null || daysStr.isBlank()) {
            getList(validation, "warnings").add("Total absent days is missing");
            return;
        }

        try {
            int days = Integer.parseInt(daysStr.trim());

            if (days < 0 || days > 365) {
                getList(validation, "errors").add(
                        "Total days out of valid range: " + days);
                validation.put("isValid", false);
            }

            logger.debug("Total days validated: {}", days);
        } catch (NumberFormatException ex) {
            getList(validation, "errors").add(
                    "Total days is not a valid number: " + daysStr);
            validation.put("isValid", false);
        }
    }

    /**
     * Cross-field validation
     */
    private void crossValidateFields(MedicalLeaveDataWithScores data, Map<String, Object> validation) {
        String fromDate = data.getFromDate();
        String toDate = data.getToDate();
        String daysStr = data.getTotalAbsentDays();

        if (config.getValidation().isValidateDaysDifference() &&
                fromDate != null && !fromDate.isBlank() &&
                toDate != null && !toDate.isBlank() &&
                daysStr != null && !daysStr.isBlank()) {

            try {
                long calculatedDays = dateExtractor.calculateDaysBetween(fromDate, toDate);
                int providedDays = Integer.parseInt(daysStr.trim());

                long difference = Math.abs(calculatedDays - providedDays);

                if (difference > 2) {
                    getList(validation, "warnings").add(
                            "Total days mismatch. Calculated: " + calculatedDays + ", Provided: " + providedDays);
                }

                logger.debug("Cross-field validation: Calculated days: {}, Provided days: {}", calculatedDays, providedDays);
            } catch (Exception ex) {
                logger.debug("Could not perform cross-field validation", ex);
            }
        }
    }

    /**
     * Calculate missing days from date range
     */
    public String calculateMissingDays(String fromDate, String toDate) {
        if (fromDate == null || toDate == null || fromDate.isBlank() || toDate.isBlank()) {
            return null;
        }

        try {
            long days = dateExtractor.calculateDaysBetween(fromDate, toDate);
            return String.valueOf(days);
        } catch (Exception ex) {
            logger.debug("Could not calculate days from date range", ex);
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private java.util.List<String> getList(Map<String, Object> map, String key) {
        return (java.util.List<String>) map.get(key);
    }
}
