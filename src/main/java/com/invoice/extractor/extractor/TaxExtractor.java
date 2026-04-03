package com.invoice.extractor.extractor;

import com.invoice.extractor.service.impl.LineIndexingService;
import com.invoice.extractor.util.AmountUtil;
import java.util.Locale;

public class TaxExtractor implements FieldExtractor<String> {
    @Override
    public String extract(String[] lines, int[] zones) {
        return null;
    }

    public String extract(LineIndexingService.Zones zones, Double total, Double subtotal) {
        return extractResult(zones, total, subtotal).getValue();
    }

    public FieldExtractionResult<String> extractResult(LineIndexingService.Zones zones, Double total, Double subtotal) {
        // Priority 1: Explicit "Total Tax Amount" labeled line
        for (AmountUtil.AmountCandidate candidate : AmountUtil.extractCandidates(zones.bottomZone)) {
            String lower = candidate.getLine().getText().toLowerCase(Locale.ROOT);
            if (candidate.isPercentToken() || lower.contains("taxable")) {
                continue;
            }
            if (lower.contains("total tax amount") || lower.matches(".*\\btax amount\\b.*")) {
                if (total == null || candidate.getValue() < total) {
                    return new FieldExtractionResult<>(AmountUtil.formatAmount(candidate.getValue()), "keyword",
                            candidate.getLine().getLineNumber());
                }
            }
        }

        FieldExtractionResult<String> summaryTableTax = extractFromSummaryTable(zones.bottomZone, total);
        if (summaryTableTax.getValue() == null) {
            summaryTableTax = extractFromSummaryTable(zones.tableZone, total);
        }
        if (summaryTableTax.getValue() != null) {
            return summaryTableTax;
        }

        double sum = 0.0;
        Integer lineNumber = null;
        java.util.Set<String> seen = new java.util.HashSet<>();
        java.util.List<LineIndexingService.IndexedLine> allLines = new java.util.ArrayList<>(zones.tableZone);
        allLines.addAll(zones.bottomZone);

        for (AmountUtil.AmountCandidate candidate : AmountUtil.extractCandidates(allLines)) {
            String lineText = candidate.getLine().getText();
            String lower = lineText.toLowerCase(Locale.ROOT);
            if (!AmountUtil.isTaxLine(lineText) || candidate.isPercentToken() || lower.contains("taxable")) {
                continue;
            }
            // Using line number and token text as the uniqueness key to sum multiple amounts on the same line
            String key = candidate.getLine().getLineNumber() + ":" + candidate.getToken();
            if (seen.add(key)) {
                sum += candidate.getValue();
                if (lineNumber == null) {
                    lineNumber = candidate.getLine().getLineNumber();
                }
            }
        }
        if (sum > 0 && (total == null || sum < total)) {
            return new FieldExtractionResult<>(AmountUtil.formatAmount(sum), "keyword", lineNumber);
        }
        if (total != null && subtotal != null) {
            double diff = total - subtotal;
            if (diff > 0 && diff < total) {
                return new FieldExtractionResult<>(AmountUtil.formatAmount(diff), "fallback", lineNumber);
            }
        }
        return new FieldExtractionResult<>(null, "fallback", null);
    }

    private FieldExtractionResult<String> extractFromSummaryTable(java.util.List<LineIndexingService.IndexedLine> lines,
            Double total) {
        AmountUtil.SummaryAmounts summary = AmountUtil.extractSummaryAmounts(lines);
        if (summary != null && summary.getTax() != null) {
            Double value = summary.getTax();
            if (total == null || value < total) {
                return new FieldExtractionResult<>(AmountUtil.formatAmount(value), "keyword", summary.getLineNumber());
            }
        }
        return new FieldExtractionResult<>(null, "fallback", null);
    }
}
