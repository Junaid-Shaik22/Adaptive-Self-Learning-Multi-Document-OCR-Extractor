package com.invoice.extractor.util;

import com.invoice.extractor.service.impl.LineIndexingService;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;

public class AmountUtil {
    public static final double MIN_SIGNIFICANT_AMOUNT = 100.0;
    public static final List<String> TOTAL_KEYWORDS = List.of(
            "grand total", "invoice value", "total invoice value", "amount payable",
            "net amount", "amount due", "total amount after tax", "value (figure)", "total after tax",
            "invoice amt", "inv value", "inv value (in fig)", "invoice amount", "\\btotal\\b");
    public static final List<String> TAX_KEYWORDS = List.of(
            "igst", "cgst", "sgst", "tax amount", "total tax amount", "gst output", "add : igst",
            "add : cgst", "add : sgst", "integrated tax", "gst amount");
    public static final List<String> SUBTOTAL_KEYWORDS = List.of(
            "taxable value", "subtotal", "sub total", "taxable amount", "amount chargeable",
            "total amount before tax", "amount before tax", "total to be taxed", "taxable total",
            "taxable amt", "sale amt");
    public static final List<String> AMOUNT_IGNORE_KEYWORDS = List.of(
            "qty", "quantity", "hsn", "sac", "rate");
    public static final List<String> BANK_KEYWORDS = List.of(
            "bank", "account", "a/c", "ifsc", "branch", "upi", "utr", "swift", "beneficiary");

    private AmountUtil() {
    }

    public static class AmountCandidate {
        private final LineIndexingService.IndexedLine line;
        private final String token;
        private final double value;
        private final boolean percentToken;

        public AmountCandidate(LineIndexingService.IndexedLine line, String token, double value, boolean percentToken) {
            this.line = line;
            this.token = token;
            this.value = value;
            this.percentToken = percentToken;
        }

        public LineIndexingService.IndexedLine getLine() {
            return line;
        }

        public String getToken() {
            return token;
        }

        public double getValue() {
            return value;
        }

        public boolean isPercentToken() {
            return percentToken;
        }
    }

    public static class SummaryAmounts {
        private final Double subtotal;
        private final Double tax;
        private final Integer lineNumber;

        public SummaryAmounts(Double subtotal, Double tax, Integer lineNumber) {
            this.subtotal = subtotal;
            this.tax = tax;
            this.lineNumber = lineNumber;
        }

        public Double getSubtotal() {
            return subtotal;
        }

        public Double getTax() {
            return tax;
        }

        public Integer getLineNumber() {
            return lineNumber;
        }
    }

    public static List<AmountCandidate> extractCandidates(List<LineIndexingService.IndexedLine> lines) {
        List<AmountCandidate> candidates = new ArrayList<>();
        if (lines == null) {
            return candidates;
        }
        for (LineIndexingService.IndexedLine line : lines) {
            String text = normalizeAmountSpacing(line.getText());
            if (RegexUtil.containsAnyKeyword(text, BANK_KEYWORDS)) {
                continue;
            }
            Matcher matcher = RegexUtil.AMOUNT_PATTERN.matcher(text.replace("Rs.", "").replace("Rs", ""));
            while (matcher.find()) {
                String token = matcher.group(2);
                Double value = parseAmount(token);
                if (value == null || value < MIN_SIGNIFICANT_AMOUNT) {
                    continue;
                }
                candidates.add(new AmountCandidate(line, token, value, isPercentToken(text, matcher.end())));
            }
        }
        return candidates;
    }

    public static Double parseAmount(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        try {
            String normalized = token
                    .replaceAll("(?i)rs\\.?", "")
                    .replace("₹", "")
                    .replace("INR", "")
                    .replace(" ", "")
                    .trim();
            normalized = normalized.replaceAll(",\\.(?=\\d)", ".");
            if (normalized.indexOf(',') >= 0 && normalized.indexOf('.') < 0) {
                int lastComma = normalized.lastIndexOf(',');
                String fractional = normalized.substring(lastComma + 1);
                if (fractional.length() == 2) {
                    normalized = normalized.substring(0, lastComma).replace(",", "") + "." + fractional;
                } else {
                    normalized = normalized.replace(",", "");
                }
            } else {
                normalized = normalized.replace(",", "");
            }
            if (normalized.isBlank()) {
                return null;
            }
            if (normalized.length() > 12) {
                return null;
            }
            double val = new BigDecimal(normalized).doubleValue();
            return val > 999999999.0 ? null : val;
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    public static String formatAmount(Double value) {
        if (value == null) {
            return null;
        }
        return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }

    public static List<String> extractRawNumericTokens(String text) {
        List<String> tokens = new ArrayList<>();
        if (text == null) {
            return tokens;
        }
        Matcher matcher = RegexUtil.AMOUNT_PATTERN
                .matcher(normalizeAmountSpacing(text).replace("Rs.", "").replace("Rs", ""));
        while (matcher.find()) {
            tokens.add(matcher.group(2));
        }
        return tokens;
    }

    public static boolean looksLikeCurrencyToken(String token) {
        return token != null && (token.contains(",") || token.contains("."));
    }

    public static boolean approximatelyEquals(Double left, Double right) {
        if (left == null || right == null) {
            return false;
        }
        double tolerance = Math.max(1.0, Math.max(Math.abs(left), Math.abs(right)) * 0.02);
        return Math.abs(left - right) <= tolerance;
    }

    public static boolean isIgnoredAmountLine(String text) {
        return RegexUtil.containsAnyKeyword(text, AMOUNT_IGNORE_KEYWORDS)
                || RegexUtil.containsAnyKeyword(text, BANK_KEYWORDS);
    }

    public static boolean isPreferredAmountLine(String text, Collection<String> preferredKeywords) {
        return RegexUtil.containsAnyKeyword(text, preferredKeywords);
    }

    public static boolean isTaxLine(String text) {
        String lower = text == null ? "" : text.toLowerCase(Locale.ROOT);
        if (lower.contains("taxable")) {
            return false;
        }
        return RegexUtil.containsAnyKeyword(lower, TAX_KEYWORDS)
                || (lower.contains("%") && RegexUtil.containsAnyKeyword(lower, List.of("igst", "cgst", "sgst", "gst")));
    }

    public static SummaryAmounts extractSummaryAmounts(List<LineIndexingService.IndexedLine> lines) {
        if (lines == null || lines.isEmpty()) {
            return null;
        }
        SummaryAmounts keywordSummary = extractKeywordSummary(lines);
        if (keywordSummary != null && keywordSummary.getSubtotal() != null && keywordSummary.getTax() != null) {
            return keywordSummary;
        }
        int headerIndex = -1;
        for (int i = 0; i < lines.size(); i++) {
            String lower = lines.get(i).getText().toLowerCase(Locale.ROOT);
            if ((lower.contains("taxable") && lower.contains("tax"))
                    || lower.contains("tax amount")
                    || lower.contains("total tax amount")) {
                headerIndex = i;
                break;
            }
        }

        if (headerIndex < 0) {
            return null;
        }

        int start = headerIndex;
        SummaryAmounts best = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (int i = start; i < lines.size(); i++) {
            LineIndexingService.IndexedLine line = lines.get(i);
            List<Double> values = extractSummaryValues(line.getText());
            if (values.size() < 2) {
                continue;
            }
            double subtotal = values.get(0);
            double tax = values.get(1);
            if (subtotal <= tax) {
                continue;
            }
            double score = values.size();
            String lower = line.getText().toLowerCase(Locale.ROOT);
            if (headerIndex >= 0) {
                score += 40;
            }
            if (lower.contains("total")) {
                score += 25;
            }
            if (lower.contains("taxable")) {
                score += 10;
            }
            if (score > bestScore) {
                bestScore = score;
                best = new SummaryAmounts(subtotal, tax, line.getLineNumber());
            }
        }
        return best;
    }

    public static Double extractBestAmountByKeywords(List<LineIndexingService.IndexedLine> lines,
            Collection<String> keywords,
            boolean preferLargest) {
        Double best = null;
        for (LineIndexingService.IndexedLine line : lines) {
            String lower = line.getText().toLowerCase(Locale.ROOT);
            if (!RegexUtil.containsAnyKeyword(lower, keywords) || isIgnoredAmountLine(lower)) {
                continue;
            }
            boolean lineHasCurrencyToken = extractRawNumericTokens(line.getText()).stream()
                    .anyMatch(AmountUtil::looksLikeCurrencyToken);
            for (AmountCandidate candidate : extractCandidates(List.of(line))) {
                if (candidate.isPercentToken()
                        || (lineHasCurrencyToken && !looksLikeCurrencyToken(candidate.getToken()))) {
                    continue;
                }
                if (best == null
                        || (preferLargest && candidate.getValue() > best)
                        || (!preferLargest && candidate.getValue() < best)) {
                    best = candidate.getValue();
                }
            }
        }
        return best;
    }

    public static Double extractAmountFromWords(List<LineIndexingService.IndexedLine> lines,
                                                Collection<String> keywords) {
        if (lines == null || lines.isEmpty()) {
            return null;
        }
        for (int i = 0; i < lines.size(); i++) {
            String lineText = lines.get(i).getText();
            String lower = lineText.toLowerCase(Locale.ROOT);
            boolean candidateLine = lower.contains("indian rupees")
                    || lower.contains("invoice value (in words)")
                    || lower.contains("amount chargeable (inwords)")
                    || (lower.contains("in words") && (keywords == null || RegexUtil.containsAnyKeyword(lower, keywords)));
            if (!candidateLine) {
                continue;
            }
            Double parsed = parseAmountWords(lineText);
            if (parsed != null && parsed >= MIN_SIGNIFICANT_AMOUNT) {
                if (i + 1 < lines.size() && lower.contains("in words")) {
                    String next = lines.get(i + 1).getText();
                    String nextLower = next.toLowerCase(Locale.ROOT).trim();
                    if (startsWithNumberWord(nextLower) && !nextLower.contains("in words")) {
                        Double combined = parseAmountWords(lineText + " " + next);
                        if (combined != null && combined >= parsed) {
                            return combined;
                        }
                    }
                }
                return parsed;
            }
            if (i + 1 < lines.size() && lower.contains("in words")) {
                String next = lines.get(i + 1).getText();
                if (startsWithNumberWord(next.toLowerCase(Locale.ROOT).trim())) {
                    parsed = parseAmountWords(next);
                    if (parsed != null && parsed >= MIN_SIGNIFICANT_AMOUNT) {
                        return parsed;
                    }
                }
            }
        }
        return null;
    }

    private static List<Double> extractSummaryValues(String text) {
        List<String> rawTokens = extractRawNumericTokens(text);
        List<Double> currencyValues = new ArrayList<>();
        List<Double> plainValues = new ArrayList<>();
        boolean hasCurrencyToken = rawTokens.stream().anyMatch(AmountUtil::looksLikeCurrencyToken);
        for (String token : rawTokens) {
            Double value = parseAmount(token);
            if (value == null || value < MIN_SIGNIFICANT_AMOUNT || isPercentLike(text, token)) {
                continue;
            }
            if (looksLikeCurrencyToken(token)) {
                currencyValues.add(value);
            } else if (!hasCurrencyToken) {
                plainValues.add(value);
            }
        }

        List<Double> base = !currencyValues.isEmpty() ? currencyValues : plainValues;
        if (base.size() >= 3 && Double.compare(base.get(1), base.get(2)) == 0) {
            return List.of(base.get(0), base.get(1));
        }
        if (base.size() >= 2) {
            return List.of(base.get(0), base.get(1));
        }
        return List.of();
    }

    private static SummaryAmounts extractKeywordSummary(List<LineIndexingService.IndexedLine> lines) {
        Double subtotal = null;
        Double tax = null;
        Integer lineNumber = null;
        for (LineIndexingService.IndexedLine line : lines) {
            String lower = line.getText().toLowerCase(Locale.ROOT);
            List<AmountCandidate> candidates = extractCandidates(List.of(line));
            if (candidates.isEmpty()) {
                continue;
            }
            boolean lineHasCurrencyToken = extractRawNumericTokens(line.getText()).stream()
                    .anyMatch(AmountUtil::looksLikeCurrencyToken);
            if (RegexUtil.containsAnyKeyword(lower, SUBTOTAL_KEYWORDS) && subtotal == null) {
                subtotal = largestNonPercent(candidates, lineHasCurrencyToken);
                lineNumber = line.getLineNumber();
            }
            if (isTaxLine(lower)) {
                Double taxValue = largestNonPercent(candidates, lineHasCurrencyToken);
                if (taxValue != null) {
                    tax = (tax == null) ? taxValue : tax + taxValue;
                    if (lineNumber == null) {
                        lineNumber = line.getLineNumber();
                    }
                }
            }
        }
        if (subtotal != null || tax != null) {
            return new SummaryAmounts(subtotal, tax, lineNumber);
        }
        return null;
    }

    private static Double largestNonPercent(List<AmountCandidate> candidates, boolean lineHasCurrencyToken) {
        Double best = null;
        for (AmountCandidate candidate : candidates) {
            if (candidate.isPercentToken() || (lineHasCurrencyToken && !looksLikeCurrencyToken(candidate.getToken()))) {
                continue;
            }
            if (best == null || candidate.getValue() > best) {
                best = candidate.getValue();
            }
        }
        return best;
    }

    private static boolean isPercentLike(String line, String token) {
        int index = line.indexOf(token);
        return index >= 0 && isPercentToken(line, index + token.length());
    }

    private static boolean isPercentToken(String line, int tokenEnd) {
        if (line == null || tokenEnd >= line.length()) {
            return false;
        }
        int index = tokenEnd;
        while (index < line.length() && Character.isWhitespace(line.charAt(index))) {
            index++;
        }
        return index < line.length() && line.charAt(index) == '%';
    }

    private static String normalizeAmountSpacing(String text) {
        if (text == null) {
            return "";
        }
        return text.replaceAll("(?<=\\d),\\s+(?=\\d)", ",");
    }

    private static Double parseAmountWords(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String normalized = text.toLowerCase(Locale.ROOT)
                .replace("fourty", "forty")
                .replace('-', ' ')
                .replaceAll("[^a-z\\s]", " ")
                .replaceAll("\\s+", " ")
                .trim();
        int rupeesIndex = normalized.indexOf("indian rupees");
        if (rupeesIndex >= 0) {
            normalized = normalized.substring(rupeesIndex + "indian rupees".length()).trim();
        }
        normalized = normalized
                .replace("rupees", " ")
                .replace("rupee", " ")
                .replace("rs", " ")
                .replaceAll("\\s+", " ")
                .trim();
        if (normalized.isBlank()) {
            return null;
        }

        long total = 0;
        long current = 0;
        boolean seenNumberWord = false;
        for (String token : normalized.split("\\s+")) {
            Integer small = smallNumber(token);
            if (small != null) {
                current += small;
                seenNumberWord = true;
                continue;
            }
            Integer tens = tensNumber(token);
            if (tens != null) {
                current += tens;
                seenNumberWord = true;
                continue;
            }
            switch (token) {
                case "hundred" -> {
                    current = current == 0 ? 100 : current * 100;
                    seenNumberWord = true;
                }
                case "thousand" -> {
                    total += current * 1_000L;
                    current = 0;
                    seenNumberWord = true;
                }
                case "lakh", "lakhs", "lac", "lacs" -> {
                    total += current * 100_000L;
                    current = 0;
                    seenNumberWord = true;
                }
                case "crore", "crores" -> {
                    total += current * 10_000_000L;
                    current = 0;
                    seenNumberWord = true;
                }
                case "and", "only" -> {
                }
                default -> {
                    if (token.startsWith("pais")) {
                        return seenNumberWord ? (double) (total + current) : null;
                    }
                }
            }
        }
        return seenNumberWord ? (double) (total + current) : null;
    }

    private static Integer smallNumber(String token) {
        return switch (token) {
            case "zero" -> 0;
            case "one" -> 1;
            case "two" -> 2;
            case "three" -> 3;
            case "four" -> 4;
            case "five" -> 5;
            case "six" -> 6;
            case "seven" -> 7;
            case "eight" -> 8;
            case "nine" -> 9;
            case "ten" -> 10;
            case "eleven" -> 11;
            case "twelve" -> 12;
            case "thirteen" -> 13;
            case "fourteen" -> 14;
            case "fifteen" -> 15;
            case "sixteen" -> 16;
            case "seventeen" -> 17;
            case "eighteen" -> 18;
            case "nineteen" -> 19;
            default -> null;
        };
    }

    private static Integer tensNumber(String token) {
        return switch (token) {
            case "twenty" -> 20;
            case "thirty" -> 30;
            case "forty" -> 40;
            case "fifty" -> 50;
            case "sixty" -> 60;
            case "seventy" -> 70;
            case "eighty" -> 80;
            case "ninety" -> 90;
            default -> null;
        };
    }

    private static boolean startsWithNumberWord(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String first = text.split("\\s+")[0];
        return smallNumber(first) != null || tensNumber(first) != null
                || "hundred".equals(first) || "thousand".equals(first)
                || "lakh".equals(first) || "lakhs".equals(first)
                || "lac".equals(first) || "lacs".equals(first)
                || "crore".equals(first) || "crores".equals(first);
    }
}
