package com.invoice.extractor.extractor;

import com.invoice.extractor.service.impl.LineIndexingService;
import com.invoice.extractor.util.DateUtil;
import com.invoice.extractor.util.RegexUtil;
import java.util.List;
import java.util.Locale;

public class InvoiceDateExtractor implements FieldExtractor<String> {
    private static final List<String> DATE_REJECT_KEYWORDS = List.of(
            "purchase order", "customer po", "delivery note", "dispatch", "transport", "buyer's order"
    );

    @Override
    public String extract(String[] lines, int[] zones) {
        return null;
    }

    public String extract(LineIndexingService.Zones zones, int invoiceNumberLine) {
        return extractResult(zones, invoiceNumberLine).getValue();
    }

    public FieldExtractionResult<String> extractResult(LineIndexingService.Zones zones, Integer invoiceNumberLine) {
        if (invoiceNumberLine != null) {
            FieldExtractionResult<String> nearInvoice = extractFromWindow(zones.topZone, invoiceNumberLine, 3, "keyword");
            if (nearInvoice.getValue() != null) {
                return nearInvoice;
            }
        }

        for (LineIndexingService.IndexedLine line : zones.topZone) {
            String lower = line.getText().toLowerCase(Locale.ROOT);
            if (lower.contains("date") && !RegexUtil.containsAnyKeyword(lower, DATE_REJECT_KEYWORDS)) {
                for (String candidate : DateUtil.findCandidateDates(line.getText())) {
                    if (DateUtil.isValidInvoiceDate(candidate)) {
                        return new FieldExtractionResult<>(candidate, "keyword", line.getLineNumber());
                    }
                }
            }
        }

        for (LineIndexingService.IndexedLine line : zones.topZone) {
            for (String candidate : DateUtil.findCandidateDates(line.getText())) {
                if (DateUtil.isValidInvoiceDate(candidate)) {
                    return new FieldExtractionResult<>(candidate, "regex", line.getLineNumber());
                }
            }
        }
        return new FieldExtractionResult<>(null, "fallback", null);
    }

    private FieldExtractionResult<String> extractFromWindow(List<LineIndexingService.IndexedLine> lines, int lineNumber, int radius, String method) {
        FieldExtractionResult<String> best = new FieldExtractionResult<>(null, "fallback", null);
        for (LineIndexingService.IndexedLine line : lines) {
            if (Math.abs(line.getLineNumber() - lineNumber) > radius) {
                continue;
            }
            String lower = line.getText().toLowerCase(Locale.ROOT);
            if (RegexUtil.containsAnyKeyword(lower, DATE_REJECT_KEYWORDS)) {
                continue;
            }
            for (String candidate : DateUtil.findCandidateDates(line.getText())) {
                if (DateUtil.isValidInvoiceDate(candidate)) {
                    if (lower.contains("date") || Math.abs(line.getLineNumber() - lineNumber) <= 1) {
                        return new FieldExtractionResult<>(candidate, method, line.getLineNumber());
                    }
                    best = new FieldExtractionResult<>(candidate, "regex", line.getLineNumber());
                }
            }
        }
        return best;
    }
}
