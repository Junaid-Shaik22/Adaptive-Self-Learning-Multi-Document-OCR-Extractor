package com.invoice.extractor.model; // Trigger re-index

public class InvoiceOcrPage {
    private final int pageNumber;
    private final String sourceName;
    private final String text;

    public InvoiceOcrPage(int pageNumber, String sourceName, String text) {
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
