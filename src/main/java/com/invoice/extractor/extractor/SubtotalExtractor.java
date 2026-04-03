package com.invoice.extractor.extractor;

import com.invoice.extractor.service.impl.LineIndexingService;
import com.invoice.extractor.util.AmountUtil;
public class SubtotalExtractor implements FieldExtractor<String> {
    @Override
    public String extract(String[] lines, int[] zones) {
        return null;
    }

    public String extract(LineIndexingService.Zones zones, Double total, Double tax) {
        return extractResult(zones, total, tax).getValue();
    }

    public FieldExtractionResult<String> extractResult(LineIndexingService.Zones zones, Double total, Double tax) {
        FieldExtractionResult<String> keywordResult = extractFromKeywordLines(zones.bottomZone);
        if (keywordResult.getValue() == null) {
            keywordResult = extractFromKeywordLines(zones.tableZone);
        }
        if (keywordResult.getValue() == null) {
            keywordResult = extractFromSummaryTable(zones.bottomZone);
        }
        if (keywordResult.getValue() == null) {
            keywordResult = extractFromSummaryTable(zones.tableZone);
        }
        if (keywordResult.getValue() != null) {
            return keywordResult;
        }

        if (total != null && tax != null) {
            double subtotal = total - tax;
            if (subtotal > 0 && subtotal < total) {
                return new FieldExtractionResult<>(AmountUtil.formatAmount(subtotal), "fallback", null);
            }
        }
        return new FieldExtractionResult<>(null, "fallback", null);
    }

    private FieldExtractionResult<String> extractFromSummaryTable(java.util.List<LineIndexingService.IndexedLine> lines) {
        AmountUtil.SummaryAmounts summary = AmountUtil.extractSummaryAmounts(lines);
        if (summary != null && summary.getSubtotal() != null) {
            return new FieldExtractionResult<>(AmountUtil.formatAmount(summary.getSubtotal()), "keyword", summary.getLineNumber());
        }
        return new FieldExtractionResult<>(null, "fallback", null);
    }

    private FieldExtractionResult<String> extractFromKeywordLines(java.util.List<LineIndexingService.IndexedLine> lines) {
        Double keywordAmount = AmountUtil.extractBestAmountByKeywords(lines, AmountUtil.SUBTOTAL_KEYWORDS, true);
        if (keywordAmount != null) {
            for (LineIndexingService.IndexedLine line : lines) {
                if (AmountUtil.isPreferredAmountLine(line.getText(), AmountUtil.SUBTOTAL_KEYWORDS)) {
                    return new FieldExtractionResult<>(AmountUtil.formatAmount(keywordAmount), "keyword", line.getLineNumber());
                }
            }
        }
        for (LineIndexingService.IndexedLine line : lines) {
            if (!AmountUtil.isPreferredAmountLine(line.getText(), AmountUtil.SUBTOTAL_KEYWORDS)) {
                continue;
            }
            for (AmountUtil.AmountCandidate candidate : AmountUtil.extractCandidates(java.util.List.of(line))) {
                if (!candidate.isPercentToken()) {
                    return new FieldExtractionResult<>(AmountUtil.formatAmount(candidate.getValue()), "keyword", line.getLineNumber());
                }
            }
        }
        return new FieldExtractionResult<>(null, "fallback", null);
    }
}
