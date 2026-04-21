package com.medical.extractor.model;

import java.util.HashMap;
import java.util.Map;

/**
 * Extracted and validated medical leave data with scoring information
 */
public class MedicalLeaveDataWithScores {
    private String organizationName;
    private double organizationNameScore;
    private String applicantName;
    private double applicantNameScore;
    private String fromDate;
    private double fromDateScore;
    private String toDate;
    private double toDateScore;
    private String totalAbsentDays;
    private double totalAbsentDaysScore;
    
    private OcrMode ocrModeUsed;
    private boolean fallbackUsed;
    private Map<String, Object> extractionMetadata = new HashMap<>();

    public MedicalLeaveDataWithScores() {}

    // Conversion to MedicalLeaveData
    public MedicalLeaveData toMedicalLeaveData() {
        MedicalLeaveData data = new MedicalLeaveData();
        data.setOrganizationName(organizationName);
        data.setApplicantName(applicantName);
        data.setFromDate(fromDate);
        data.setToDate(toDate);
        data.setTotalAbsentDays(totalAbsentDays);
        return data;
    }

    // Getters and setters
    public String getOrganizationName() { return organizationName; }
    public void setOrganizationName(String organizationName) { this.organizationName = organizationName; }
    public double getOrganizationNameScore() { return organizationNameScore; }
    public void setOrganizationNameScore(double organizationNameScore) { this.organizationNameScore = organizationNameScore; }
    
    public String getApplicantName() { return applicantName; }
    public void setApplicantName(String applicantName) { this.applicantName = applicantName; }
    public double getApplicantNameScore() { return applicantNameScore; }
    public void setApplicantNameScore(double applicantNameScore) { this.applicantNameScore = applicantNameScore; }
    
    public String getFromDate() { return fromDate; }
    public void setFromDate(String fromDate) { this.fromDate = fromDate; }
    public double getFromDateScore() { return fromDateScore; }
    public void setFromDateScore(double fromDateScore) { this.fromDateScore = fromDateScore; }
    
    public String getToDate() { return toDate; }
    public void setToDate(String toDate) { this.toDate = toDate; }
    public double getToDateScore() { return toDateScore; }
    public void setToDateScore(double toDateScore) { this.toDateScore = toDateScore; }
    
    public String getTotalAbsentDays() { return totalAbsentDays; }
    public void setTotalAbsentDays(String totalAbsentDays) { this.totalAbsentDays = totalAbsentDays; }
    public double getTotalAbsentDaysScore() { return totalAbsentDaysScore; }
    public void setTotalAbsentDaysScore(double totalAbsentDaysScore) { this.totalAbsentDaysScore = totalAbsentDaysScore; }
    
    public OcrMode getOcrModeUsed() { return ocrModeUsed; }
    public void setOcrModeUsed(OcrMode ocrModeUsed) { this.ocrModeUsed = ocrModeUsed; }
    public boolean isFallbackUsed() { return fallbackUsed; }
    public void setFallbackUsed(boolean fallbackUsed) { this.fallbackUsed = fallbackUsed; }
    
    public Map<String, Object> getExtractionMetadata() { return extractionMetadata; }
    public void setExtractionMetadata(Map<String, Object> extractionMetadata) { this.extractionMetadata = extractionMetadata; }
}
