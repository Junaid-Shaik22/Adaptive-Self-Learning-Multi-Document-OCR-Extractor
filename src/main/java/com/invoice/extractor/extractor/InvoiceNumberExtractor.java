package com.invoice.extractor.extractor;

import com.invoice.extractor.service.impl.LineIndexingService;
import com.invoice.extractor.util.DateUtil;
import com.invoice.extractor.util.OcrLayoutUtil;
import com.invoice.extractor.util.RegexUtil;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class InvoiceNumberExtractor implements FieldExtractor<String> {
    private static final List<String> INVOICE_CONTEXT_REJECT_KEYWORDS = List.of(
            "plot", "address", "fac", "mob", "road", "near", "state", "code", "delivery note",
            "mode/terms", "reference", "dispatch", "destination", "bill of lading", "lr-rr", "e-way",
            "eway", "purchase order", "customer po", "transport", "vehicle", "original", "duplicate",
            "recipient", "supplier", "copy", "consignee", "ship to", "bill to", "party gst");

    private static final Pattern KEYWORD_PATTERN = Pattern.compile(
            "(?i)(invoice\\s*(?:no|number|#)|inv\\s*no|bill\\s*(?:no|#))\\s*[:#-]*\\s*([A-Za-z0-9/-]{3,20})");

    @Override
    public String extract(String[] lines, int[] zones) {
        return null;
    }

    public String extract(LineIndexingService.Zones zones) {
        return extractResult(zones).getValue();
    }

    public FieldExtractionResult<String> extractResult(LineIndexingService.Zones zones) {
        Map<String, Integer> freq = new HashMap<>();
        List<LineIndexingService.IndexedLine> candidateLines = keywordWindow(zones.topZone);
        for (LineIndexingService.IndexedLine line : candidateLines) {
            for (String fragment : OcrLayoutUtil.fragments(line.getText())) {
                if (shouldRejectContext(fragment)) {
                    continue;
                }
                for (String token : tokenize(fragment)) {
                    String repaired = RegexUtil.repairInvoiceNumberCandidate(token);
                    if (validate(repaired, false)) {
                        freq.put(repaired, freq.getOrDefault(repaired, 0) + 1);
                    }
                }
            }
        }

        FieldExtractionResult<String> keywordResult = extractFromKeywordLines(zones.topZone, freq);
        if (keywordResult.getValue() != null) {
            return keywordResult;
        }

        String repeated = chooseMostRepeated(freq);
        if (repeated != null) {
            return new FieldExtractionResult<>(repeated, "regex", findLine(zones.topZone, repeated));
        }
        return new FieldExtractionResult<>(null, "fallback", null);
    }

    private List<LineIndexingService.IndexedLine> keywordWindow(List<LineIndexingService.IndexedLine> lines) {
        java.util.LinkedHashSet<LineIndexingService.IndexedLine> window = new java.util.LinkedHashSet<>();
        for (int i = 0; i < lines.size(); i++) {
            if (!containsInvoiceKeyword(lines.get(i).getText().toLowerCase(Locale.ROOT))) {
                continue;
            }
            for (int offset = 0; offset <= 3; offset++) {
                int current = i + offset;
                if (current < lines.size()) {
                    window.add(lines.get(current));
                }
            }
        }
        if (window.isEmpty()) {
            for (int i = 0; i < Math.min(8, lines.size()); i++) {
                window.add(lines.get(i));
            }
        }
        return new java.util.ArrayList<>(window);
    }

    private FieldExtractionResult<String> extractFromKeywordLines(List<LineIndexingService.IndexedLine> lines,
            Map<String, Integer> freq) {
        String bestValue = null;
        int bestLine = -1;
        int bestScore = -1;
        for (int i = 0; i < lines.size(); i++) {
            LineIndexingService.IndexedLine line = lines.get(i);
            String lower = line.getText().toLowerCase(Locale.ROOT);
            if (!containsInvoiceKeyword(lower)) {
                continue;
            }
            for (String candidateLine : candidateFragments(lines, i)) {
                if (shouldRejectContext(candidateLine)) {
                    continue;
                }
                Matcher matcher = KEYWORD_PATTERN.matcher(candidateLine);
                if (matcher.find()) {
                    String candidate = RegexUtil.repairInvoiceNumberCandidate(matcher.group(2));
                    if (validate(candidate, true)) {
                        if (candidate.contains("/") || candidate.contains("-") || candidate.length() >= 5) {
                            return new FieldExtractionResult<>(candidate, "keyword", line.getLineNumber());
                        }
                        int score = scoreCandidate(candidate, candidateLine, freq, true);
                        if (score > bestScore) {
                            bestScore = score;
                            bestValue = candidate;
                            bestLine = line.getLineNumber();
                        }
                    }
                }
                for (String token : tokenize(candidateLine)) {
                    String repaired = RegexUtil.repairInvoiceNumberCandidate(token);
                    if (validate(repaired, containsInvoiceKeyword(candidateLine.toLowerCase(Locale.ROOT)))) {
                        int score = scoreCandidate(repaired, candidateLine, freq, false);
                        if (score > bestScore) {
                            bestScore = score;
                            bestValue = repaired;
                            bestLine = line.getLineNumber();
                        }
                    }
                }
            }
        }
        return new FieldExtractionResult<>(bestValue, bestValue == null ? "fallback" : "keyword",
                bestLine == -1 ? null : bestLine);
    }

    private List<String> candidateFragments(List<LineIndexingService.IndexedLine> lines, int index) {
        java.util.LinkedHashSet<String> fragments = new java.util.LinkedHashSet<>();
        for (int offset = 0; offset <= 4; offset++) {
            int current = index + offset;
            if (current >= lines.size()) {
                break;
            }
            for (String fragment : OcrLayoutUtil.fragments(lines.get(current).getText())) {
                if (current == index || fragment.length() <= 60) {
                    fragments.add(fragment);
                }
            }
        }
        return new java.util.ArrayList<>(fragments);
    }

    private List<String> tokenize(String text) {
        return List.of(text.split("[\\s:]+"));
    }

    private String chooseMostRepeated(Map<String, Integer> freq) {
        String best = null;
        int bestCount = 1;
        for (Map.Entry<String, Integer> entry : freq.entrySet()) {
            if (entry.getValue() > bestCount && isStructuredUnanchored(entry.getKey())) {
                best = entry.getKey();
                bestCount = entry.getValue();
            }
        }
        return best;
    }

    private Integer findLine(List<LineIndexingService.IndexedLine> lines, String value) {
        for (LineIndexingService.IndexedLine line : lines) {
            if (line.getText().contains(value)) {
                return line.getLineNumber();
            }
        }
        return null;
    }

    private boolean validate(String val, boolean keywordAnchored) {
        if (val == null) {
            return false;
        }
        String normalized = RegexUtil.cleanToken(val.trim());
        if (!RegexUtil.INVOICE_NUMBER_TOKEN_PATTERN.matcher(normalized).matches()) {
            return false;
        }
        if (RegexUtil.GSTIN_PATTERN.matcher(normalized).matches()) {
            return false;
        }
        if (DateUtil.isValidInvoiceDate(normalized)) {
            return false;
        }
        if (isKeywordLookalike(normalized)) {
            return false;
        }
        if (normalized.matches("^\\d{3,12}$")) {
            return keywordAnchored;
        }
        if (!keywordAnchored && !isStructuredUnanchored(normalized)) {
            return false;
        }
        return true;
    }

    private int scoreCandidate(String candidate, String source, Map<String, Integer> freq, boolean regexMatch) {
        int score = regexMatch ? 120 : 70;
        score += freq.getOrDefault(candidate, 0) * 10;
        if (DateUtil.findCandidateDates(source).size() > 0) {
            score += 35;
        }
        if (shouldRejectContext(source)) {
            score -= 80;
        }
        if (candidate.toUpperCase(Locale.ROOT).startsWith("NO-")) {
            score -= 30;
        }
        if (candidate.matches("^\\d{3,12}$")) {
            score += regexMatch ? 10 : 25;
        }
        if (candidate.matches("^[A-Z]{2,10}\\d{2,8}/\\d{1,8}[A-Z]?$")) {
            score += 25;
        }
        if (candidate.matches("^[A-Z]\\d{3,8}$")) {
            score += 20;
        }
        if (!candidate.contains("/") && !candidate.contains("-") && candidate.length() <= 3) {
            score -= 60;
        }
        if (source.trim().split("\\s+").length > 5) {
            score -= 15;
        }
        return score;
    }

    private boolean shouldRejectContext(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        if (looksLikeAddressContext(lower)) {
            return true;
        }
        for (String keyword : INVOICE_CONTEXT_REJECT_KEYWORDS) {
            if (lower.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private boolean looksLikeAddressContext(String lower) {
        int hits = 0;
        for (String keyword : List.of("plot", "address", "road", "street", "lane", "district", "state", "branch", "complex")) {
            if (lower.contains(keyword)) {
                hits++;
            }
        }
        if (hits >= 2) {
            return true;
        }
        return hits >= 1 && (lower.contains(",") || lower.matches(".*\\b\\d{5,6}\\b.*"));
    }

    private boolean containsInvoiceKeyword(String text) {
        String lower = text == null ? "" : text.toLowerCase(Locale.ROOT);
        boolean anchored = lower.contains("invoice no")
                || lower.contains("invoice number")
                || lower.contains("invoice #")
                || lower.contains("inv no")
                || lower.contains("bill no")
                || lower.contains("bill #")
                || lower.matches(".*\\b[a-z]?nvoice\\b.*\\b(no|number|#)\\b.*")
                || lower.matches(".*\\binv\\b.*\\b(no|number|#)\\b.*");
        if (!anchored && (lower.contains("tax invoice") || lower.contains("gst invoice") || lower.matches("^invoice\\b.*"))) {
            return false;
        }
        return anchored;
    }

    private boolean isKeywordLookalike(String value) {
        String normalized = value
                .toUpperCase(Locale.ROOT)
                .replace('0', 'O')
                .replace('1', 'I')
                .replace('7', 'T')
                .replace('5', 'S')
                .replace('8', 'B');
        return normalized.matches("^(INVOICE.*|VOUCHER.*|DATE.*|FOR|ORIGINAL.*|DUPLICATE.*|RECEIVER.*|SUPPLY.*|STORE.*|MATERIAL.*|ENTERPRISE.*|STATE.*|STATION.*|TOTAL.*|AMOUNT.*)$");
    }

    private boolean isStructuredUnanchored(String value) {
        String normalized = value.toUpperCase(Locale.ROOT);
        if (normalized.contains("/") || normalized.contains("-")) {
            return true;
        }
        if (normalized.matches("^[A-Z]{1,4}\\d{2,10}[A-Z]?$")) {
            return true;
        }
        return normalized.matches("^\\d{3,12}$");
    }
}
