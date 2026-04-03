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

        AmountUtil.AmountCandidate best = null;
        for (AmountUtil.AmountCandidate candidate : AmountUtil.extractCandidates(zones.bottomZone)) {
            String lineText = candidate.getLine().getText();
            String lower = lineText.toLowerCase(java.util.Locale.ROOT);
            boolean preferredLine = AmountUtil.isPreferredAmountLine(lineText, AmountUtil.TOTAL_KEYWORDS);
            boolean lineHasCurrencyToken = AmountUtil.extractRawNumericTokens(lineText).stream().anyMatch(AmountUtil::looksLikeCurrencyToken);
            
            if (candidate.isPercentToken() || AmountUtil.isIgnoredAmountLine(lineText) || lower.contains("taxable")) {
                continue;
            }
            if (AmountUtil.isTaxLine(lineText) && !preferredLine) {
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
}
