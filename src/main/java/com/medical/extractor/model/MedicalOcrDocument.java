package com.medical.extractor.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

public class MedicalOcrDocument {
    private final List<MedicalOcrPage> pages;
    private final String combinedText;

    public MedicalOcrDocument(List<MedicalOcrPage> pages) {
        List<MedicalOcrPage> safePages = pages == null ? List.of() : new ArrayList<>(pages);
        this.pages = Collections.unmodifiableList(safePages);
        this.combinedText = safePages.stream()
                .map(MedicalOcrPage::getText)
                .filter(text -> text != null && !text.isBlank())
                .collect(Collectors.joining("\n\n"))
                .trim();
    }

    public static MedicalOcrDocument single(String text) {
        return new MedicalOcrDocument(List.of(new MedicalOcrPage(1, "page-1", text)));
    }

    public List<MedicalOcrPage> getPages() {
        return pages;
    }

    public String getCombinedText() {
        return combinedText;
    }

    public int getPageCount() {
        return pages.size();
    }
}
