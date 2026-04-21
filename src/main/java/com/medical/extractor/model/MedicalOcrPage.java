package com.medical.extractor.model;

public class MedicalOcrPage {
    private final int pageNumber;
    private final String sourceName;
    private final String text;

    public MedicalOcrPage(int pageNumber, String sourceName, String text) {
        this.pageNumber = pageNumber;
        this.sourceName = sourceName;
        this.text = text == null ? "" : text;
    }

    public int getPageNumber() {
        return pageNumber;
    }

    public String getSourceName() {
        return sourceName;
    }

    public String getText() {
        return text;
    }
}
