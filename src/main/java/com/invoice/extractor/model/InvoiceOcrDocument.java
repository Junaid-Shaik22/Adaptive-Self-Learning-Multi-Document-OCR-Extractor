package com.invoice.extractor.model; // Trigger re-index

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

public class InvoiceOcrDocument {
    private final List<InvoiceOcrPage> pages;
    private final String combinedText;

    public InvoiceOcrDocument(List<InvoiceOcrPage> pages) {
        List<InvoiceOcrPage> safePages = pages == null ? List.of() : new ArrayList<>(pages);
        this.pages = Collections.unmodifiableList(safePages);
        this.combinedText = safePages.stream()
                .map(InvoiceOcrPage::getText)
                .filter(text -> text != null && !text.isBlank())
                .collect(Collectors.joining("\n\n"))
                .trim();
    }

    public static InvoiceOcrDocument single(String text) {
        return new InvoiceOcrDocument(List.of(new InvoiceOcrPage(1, "page-1", text)));
    }

    public List<InvoiceOcrPage> getPages() {
        return pages;
    }

    public String getCombinedText() {
        return combinedText;
    }

    public int getPageCount() {
        return pages.size();
    }

    public boolean hasMultiplePages() {
        return pages.size() > 1;
    }

    public String getFirstPageText() {
        return pages.isEmpty() ? "" : pages.get(0).getText();
    }

    public String getLastPageText() {
        return pages.isEmpty() ? "" : pages.get(pages.size() - 1).getText();
    }

    public String getMiddlePagesText() {
        if (pages.size() <= 2) {
            return "";
        }
        return pages.subList(1, pages.size() - 1).stream()
                .map(InvoiceOcrPage::getText)
                .filter(text -> text != null && !text.isBlank())
                .collect(Collectors.joining("\n\n"))
                .trim();
    }
}
