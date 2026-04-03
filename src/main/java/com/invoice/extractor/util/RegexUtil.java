package com.invoice.extractor.util;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

public class RegexUtil {
    private static final String BASE36_CHARS = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ";

    public static final Pattern GSTIN_PATTERN = Pattern.compile(
            "\\d{2}[A-Z]{5}\\d{4}[A-Z][1-9A-Z]Z[0-9A-Z]",
            Pattern.CASE_INSENSITIVE
    );

    public static final Pattern GSTIN_TOKEN_PATTERN = Pattern.compile(
            "\\b[A-Z0-9]{15}\\b",
            Pattern.CASE_INSENSITIVE
    );

    public static final Pattern AMOUNT_PATTERN = Pattern.compile(
            "(?<![A-Za-z0-9])(₹|RS\\.?|INR)?\\s*(\\d{1,3}(?:,\\s*\\d{2,3})+(?:\\.\\d{1,2})?|\\d+(?:\\.\\d{1,2})?)(?![A-Za-z0-9])",
            Pattern.CASE_INSENSITIVE
    );

    public static final Pattern INVOICE_NUMBER_TOKEN_PATTERN = Pattern.compile(
            "^(?=.{3,20}$)(?:\\d{3,12}|(?=.*[A-Za-z])(?=.*\\d)[A-Za-z0-9/-]+)$"
    );

    private RegexUtil() {
    }

    public static String normalizeLine(String text) {
        if (text == null) {
            return "";
        }
        return text.replaceAll("\\s+", " ").trim();
    }

    public static String normalizeForComparison(String text) {
        if (text == null) {
            return "";
        }
        return text.replaceAll("[^A-Za-z0-9]", "").toUpperCase(Locale.ROOT);
    }

    public static String cleanToken(String token) {
        if (token == null) {
            return "";
        }
        return token.replaceAll("^[^A-Za-z0-9]+|[^A-Za-z0-9]+$", "");
    }

    public static boolean containsAnyKeyword(String text, Collection<String> keywords) {
        if (text == null || keywords == null || keywords.isEmpty()) {
            return false;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        for (String keyword : keywords) {
            if (lower.contains(keyword.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    public static boolean isValidGstin(String value) {
        if (value == null) {
            return false;
        }
        String normalized = value.replaceAll("[^A-Za-z0-9]", "").toUpperCase(Locale.ROOT);
        return GSTIN_PATTERN.matcher(normalized).matches();
    }

    public static String repairGstinCandidate(String token) {
        if (token == null) {
            return null;
        }
        String normalized = token.replaceAll("[^A-Za-z0-9]", "").toUpperCase(Locale.ROOT);
        if (normalized.length() != 15) {
            return null;
        }
        if (GSTIN_PATTERN.matcher(normalized).matches() && hasValidGstinChecksum(normalized)) {
            return normalized;
        }

        List<List<Character>> options = new ArrayList<>();
        for (int index = 0; index < 14; index++) {
            List<Character> candidates = gstinOptions(normalized.charAt(index), index);
            if (candidates.isEmpty()) {
                return null;
            }
            options.add(candidates);
        }

        String[] best = new String[]{null};
        int[] bestDistance = new int[]{Integer.MAX_VALUE};
        searchGstinCandidates(normalized, options, 0, new StringBuilder(), best, bestDistance);
        return best[0];
    }

    public static String repairInvoiceNumberCandidate(String token) {
        String normalized = cleanToken(token).toUpperCase(Locale.ROOT);
        if (normalized.length() < 3 || normalized.length() > 20) {
            return normalized;
        }
        if (DateUtil.isValidInvoiceDate(normalized) || GSTIN_PATTERN.matcher(normalized).matches()) {
            return normalized;
        }

        List<String> variants = new ArrayList<>();
        buildInvoiceVariants(normalized, 0, new StringBuilder(), variants, 512);
        String best = normalized;
        int bestScore = scoreInvoiceVariant(normalized, normalized);
        for (String variant : variants) {
            int score = scoreInvoiceVariant(normalized, variant);
            if (score > bestScore) {
                bestScore = score;
                best = variant;
            }
        }
        return bestScore >= 55 ? best : normalized;
    }

    private static void searchGstinCandidates(String source,
                                              List<List<Character>> options,
                                              int index,
                                              StringBuilder builder,
                                              String[] best,
                                              int[] bestDistance) {
        if (index == options.size()) {
            char checksum = computeGstinCheckDigit(builder.toString());
            String candidate = builder.toString() + checksum;
            if (!isValidGstin(candidate)) {
                return;
            }
            int distance = editDistance(source, candidate);
            if (distance < bestDistance[0]) {
                bestDistance[0] = distance;
                best[0] = candidate;
            }
            return;
        }

        for (char option : options.get(index)) {
            builder.append(option);
            searchGstinCandidates(source, options, index + 1, builder, best, bestDistance);
            builder.deleteCharAt(builder.length() - 1);
        }
    }

    private static List<Character> gstinOptions(char source, int index) {
        List<Character> options = new ArrayList<>();
        for (char option : ocrAlternatives(source)) {
            if (isAllowedForGstinPosition(option, index) && !options.contains(option)) {
                options.add(option);
            }
        }
        return options;
    }

    private static boolean isAllowedForGstinPosition(char value, int index) {
        if (index == 13) {
            return value == 'Z';
        }
        if (index == 12) {
            return Character.isLetterOrDigit(value) && value != '0';
        }
        if (index == 11) {
            return Character.isLetterOrDigit(value);
        }
        if (index >= 2 && index <= 6) {
            return Character.isLetter(value);
        }
        return Character.isDigit(value);
    }

    private static char computeGstinCheckDigit(String body) {
        int factor = 1;
        int sum = 0;
        for (char ch : body.toCharArray()) {
            int codePoint = BASE36_CHARS.indexOf(ch);
            int product = factor * codePoint;
            sum += (product / BASE36_CHARS.length()) + (product % BASE36_CHARS.length());
            factor = factor == 1 ? 2 : 1;
        }
        return BASE36_CHARS.charAt((BASE36_CHARS.length() - (sum % BASE36_CHARS.length())) % BASE36_CHARS.length());
    }

    private static boolean hasValidGstinChecksum(String gstin) {
        return gstin != null
                && gstin.length() == 15
                && computeGstinCheckDigit(gstin.substring(0, 14)) == gstin.charAt(14);
    }

    private static void buildInvoiceVariants(String source,
                                             int index,
                                             StringBuilder current,
                                             List<String> variants,
                                             int limit) {
        if (variants.size() >= limit) {
            return;
        }
        if (index == source.length()) {
            String candidate = current.toString();
            if (INVOICE_NUMBER_TOKEN_PATTERN.matcher(candidate).matches()) {
                variants.add(candidate);
            }
            return;
        }

        char value = source.charAt(index);
        Set<Character> options = new LinkedHashSet<>();
        if (value == '/' || value == '-') {
            options.add(value);
        } else {
            for (char option : ocrAlternatives(value)) {
                if (Character.isLetterOrDigit(option)) {
                    options.add(option);
                }
            }
        }
        for (char option : options) {
            current.append(option);
            buildInvoiceVariants(source, index + 1, current, variants, limit);
            current.deleteCharAt(current.length() - 1);
        }
    }

    private static int scoreInvoiceVariant(String source, String candidate) {
        if (!INVOICE_NUMBER_TOKEN_PATTERN.matcher(candidate).matches()) {
            return Integer.MIN_VALUE;
        }
        if ((!candidate.matches(".*[A-Z].*") || !candidate.matches(".*\\d.*") || DateUtil.isValidInvoiceDate(candidate))
                && !candidate.matches("^\\d{3,12}$")) {
            return Integer.MIN_VALUE;
        }
        int score = 0;
        if (candidate.matches("^\\d{3,12}$")) {
            score += 55;
        } else if (candidate.matches("^[A-Z]\\d{3,8}$")) {
            score += 120;
        } else if (candidate.matches("^[A-Z]{2,10}\\d{2,8}/\\d{1,8}[A-Z]?$")) {
            score += 115;
        } else if (candidate.matches("^[A-Z]{1,4}[-/]?\\d{2,8}[A-Z]?$")) {
            score += 95;
        } else if (candidate.matches("^[A-Z]{1,4}[-/]?[A-Z0-9]{2,8}$")) {
            score += 60;
        }
        if (candidate.startsWith("NO")) {
            score -= 80;
        }
        if (candidate.endsWith("-") || candidate.endsWith("/")) {
            score -= 40;
        }
        score -= editDistance(source, candidate) * 6;
        for (int i = 0; i < Math.min(source.length(), candidate.length()); i++) {
            if (source.charAt(i) == candidate.charAt(i)) {
                score += 2;
            }
        }
        return score;
    }

    private static int editDistance(String left, String right) {
        int distance = 0;
        for (int i = 0; i < Math.min(left.length(), right.length()); i++) {
            if (left.charAt(i) != right.charAt(i)) {
                distance++;
            }
        }
        return distance + Math.abs(left.length() - right.length());
    }

    private static List<Character> ocrAlternatives(char source) {
        char normalized = Character.toUpperCase(source);
        return switch (normalized) {
            case '0' -> List.of('0', 'O', 'Q', 'D');
            case 'O' -> List.of('O', '0', 'Q', 'D');
            case 'Q' -> List.of('Q', 'O', '0');
            case 'D' -> List.of('D', 'O', '0');
            case '1' -> List.of('1', 'I', 'L');
            case 'I' -> List.of('I', '1', 'L');
            case 'L' -> List.of('L', '1', 'I');
            case '2' -> List.of('2', 'Z');
            case 'Z' -> List.of('Z', '2');
            case '5' -> List.of('5', 'S');
            case 'S' -> List.of('S', '5');
            case '8' -> List.of('8', 'B');
            case 'B' -> List.of('B', '8');
            case '6' -> List.of('6', 'G');
            case 'G' -> List.of('G', '6');
            case '7' -> List.of('7', 'T');
            case 'T' -> List.of('T', '7');
            default -> List.of(normalized);
        };
    }
}
