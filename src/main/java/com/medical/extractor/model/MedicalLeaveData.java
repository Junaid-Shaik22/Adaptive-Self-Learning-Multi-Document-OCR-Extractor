package com.medical.extractor.model;

public class MedicalLeaveData {
    private String organizationName;
    private String applicantName;
    private String fromDate;
    private String toDate;
    private String totalAbsentDays;

    public String getOrganizationName() {
        return organizationName;
    }

    public void setOrganizationName(String organizationName) {
        this.organizationName = organizationName;
    }

    public String getApplicantName() {
        return applicantName;
    }

    public void setApplicantName(String applicantName) {
        this.applicantName = applicantName;
    }

    public String getFromDate() {
        return fromDate;
    }

    public void setFromDate(String fromDate) {
        this.fromDate = fromDate;
    }

    public String getToDate() {
        return toDate;
    }

    public void setToDate(String toDate) {
        this.toDate = toDate;
    }

    public String getTotalAbsentDays() {
        return totalAbsentDays;
    }

    public void setTotalAbsentDays(String totalAbsentDays) {
        this.totalAbsentDays = totalAbsentDays;
    }
}
