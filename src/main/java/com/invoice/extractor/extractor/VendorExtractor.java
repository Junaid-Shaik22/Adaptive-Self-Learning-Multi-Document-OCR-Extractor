package com.invoice.extractor.extractor;

import com.invoice.extractor.service.impl.LineIndexingService;
import com.invoice.extractor.util.DateUtil;
import com.invoice.extractor.util.OcrLayoutUtil;
import com.invoice.extractor.util.RegexUtil;
import java.util.List;
import java.util.Locale;

public class VendorExtractor implements FieldExtractor<String> {
    private static final List<String> COMPANY_KEYWORDS = List.of(
            "ltd", "limited", "pvt", "corporation", "industries", "enterprises", "solutions"
    );

    @Override
    public String extract(String[] lines, int[] zones) {
        return null;
    }

    public String extract(LineIndexingService.Zones zones, String vendorGstin) {
        return extractResult(zones, vendorGstin).getValue();
    }

    public FieldExtractionResult<String> extractResult(LineIndexingService.Zones zones, String vendorGstin) {
        FieldExtractionResult<String> nearGstin = findNearGstin(zones.topZone, vendorGstin);
        if (nearGstin.getValue() != null) {
            return nearGstin;
        }

        FieldExtractionResult<String> keywordMatch = findBest(zones.topZone, true, "keyword");
        if (keywordMatch.getValue() != null) {
            return keywordMatch;
        }

        return findBest(zones.topZone, false, "fallback");
    }

    private FieldExtractionResult<String> findNearGstin(List<LineIndexingService.IndexedLine> lines, String vendorGstin) {
        if (vendorGstin == null) {
            return new FieldExtractionResult<>(null, "fallback", null);
        }
        for (int i = 0; i < lines.size(); i++) {
            if (!lines.get(i).getText().replaceAll("\\s+", "").contains(vendorGstin)) {
                continue;
            }
            String best = null;
            Integer lineNumber = null;
            int bestScore = Integer.MIN_VALUE;
            for (int j = Math.max(0, i - 10); j < i; j++) {
                for (String candidate : OcrLayoutUtil.fragments(lines.get(j).getText())) {
                    String sanitized = sanitizeCandidate(candidate);
                    if (isValid(sanitized)) {
                        boolean hasKeyword = containsCompanyKeyword(sanitized);
                        int score = scoreCandidate(sanitized, hasKeyword, lines.get(j).getLineNumber());
                        if (score > bestScore) {
                            bestScore = score;
                            best = sanitized;
                            lineNumber = lines.get(j).getLineNumber();
                        }
                    }
                }
            }
            if (best != null) {
                return new FieldExtractionResult<>(best, containsCompanyKeyword(best) ? "keyword" : "regex", lineNumber);
            }
        }
        return new FieldExtractionResult<>(null, "fallback", null);
    }

    private FieldExtractionResult<String> findBest(List<LineIndexingService.IndexedLine> lines, boolean requireCompanyKeyword, String method) {
        String best = null;
        Integer lineNumber = null;
        int bestScore = Integer.MIN_VALUE;
        for (LineIndexingService.IndexedLine line : lines) {
            for (String text : OcrLayoutUtil.fragments(line.getText())) {
                String sanitized = sanitizeCandidate(text);
                if (!isValid(sanitized)) {
                    continue;
                }
                boolean hasKeyword = containsCompanyKeyword(sanitized);
                if (requireCompanyKeyword && !hasKeyword) {
                    continue;
                }
                int score = scoreCandidate(sanitized, hasKeyword, line.getLineNumber());
                if (score > bestScore) {
                    bestScore = score;
                    best = sanitized;
                    lineNumber = line.getLineNumber();
                }
            }
        }
        return new FieldExtractionResult<>(best, best == null ? "fallback" : method, lineNumber);
    }

    private boolean containsCompanyKeyword(String txt) {
        return RegexUtil.containsAnyKeyword(txt.toLowerCase(Locale.ROOT), COMPANY_KEYWORDS);
    }

    private boolean isValid(String txt) {
        if (txt == null || txt.length() < 5 || txt.length() > 120) {
            return false;
        }
        String lower = txt.toLowerCase(Locale.ROOT);
        if (lower.contains("invoice") || lower.contains("tax") || lower.contains("bill") || lower.contains("gstin")
                || lower.contains("date") || lower.contains("bank") || lower.contains("ship to")
                || lower.contains("consignee") || lower.contains("buyer") || lower.contains("department")
                || lower.contains("reference")) {
            return false;
        }
        if (!DateUtil.findCandidateDates(txt).isEmpty()) {
            return false;
        }
        if (OcrLayoutUtil.isAddressLike(lower)) {
            return false;
        }
        if (OcrLayoutUtil.isLogisticsLike(lower)) {
            return false;
        }
        if (!txt.matches(".*[A-Za-z].*")) {
            return false;
        }
        String[] words = txt.trim().split("\\s+");
        int wordCount = 0;
        for (String word : words) {
            if (word.matches(".*[A-Za-z].*")) {
                wordCount++;
            }
        }
        return wordCount >= 2 && !txt.matches("\\d+");
    }

    private int scoreCandidate(String text, boolean hasKeyword, int lineNumber) {
        int score = 0;
        if (hasKeyword) {
            score += 60;
        }
        score += Math.max(0, 40 - lineNumber);
        score += Math.max(0, 40 - text.length());
        if (OcrLayoutUtil.isAddressLike(text.toLowerCase(Locale.ROOT))) {
            score -= 60;
        }
        if (text.contains(",")) {
            score -= 20;
        }
        if (!DateUtil.findCandidateDates(text).isEmpty()) {
            score -= 80;
        }
        return score;
    }

    private String sanitizeCandidate(String value) {
        String normalized = RegexUtil.normalizeLine(value);
        normalized = OcrLayoutUtil.truncateAtKeyword(normalized, OcrLayoutUtil.HEADER_METADATA_KEYWORDS);
        normalized = OcrLayoutUtil.truncateAtKeyword(normalized, OcrLayoutUtil.BUYER_STOP_KEYWORDS);
        normalized = normalized.replaceAll("(?i)^tax invoice\\s*", "").trim();

        String lower = normalized.toLowerCase(Locale.ROOT);
        int suffixIndex = lastCompanySuffixIndex(lower);
        if (suffixIndex >= 0) {
            String tail = normalized.substring(suffixIndex);
            if (!tail.matches("(?i).*(?:\\(\\d{4}[-/]\\d{2,4}\\)|\\b(?:ltd|limited|pvt|llp|industries|corporation|systems|solutions)\\b).*")) {
                normalized = normalized.substring(0, suffixIndex).trim();
            } else if (normalized.matches(".*\\b(?:ltd|limited|pvt|llp)\\b\\s+[A-Za-z]{2,20}.*")) {
                normalized = normalized.replaceFirst("(?i)(\\b(?:ltd|limited|llp)\\b).*$", "$1").trim();
            }
        }
        return normalized.replaceAll("\\s+[a-z]{1,3}$", "").trim();
    }

    private int lastCompanySuffixIndex(String lower) {
        int best = -1;
        for (String keyword : COMPANY_KEYWORDS) {
            int index = lower.lastIndexOf(keyword);
            if (index > best) {
                best = index;
            }
        }
        return best;
    }
}
