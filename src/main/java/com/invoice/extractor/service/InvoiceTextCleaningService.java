package com.invoice.extractor.service;

import com.invoice.extractor.util.TextUtil;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service("invoiceTextCleaningService")
public class InvoiceTextCleaningService {

    public String clean(String rawText) {
        return TextUtil.cleanOcrText(rawText);
    }

    public String combinePages(List<String> pageTexts) {
        if (pageTexts == null || pageTexts.isEmpty()) {
            return "";
        }
        return pageTexts.stream()
                .map(this::clean)
                .filter(text -> text != null && !text.isBlank())
                .collect(Collectors.joining("\n\n"))
                .trim();
    }
}
