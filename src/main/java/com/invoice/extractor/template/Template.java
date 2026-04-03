package com.invoice.extractor.template;

import java.util.Map;

public class Template {
    private String templateId;
    private String vendorName;
    private String vendorGstin;
    private String signature;
    private String layoutSignature;
    private int version;
    private double qualityScore;
    private Map<String, TemplateField> fieldPositions;

    public String getTemplateId() { return templateId; }
    public void setTemplateId(String templateId) { this.templateId = templateId; }

    public String getVendorName() { return vendorName; }
    public void setVendorName(String vendorName) { this.vendorName = vendorName; }

    public String getVendorGstin() { return vendorGstin; }
    public void setVendorGstin(String vendorGstin) { this.vendorGstin = vendorGstin; }

    public String getSignature() { return signature; }
    public void setSignature(String signature) { this.signature = signature; }

    public String getLayoutSignature() {
        return layoutSignature;
    }

    public void setLayoutSignature(String layoutSignature) {
        this.layoutSignature = layoutSignature;
    }

    public int getVersion() { return version; }
    public void setVersion(int version) { this.version = version; }

    public double getQualityScore() {
        return qualityScore;
    }

    public void setQualityScore(double qualityScore) {
        this.qualityScore = qualityScore;
    }

    public Map<String, TemplateField> getFieldPositions() { return fieldPositions; }
    public void setFieldPositions(Map<String, TemplateField> fieldPositions) { this.fieldPositions = fieldPositions; }
}
