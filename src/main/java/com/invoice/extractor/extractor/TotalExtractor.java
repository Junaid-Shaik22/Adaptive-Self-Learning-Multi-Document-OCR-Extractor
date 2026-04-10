package com.invoice.extractor.extractor;

import com.invoice.extractor.service.impl.LineIndexingService;
import com.invoice.extractor.util.AmountUtil;
public class TotalExtractor implements FieldExtractor<String> {
    @Override
    public String extract(String[] lines, int[] zones) {
        return null;
    }

    public String extract(LineIndexingService.Zones zones, Double taxAmount) {
        return extractResult(zones, taxAmount).getValue();
    }

    public FieldExtractionResult<String> extractResult(LineIndexingService.Zones zones, Double taxAmount) {
        FieldExtractionResult<String> adjacentTotal = extractAdjacentTotal(zones.bottomZone, taxAmount);
        if (adjacentTotal.getValue() != null) {
            return adjacentTotal;
        }

        Double keywordAmount = AmountUtil.extractBestAmountByKeywords(zones.bottomZone, AmountUtil.TOTAL_KEYWORDS, true);
        if (keywordAmount != null && (taxAmount == null || keywordAmount > taxAmount)) {
            Integer lineNumber = null;
            for (LineIndexingService.IndexedLine line : zones.bottomZone) {
                if (AmountUtil.isPreferredAmountLine(line.getText(), AmountUtil.TOTAL_KEYWORDS)
                        && AmountUtil.extractCandidates(java.util.List.of(line)).stream().anyMatch(candidate -> Double.compare(candidate.getValue(), keywordAmount) == 0)) {
                    lineNumber = line.getLineNumber();
                    break;
                }
            }
            return new FieldExtractionResult<>(AmountUtil.formatAmount(keywordAmount), "keyword", lineNumber);
        }

        AmountUtil.AmountCandidate best = null;
        for (AmountUtil.AmountCandidate candidate : AmountUtil.extractCandidates(zones.bottomZone)) {
            String lineText = candidate.getLine().getText();
            String lower = lineText.toLowerCase(java.util.Locale.ROOT);
            boolean preferredLine = AmountUtil.isPreferredAmountLine(lineText, AmountUtil.TOTAL_KEYWORDS);
            boolean lineHasCurrencyToken = AmountUtil.extractRawNumericTokens(lineText).stream().anyMatch(AmountUtil::looksLikeCurrencyToken);
            
            if (candidate.isPercentToken() || AmountUtil.isIgnoredAmountLine(lineText) || lower.contains("taxable")
                    || lower.contains("before tax") || lower.contains("amount chargeable")) {
                continue;
            }
            if (AmountUtil.isTaxLine(lineText) && !isExplicitTotalLine(lower)) {
                continue;
            }
            if (!AmountUtil.looksLikeCurrencyToken(candidate.getToken()) && lineHasCurrencyToken) {
                continue;
            }
            if (!preferredLine && !AmountUtil.looksLikeCurrencyToken(candidate.getToken())) {
                continue;
            }
            if (taxAmount != null && candidate.getValue() <= taxAmount) {
                continue;
            }
            if (best == null || candidate.getValue() > best.getValue()
                    || (candidate.getValue() == best.getValue() && preferredLine)) {
                best = candidate;
            }
        }

        if (best == null) {
            return new FieldExtractionResult<>(null, "fallback", null);
        }
        String method = AmountUtil.isPreferredAmountLine(best.getLine().getText(), AmountUtil.TOTAL_KEYWORDS) ? "keyword" : "regex";
        return new FieldExtractionResult<>(AmountUtil.formatAmount(best.getValue()), method, best.getLine().getLineNumber());
    }

    private FieldExtractionResult<String> extractAdjacentTotal(java.util.List<LineIndexingService.IndexedLine> lines, Double taxAmount) {
        for (int i = 0; i < lines.size(); i++) {
            String lower = lines.get(i).getText().toLowerCase(java.util.Locale.ROOT);
            if (!(lower.matches("^total\\b.*") || lower.matches("^teal\\b.*")) || lower.contains("tax")) {
                continue;
            }
            if (hasLaterStrongTotalLine(lines, i + 1)) {
                continue;
            }
            Double best = null;
            Integer lineNumber = null;
            for (int j = i; j < Math.min(lines.size(), i + 3); j++) {
                for (AmountUtil.AmountCandidate candidate : AmountUtil.extractCandidates(java.util.List.of(lines.get(j)))) {
                    if (candidate.isPercentToken() || AmountUtil.isTaxLine(lines.get(j).getText())) {
                        continue;
                    }
                    if (taxAmount != null && candidate.getValue() <= taxAmount) {
                        continue;
                    }
                    if (best == null || candidate.getValue() > best) {
                        best = candidate.getValue();
                        lineNumber = candidate.getLine().getLineNumber();
                    }
                }
            }
            if (best != null) {
                return new FieldExtractionResult<>(AmountUtil.formatAmount(best), "keyword", lineNumber);
            }
        }
        return new FieldExtractionResult<>(null, "fallback", null);
    }

    private boolean hasLaterStrongTotalLine(java.util.List<LineIndexingService.IndexedLine> lines, int startIndex) {
        for (int i = startIndex; i < lines.size(); i++) {
            String lower = lines.get(i).getText().toLowerCase(java.util.Locale.ROOT);
            if (lower.contains("after tax")
                    || lower.contains("invoice value")
                    || lower.contains("inv value")
                    || lower.contains("invoice amt")
                    || lower.contains("amount payable")
                    || lower.contains("grand total")
                    || lower.contains("value (figure)")) {
                return true;
            }
        }
        return false;
    }

    private boolean isExplicitTotalLine(String lower) {
        return lower.contains("grand total")
                || lower.contains("invoice value")
                || lower.contains("inv value")
                || lower.contains("invoice amt")
                || lower.contains("amount payable")
                || lower.contains("after tax")
                || lower.matches("^total\\b.*");
    }
}
