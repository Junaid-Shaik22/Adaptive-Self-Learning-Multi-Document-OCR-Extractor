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
    private static final List<String> INVOICE_KEYWORDS = List.of(
            "invoice no", "invoice number", "invoice #", "inv no", "bill no", "bill #");
    private static final List<String> INVOICE_CONTEXT_REJECT_KEYWORDS = List.of(
            "plot", "address", "fac", "mob", "road", "near", "state", "code", "delivery note",
            "mode/terms", "reference", "dispatch", "destination", "bill of lading", "lr-rr", "e-way",
            "eway", "purchase order", "customer po", "transport", "vehicle");

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
        for (LineIndexingService.IndexedLine line : zones.topZone) {
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
                if (fragment.length() <= 45) {
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
            if (entry.getValue() > bestCount) {
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
        if (normalized.matches("^\\d{3,12}$")) {
            return keywordAnchored;
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
        if (source.trim().split("\\s+").length > 5) {
            score -= 15;
        }
        return score;
    }

    private boolean shouldRejectContext(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        return OcrLayoutUtil.isAddressLike(lower)
                || RegexUtil.containsAnyKeyword(lower, INVOICE_CONTEXT_REJECT_KEYWORDS);
    }

    private boolean containsInvoiceKeyword(String text) {
        String lower = text == null ? "" : text.toLowerCase(Locale.ROOT);
        return RegexUtil.containsAnyKeyword(lower, INVOICE_KEYWORDS)
                || lower.matches(".*\\b[a-z]?nvoice\\b.*\\b(no|number|#)\\b.*")
                || lower.matches(".*\\binv\\b.*\\b(no|number|#)\\b.*");
    }
}
