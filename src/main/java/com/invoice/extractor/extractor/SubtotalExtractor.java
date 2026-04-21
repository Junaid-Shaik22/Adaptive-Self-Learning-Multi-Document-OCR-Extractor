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
                if (isCandidatePresent(subtotal, zones)) {
                    return new FieldExtractionResult<>(AmountUtil.formatAmount(subtotal), "fallback", null);
                }
            }
        }
        if (total != null) {
            FieldExtractionResult<String> tableCandidate = extractLargestCandidateBelowTotal(zones.tableZone, total);
            if (tableCandidate.getValue() != null) {
                return tableCandidate;
            }
            FieldExtractionResult<String> bottomCandidate = extractLargestCandidateBelowTotal(zones.bottomZone, total);
            if (bottomCandidate.getValue() != null) {
                return bottomCandidate;
            }
        }
        return new FieldExtractionResult<>(null, "fallback", null);
    }

    private boolean isCandidatePresent(double amount, LineIndexingService.Zones zones) {
        java.util.List<LineIndexingService.IndexedLine> candidateLines = new java.util.ArrayList<>();
        if (zones.tableZone != null) candidateLines.addAll(zones.tableZone);
        if (zones.bottomZone != null) candidateLines.addAll(zones.bottomZone);
        for (AmountUtil.AmountCandidate candidate : AmountUtil.extractCandidates(candidateLines)) {
            if (!candidate.isPercentToken() && AmountUtil.approximatelyEquals(candidate.getValue(), amount)) {
                return true;
            }
        }
        return false;
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

    private FieldExtractionResult<String> extractLargestCandidateBelowTotal(java.util.List<LineIndexingService.IndexedLine> lines,
                                                                            double total) {
        AmountUtil.AmountCandidate best = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (AmountUtil.AmountCandidate candidate : AmountUtil.extractCandidates(lines)) {
            if (candidate.isPercentToken() || candidate.getValue() <= 0 || candidate.getValue() >= total) {
                continue;
            }
            String lower = candidate.getLine().getText().toLowerCase();
            if (lower.contains("bank") || lower.contains("ifsc") || lower.contains("account")) {
                continue;
            }
            double score = candidate.getValue();
            score += AmountUtil.isPreferredAmountLine(candidate.getLine().getText(), AmountUtil.SUBTOTAL_KEYWORDS) ? 250 : 0;
            score -= AmountUtil.isPreferredAmountLine(candidate.getLine().getText(), AmountUtil.TAX_KEYWORDS) ? 250 : 0;
            score -= lower.contains("total") && !AmountUtil.isPreferredAmountLine(candidate.getLine().getText(), AmountUtil.SUBTOTAL_KEYWORDS) ? 80 : 0;
            if (score > bestScore) {
                bestScore = score;
                best = candidate;
            }
        }
        return best == null
                ? new FieldExtractionResult<>(null, "fallback", null)
                : new FieldExtractionResult<>(AmountUtil.formatAmount(best.getValue()), "fallback", best.getLine().getLineNumber());
    }
}
