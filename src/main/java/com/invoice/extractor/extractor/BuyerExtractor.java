package com.invoice.extractor.extractor;

import com.invoice.extractor.service.impl.LineIndexingService;
import com.invoice.extractor.util.DateUtil;
import com.invoice.extractor.util.OcrLayoutUtil;
import com.invoice.extractor.util.RegexUtil;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

public class BuyerExtractor implements FieldExtractor<String> {
    private static final List<String> TRIGGERS = List.of(
            "buyer (bill to)", "buyer", "bill to", "billed to", "details of recipient",
            "details of receiver", "consignee", "ship to", "to"
    );
    private static final List<String> ORGANIZATION_KEYWORDS = List.of(
            "department", "directorate", "industries", "solutions", "limited", "private", "stores",
            "corporation", "enterprises", "office", "systems", "agency", "company", "officer",
            "manager", "materials", "complex", "atomic", "fuel", "purchase", "accounts", "regional"
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
        FieldExtractionResult<String> best = null;
        int bestScore = Integer.MIN_VALUE;
        for (String trigger : primaryTriggers()) {
            FieldExtractionResult<String> result = extractForTrigger(zones, buyerGstin, trigger);
            if (result.getValue() != null) {
                int score = scoreExtractedBlock(result, zones, buyerGstin);
                if (score > bestScore) {
                    best = result;
                    bestScore = score;
                }
            }
        }
        if (best != null) {
            return best;
        }

        FieldExtractionResult<String> nearGstin = extractNearBuyerGstin(zones, buyerGstin);
        if (nearGstin.getValue() != null) {
            int score = scoreExtractedBlock(nearGstin, zones, buyerGstin);
            if (score > bestScore) {
                best = nearGstin;
                bestScore = score;
            }
        }

        for (String trigger : fallbackTriggers()) {
            FieldExtractionResult<String> result = extractForTrigger(zones, buyerGstin, trigger);
            if (result.getValue() != null) {
                int score = scoreExtractedBlock(result, zones, buyerGstin);
                if (score > bestScore) {
                    best = result;
                    bestScore = score;
                }
            }
        }

        return best != null ? best : new FieldExtractionResult<>(null, "fallback", null);
    }

    private FieldExtractionResult<String> extractForTrigger(LineIndexingService.Zones zones, String buyerGstin,
            String trigger) {
        FieldExtractionResult<String> best = new FieldExtractionResult<>(null, "fallback", null);
        int bestScore = Integer.MIN_VALUE;
        for (int i = 0; i < zones.middleZone.size(); i++) {
            String lower = zones.middleZone.get(i).getText().toLowerCase(Locale.ROOT);
            if (!matchesTriggerLine(lower, trigger)) {
                continue;
            }
            List<String> block = new ArrayList<>();
            Integer startLine = null;
            String inlineCandidate = inlineBuyerName(zones.middleZone.get(i).getText(), trigger);
            if (isValid(inlineCandidate)) {
                block.add(inlineCandidate);
                startLine = zones.middleZone.get(i).getLineNumber();
            }
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
                FieldExtractionResult<String> candidate = new FieldExtractionResult<>(
                        compact,
                        "keyword",
                        startLine == null ? zones.middleZone.get(i).getLineNumber() : startLine
                );
                int score = scoreExtractedBlock(candidate, zones, buyerGstin) + triggerPriority(trigger);
                if (score > bestScore) {
                    best = candidate;
                    bestScore = score;
                }
            }
        }
        return best;
    }

    private FieldExtractionResult<String> extractNearBuyerGstin(LineIndexingService.Zones zones, String buyerGstin) {
        if (buyerGstin == null) {
            return new FieldExtractionResult<>(null, "fallback", null);
        }
        FieldExtractionResult<String> best = new FieldExtractionResult<>(null, "fallback", null);
        int bestScore = Integer.MIN_VALUE;
        String normalizedGstin = buyerGstin.replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
        for (int i = 0; i < zones.middleZone.size(); i++) {
            String compactLine = zones.middleZone.get(i).getText().replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
            if (!compactLine.contains(normalizedGstin)) {
                continue;
            }
            List<String> block = new ArrayList<>();
            Integer startLine = null;
            for (int j = Math.max(0, i - 4); j < i; j++) {
                String raw = zones.middleZone.get(j).getText();
                if (stopCondition(raw, buyerGstin)) {
                    block.clear();
                    startLine = null;
                    continue;
                }
                String sanitized = sanitizeBuyerLine(raw);
                if (isValid(sanitized)) {
                    if (block.isEmpty() || !block.get(block.size() - 1).equalsIgnoreCase(sanitized)) {
                        block.add(sanitized);
                    }
                    if (startLine == null) {
                        startLine = zones.middleZone.get(j).getLineNumber();
                    }
                }
            }
            String compact = compactBlock(block);
            if (isValid(compact)) {
                FieldExtractionResult<String> candidate = new FieldExtractionResult<>(compact, "regex", startLine);
                int score = scoreExtractedBlock(candidate, zones, buyerGstin);
                if (score > bestScore) {
                    best = candidate;
                    bestScore = score;
                }
            }
        }
        return best;
    }

    private int scoreExtractedBlock(FieldExtractionResult<String> result,
                                    LineIndexingService.Zones zones,
                                    String buyerGstin) {
        if (result == null || result.getValue() == null) {
            return Integer.MIN_VALUE;
        }
        String value = result.getValue();
        String lower = value.toLowerCase(Locale.ROOT);
        int score = 30;
        score += isValid(value) ? 25 : -80;
        score += RegexUtil.containsAnyKeyword(lower, ORGANIZATION_KEYWORDS) ? 30 : 0;
        score += lower.contains("purchase unit") || lower.contains("regional purchase unit") ? 18 : 0;
        score += lower.startsWith("m/s") ? 18 : 0;
        score += OcrLayoutUtil.isGovernmentLike(lower) ? 12 : 0;
        score += !value.matches(".*\\d.*") ? 10 : -12;
        score += value.length() > 80 ? -18 : Math.max(0, 22 - value.length() / 4);
        score += OcrLayoutUtil.isAddressLike(lower) ? -35 : 0;
        score += OcrLayoutUtil.isLogisticsLike(lower) ? -90 : 0;
        score += containsTrigger(lower) ? -70 : 0;
        if (result.getLineNumber() != null) {
            String zone = zones.zoneForLineNumber(result.getLineNumber());
            if ("MIDDLE".equals(zone)) {
                score += 20;
            } else if ("TOP".equals(zone) || "BOTTOM".equals(zone)) {
                score -= 25;
            }
            if (buyerGstin != null) {
                int distance = distanceToBuyerGstin(result.getLineNumber(), zones.middleZone, buyerGstin);
                if (distance >= 0) {
                    score += Math.max(0, 20 - distance * 5);
                }
            }
        }
        return score;
    }

    private int distanceToBuyerGstin(int lineNumber,
                                     List<LineIndexingService.IndexedLine> lines,
                                     String buyerGstin) {
        String normalizedGstin = buyerGstin == null ? null : buyerGstin.replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
        if (normalizedGstin == null) {
            return -1;
        }
        for (LineIndexingService.IndexedLine line : lines) {
            if (line.getText().replaceAll("\\s+", "").toUpperCase(Locale.ROOT).contains(normalizedGstin)) {
                return Math.abs(line.getLineNumber() - lineNumber);
            }
        }
        return -1;
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
        if (lower.contains("bank") || lower.contains("ifsc") || lower.contains("account")
                || lower.contains("phone") || lower.contains("pan")) {
            return true;
        }
        if (lower.contains("invoice details") || lower.contains("nvoice detals")
                || lower.contains("reference no") || lower.contains("buyers order")
                || lower.contains("buyer's order") || lower.contains("purchase order")
                || lower.contains("mode/terms") || lower.contains("delivery note")) {
            return true;
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
        if (txt.trim().length() < 5) {
            return false;
        }
        if (!txt.matches(".*[A-Za-z].*")) {
            return false;
        }
        String lower = txt.toLowerCase(Locale.ROOT);
        if (lower.contains("gstin") || lower.contains("invoice") || OcrLayoutUtil.isLogisticsLike(lower)) {
            return false;
        }
        if (lower.matches("^[()\\s.:,-]*(?:ship to|bill to|billed to|consignee|buyer|to)[()\\s.:,-]*$")) {
            return false;
        }
        if (lower.contains("original") || lower.contains("duplicate") || lower.contains("copy")) {
            return false;
        }
        if (lower.contains("invoice details") || lower.contains("nvoice detals") || lower.contains("dispatch")
                || lower.contains("reference no") || lower.contains("buyers order")
                || lower.contains("buyer's order") || lower.contains("purchase order")
                || lower.contains("mode/terms") || lower.contains("delivery note")
                || lower.contains("state code") || lower.contains("party pincode")
                || lower.contains("party e-mail") || lower.contains("party mobile")) {
            return false;
        }
        if (lower.contains("bank") || lower.contains("ifsc") || lower.contains("account")
                || lower.contains("phone") || lower.contains("pan")) {
            return false;
        }
        if (!DateUtil.findCandidateDates(txt).isEmpty()) {
            return false;
        }
        int alphaWords = 0;
        for (String word : txt.split("\\s+")) {
            if (word.matches(".*[A-Za-z].*") && !word.matches(".*\\d.*")) {
                alphaWords++;
            }
        }
        if (alphaWords < 2 && !RegexUtil.containsAnyKeyword(lower, ORGANIZATION_KEYWORDS)) {
            return false;
        }
        if ("apo".equals(lower) || "po".equals(lower)) {
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
        } else if (compact.size() > 2 && !isGenericRoleLine(compact.get(0))) {
            compact = compact.subList(0, 2);
        } else if (compact.size() > 1 && isGenericRoleLine(compact.get(0)) && shouldAppendRoleContext(compact.get(1))) {
            compact = compact.subList(0, Math.min(2, compact.size()));
        }
        return String.join(", ", compact);
    }

    private boolean shouldAppend(String first, String second) {
        String lower = second.toLowerCase(Locale.ROOT);
        return !OcrLayoutUtil.isAddressLike(lower)
                && !lower.matches(".*\\b\\d{5,6}\\b.*")
                && !lower.contains("invoice details")
                && !lower.contains("buyers order")
                && !lower.contains("buyer's order")
                && !lower.contains("building")
                && !lower.contains("floor")
                && !lower.contains("post")
                && (RegexUtil.containsAnyKeyword(lower, ORGANIZATION_KEYWORDS) || second.length() <= 40);
    }

    private String trimTrailingNoise(String value) {
        String normalized = RegexUtil.normalizeLine(value);
        normalized = normalized.replaceAll("(?i)\\b(?:details of recipient|details of consignee)\\b", "").trim();
        normalized = normalized.replaceFirst("(?i)\\bGEMC[-A-Z0-9/]{6,}.*$", "").trim();
        normalized = normalized.replaceFirst("(?i)\\b(?:po|order|doc)\\s*no\\.?\\s*[:#-]*\\s*[A-Z0-9/-]{4,}.*$", "").trim();
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
        if (lower.startsWith("name ")) {
            score += 10;
        }
        if (OcrLayoutUtil.isAddressLike(lower) || OcrLayoutUtil.isLogisticsLike(lower)) {
            score -= 60;
        }
        if (lower.contains("bank") || lower.contains("ifsc") || lower.contains("account")
                || lower.contains("phone") || lower.contains("pan")) {
            score -= 80;
        }
        if (lower.contains("invoice details") || lower.contains("buyers order") || lower.contains("buyer's order")
                || lower.contains("purchase order") || lower.contains("reference no")) {
            score -= 120;
        }
        if (lower.contains("building") || lower.contains("floor") || lower.contains("road") || lower.contains("post")) {
            score -= 30;
        }
        if (!DateUtil.findCandidateDates(value).isEmpty()) {
            score -= 60;
        }
        if (value.length() > 80) {
            score -= 20;
        }
        return score;
    }

    private boolean matchesTriggerLine(String text, String trigger) {
        String lower = text == null ? "" : text.toLowerCase(Locale.ROOT);
        if ("to".equals(trigger)) {
            for (String fragment : OcrLayoutUtil.fragments(text)) {
                String normalized = RegexUtil.normalizeLine(fragment).toLowerCase(Locale.ROOT);
                if (normalized.matches("^to[,:.]?$") || normalized.matches("^invoice to[,:.]?$")) {
                    return true;
                }
            }
            return false;
        }
        if ((trigger.equals("buyer") || trigger.equals("buyer (bill to)")) && lower.contains("buyer's order")) {
            return false;
        }
        if (lower.contains("buyers order") || lower.contains("purchase order") || lower.contains("reference no")) {
            return false;
        }
        if (lower.contains("original for") || lower.contains("duplicate for") || lower.contains("triplicate for")) {
            return false;
        }
        return lower.contains(trigger);
    }

    private String inlineBuyerName(String text, String trigger) {
        for (String fragment : OcrLayoutUtil.fragments(text)) {
            String normalized = RegexUtil.normalizeLine(fragment);
            String lower = normalized.toLowerCase(Locale.ROOT);
            String candidate = normalized;
            if ("to".equals(trigger)) {
                candidate = normalized.replaceFirst("(?i)^invoice\\s+to\\s*[:,-]?\\s*", "");
                candidate = candidate.replaceFirst("(?i)^to\\s*[:,-]?\\s*", "");
            } else if (lower.contains(trigger)) {
                candidate = normalized.replaceFirst("(?i)^.*?" + Pattern.quote(trigger) + "\\s*[:,-]?\\s*", "");
            }
            candidate = candidate.replaceFirst("(?i)^\\(?\\s*(?:ship to|bill to|billed to|consignee|buyer)\\s*\\)?\\s*[:,-]?\\s*", "");
            candidate = stripFieldLabels(trimTrailingNoise(
                    OcrLayoutUtil.truncateAtKeyword(candidate, OcrLayoutUtil.BUYER_STOP_KEYWORDS)));
            if (isValid(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private boolean isGenericRoleLine(String value) {
        String lower = value == null ? "" : value.toLowerCase(Locale.ROOT);
        return lower.matches(".*\\b(?:stores officer|asst\\. stores officer|assistant stores officer|materials manager|sr\\. manager materials|manager materials)\\b.*")
                && !lower.startsWith("m/s");
    }

    private boolean shouldAppendRoleContext(String value) {
        String lower = value == null ? "" : value.toLowerCase(Locale.ROOT);
        return RegexUtil.containsAnyKeyword(lower, ORGANIZATION_KEYWORDS)
                && !OcrLayoutUtil.isAddressLike(lower)
                && !lower.contains("building")
                && !lower.contains("floor")
                && !lower.contains("post")
                && !lower.matches(".*\\b\\d{5,6}\\b.*");
    }

    private int triggerPriority(String trigger) {
        String lower = trigger == null ? "" : trigger.toLowerCase(Locale.ROOT);
        if (lower.contains("buyer") || lower.contains("bill to") || lower.contains("billed to")
                || lower.contains("recipient") || lower.contains("receiver")) {
            return 60;
        }
        if (lower.contains("consignee") || lower.contains("ship to")) {
            return 8;
        }
        return 0;
    }

    private List<String> primaryTriggers() {
        return List.of("buyer (bill to)", "buyer", "bill to", "billed to", "details of recipient", "details of receiver");
    }

    private List<String> fallbackTriggers() {
        return List.of("consignee", "ship to", "to");
    }
}
