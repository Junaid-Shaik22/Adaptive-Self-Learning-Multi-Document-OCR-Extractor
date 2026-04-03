package com.invoice.extractor.extractor;

import com.invoice.extractor.service.impl.LineIndexingService;
import com.invoice.extractor.util.OcrLayoutUtil;
import com.invoice.extractor.util.RegexUtil;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

public class BuyerExtractor implements FieldExtractor<String> {
    private static final List<String> TRIGGERS = List.of("buyer (bill to)", "buyer", "bill to", "billed to", "consignee", "ship to");
    private static final List<String> ORGANIZATION_KEYWORDS = List.of(
            "department", "directorate", "industries", "solutions", "limited", "private", "stores",
            "corporation", "enterprises", "office", "systems", "agency", "company"
    );
    private static final Pattern FIELD_LABEL_PATTERN = Pattern.compile(
            "(?i)\\b(?:name|address|state(?:\\s*code)?|gst(?:in| no|in/uin)?|pin\\s*code|place\\s*of\\s*supply)\\b\\s*[:.-]*\\s*"
    );

    @Override
    public String extract(String[] lines, int[] zones) {
        return null;
    }

    public String extract(LineIndexingService.Zones zones, String buyerGstin) {
        return extractResult(zones, buyerGstin).getValue();
    }

    public FieldExtractionResult<String> extractResult(LineIndexingService.Zones zones, String buyerGstin) {
        for (String trigger : TRIGGERS) {
            FieldExtractionResult<String> result = extractForTrigger(zones, buyerGstin, trigger);
            if (result.getValue() != null) {
                return result;
            }
        }

        if (buyerGstin != null) {
            for (int i = 0; i < zones.middleZone.size(); i++) {
                if (!zones.middleZone.get(i).getText().replaceAll("\\s+", "").contains(buyerGstin)) {
                    continue;
                }
                List<String> block = new ArrayList<>();
                Integer startLine = null;
                for (int j = Math.max(0, i - 3); j < i; j++) {
                    String text = sanitizeBuyerLine(zones.middleZone.get(j).getText());
                    if (isValid(text)) {
                        if (block.isEmpty() || !block.get(block.size() - 1).equalsIgnoreCase(text)) {
                            block.add(text);
                        }
                        if (startLine == null) {
                            startLine = zones.middleZone.get(j).getLineNumber();
                        }
                    }
                }
                String compact = compactBlock(block);
                if (isValid(compact)) {
                    return new FieldExtractionResult<>(compact, "regex", startLine);
                }
            }
        }

        return new FieldExtractionResult<>(null, "fallback", null);
    }

    private FieldExtractionResult<String> extractForTrigger(LineIndexingService.Zones zones, String buyerGstin,
            String trigger) {
        for (int i = 0; i < zones.middleZone.size(); i++) {
            String lower = zones.middleZone.get(i).getText().toLowerCase(Locale.ROOT);
            if (!matchesTriggerLine(lower, trigger)) {
                continue;
            }
            List<String> block = new ArrayList<>();
            Integer startLine = null;
            for (int j = i + 1; j < zones.middleZone.size(); j++) {
                String text = zones.middleZone.get(j).getText();
                if (stopCondition(text, buyerGstin)) {
                    break;
                }
                String sanitized = sanitizeBuyerLine(text);
                if (isValid(sanitized)) {
                    if (block.isEmpty() || !block.get(block.size() - 1).equalsIgnoreCase(sanitized)) {
                        block.add(sanitized);
                    }
                    if (startLine == null) {
                        startLine = zones.middleZone.get(j).getLineNumber();
                    }
                }
                if (!block.isEmpty() && isAddressContinuation(text)) {
                    break;
                }
            }
            String compact = compactBlock(block);
            if (isValid(compact)) {
                return new FieldExtractionResult<>(compact, "keyword",
                        startLine == null ? zones.middleZone.get(i).getLineNumber() : startLine);
            }
        }
        return new FieldExtractionResult<>(null, "fallback", null);
    }

    private boolean containsTrigger(String value) {
        for (String trigger : TRIGGERS) {
            if (matchesTriggerLine(value, trigger)) {
                return true;
            }
        }
        return false;
    }

    private boolean stopCondition(String txt, String gstin) {
        String lower = txt.toLowerCase(Locale.ROOT);
        if (containsTrigger(lower) || lower.contains("gstin")) {
            if (gstin != null) {
                return txt.replaceAll("\\s+", "").contains(gstin);
            }
            return lower.contains("gstin") && !lower.contains("transport") && !lower.contains("dispatch");
        }
        if (lower.contains("description") || lower.contains("grand total") || lower.contains("amount payable")
                || OcrLayoutUtil.isLogisticsLike(lower)) {
            return true;
        }
        return gstin != null && txt.replaceAll("\\s+", "").contains(gstin);
    }

    private String sanitizeBuyerLine(String txt) {
        String best = null;
        int bestScore = Integer.MIN_VALUE;
        for (String fragment : OcrLayoutUtil.fragments(txt)) {
            for (String part : fragment.split("\\s*,\\s*")) {
                String candidate = stripFieldLabels(trimTrailingNoise(
                        OcrLayoutUtil.truncateAtKeyword(part, OcrLayoutUtil.BUYER_STOP_KEYWORDS)));
                int score = scoreNameCandidate(candidate);
                if (score > bestScore) {
                    bestScore = score;
                    best = candidate;
                }
            }
        }
        return best == null ? stripFieldLabels(RegexUtil.normalizeLine(txt)) : best;
    }

    private boolean isValid(String txt) {
        if (txt == null || txt.isBlank()) {
            return false;
        }
        if (!txt.matches(".*[A-Za-z].*")) {
            return false;
        }
        String lower = txt.toLowerCase(Locale.ROOT);
        if (lower.contains("gstin") || lower.contains("invoice") || OcrLayoutUtil.isLogisticsLike(lower)) {
            return false;
        }
        return !txt.matches("\\d+");
    }

    private boolean isAddressContinuation(String text) {
        String lower = text == null ? "" : text.toLowerCase(Locale.ROOT);
        return OcrLayoutUtil.isAddressLike(lower) || lower.matches(".*\\b\\d{5,6}\\b.*");
    }

    private String compactBlock(List<String> block) {
        if (block.isEmpty()) {
            return null;
        }
        List<String> compact = new ArrayList<>();
        for (String line : block) {
            String sanitized = stripFieldLabels(line);
            if (!isValid(sanitized)) {
                continue;
            }
            compact.add(sanitized);
            if (compact.size() >= 3) {
                break;
            }
        }
        if (compact.isEmpty()) {
            return null;
        }
        if (compact.size() > 1 && !shouldAppend(compact.get(0), compact.get(1))) {
            compact = compact.subList(0, 1);
        }
        return String.join(", ", compact);
    }

    private boolean shouldAppend(String first, String second) {
        String lower = second.toLowerCase(Locale.ROOT);
        return !OcrLayoutUtil.isAddressLike(lower)
                && !lower.matches(".*\\b\\d{5,6}\\b.*")
                && (RegexUtil.containsAnyKeyword(lower, ORGANIZATION_KEYWORDS) || second.length() <= 40);
    }

    private String trimTrailingNoise(String value) {
        String normalized = RegexUtil.normalizeLine(value);
        normalized = normalized.replaceAll("(?i)\\b(?:details of recipient|details of consignee)\\b", "").trim();
        while (normalized.matches(".*\\b[a-z]{1,4}$")) {
            normalized = normalized.replaceFirst("\\s+[a-z]{2,4}$", "").trim();
        }
        return normalized;
    }

    private String stripFieldLabels(String value) {
        String cleaned = RegexUtil.normalizeLine(value);
        cleaned = FIELD_LABEL_PATTERN.matcher(cleaned).replaceAll("");
        cleaned = cleaned.replaceAll("(?i)^m/s\\.?\\s*", "M/s. ");
        return cleaned.replaceAll("\\s{2,}", " ").trim();
    }

    private int scoreNameCandidate(String value) {
        if (value == null || value.isBlank()) {
            return Integer.MIN_VALUE;
        }
        int score = 0;
        String lower = value.toLowerCase(Locale.ROOT);
        if (value.matches(".*[A-Za-z].*")) {
            score += 30;
        }
        if (!value.matches(".*\\d.*")) {
            score += 20;
        }
        if (RegexUtil.containsAnyKeyword(lower, ORGANIZATION_KEYWORDS)) {
            score += 30;
        }
        if (lower.startsWith("m/s")) {
            score += 15;
        }
        if (OcrLayoutUtil.isAddressLike(lower) || OcrLayoutUtil.isLogisticsLike(lower)) {
            score -= 60;
        }
        if (value.length() > 80) {
            score -= 20;
        }
        return score;
    }

    private boolean matchesTriggerLine(String text, String trigger) {
        String lower = text == null ? "" : text.toLowerCase(Locale.ROOT);
        if ((trigger.equals("buyer") || trigger.equals("buyer (bill to)")) && lower.contains("buyer's order")) {
            return false;
        }
        return lower.contains(trigger);
    }
}
