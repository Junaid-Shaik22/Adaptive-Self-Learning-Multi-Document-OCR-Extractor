package com.docextract.util;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * RegexUtility – centralises all regex patterns and look-up helpers.
 *
 * All patterns are compiled once at class-load time for performance.
 */
@Slf4j
@Component
public class RegexUtility {

    private static final int[][] VERHOEFF_D = {
            {0, 1, 2, 3, 4, 5, 6, 7, 8, 9},
            {1, 2, 3, 4, 0, 6, 7, 8, 9, 5},
            {2, 3, 4, 0, 1, 7, 8, 9, 5, 6},
            {3, 4, 0, 1, 2, 8, 9, 5, 6, 7},
            {4, 0, 1, 2, 3, 9, 5, 6, 7, 8},
            {5, 9, 8, 7, 6, 0, 4, 3, 2, 1},
            {6, 5, 9, 8, 7, 1, 0, 4, 3, 2},
            {7, 6, 5, 9, 8, 2, 1, 0, 4, 3},
            {8, 7, 6, 5, 9, 3, 2, 1, 0, 4},
            {9, 8, 7, 6, 5, 4, 3, 2, 1, 0}
    };

    private static final int[][] VERHOEFF_P = {
            {0, 1, 2, 3, 4, 5, 6, 7, 8, 9},
            {1, 5, 7, 6, 2, 8, 3, 0, 9, 4},
            {5, 8, 0, 3, 7, 9, 6, 1, 4, 2},
            {8, 9, 1, 6, 0, 4, 3, 5, 2, 7},
            {9, 4, 5, 3, 1, 2, 6, 8, 7, 0},
            {4, 2, 8, 6, 5, 7, 3, 9, 0, 1},
            {2, 7, 9, 3, 8, 0, 6, 4, 1, 5},
            {7, 0, 4, 6, 9, 1, 3, 2, 5, 8}
    };

    // ─────────────────────────────────────────────────
    //  AADHAAR PATTERNS
    // ─────────────────────────────────────────────────

    /** 12-digit Aadhaar (first digit 2–9, with optional spaces every 4 digits). */
    public static final Pattern AADHAAR_NUMBER =
            Pattern.compile("\\b([2-9]\\d{3}[\\s]?\\d{4}[\\s]?\\d{4})\\b");

    /** VID (16-digit virtual ID). */
    public static final Pattern AADHAAR_VID =
            Pattern.compile("\\bVID[:\\s]+([0-9]{4}[\\s]?[0-9]{4}[\\s]?[0-9]{4}[\\s]?[0-9]{4})\\b");

    // ─────────────────────────────────────────────────
    //  PAN PATTERNS
    // ─────────────────────────────────────────────────

    /** PAN card number: AAAAA9999A. */
    public static final Pattern PAN_NUMBER =
            Pattern.compile("\\b([A-Z]{5}[0-9]{4}[A-Z])\\b");

    // ─────────────────────────────────────────────────
    //  DRIVING LICENSE PATTERNS
    // ─────────────────────────────────────────────────

    /**
     * DL number: StateCode(2) + optional sep + RTO(2/3) + optional sep + year(4) + optional sep + serial(7)
     * E.g.:  TS09 20230012345  |  MH12-2019-1234567  |  DL-0420110149646
     */
    public static final Pattern DL_NUMBER =
            Pattern.compile("\\b([A-Z]{2}[-\\s]?[0-9]{2,3}[-\\s]?[0-9]{4}[-\\s]?[0-9]{7})\\b");

    /** Alternate short DL format used in some states. */
    public static final Pattern DL_NUMBER_SHORT =
            Pattern.compile("\\b([A-Z]{2}[0-9]{13,14})\\b");

    // ─────────────────────────────────────────────────
    //  DATE PATTERNS  (DD/MM/YYYY  |  DD-MM-YYYY  |  DD.MM.YYYY  |  YYYY)
    // ─────────────────────────────────────────────────

    public static final Pattern DATE_FULL =
            Pattern.compile("\\b(\\d{1,2})[/\\-.](\\d{1,2})[/\\-.]((?:19|20)\\d{2})\\b");

    public static final Pattern DATE_FUZZY =
            Pattern.compile("\\b([0-9OQDILSZBG]{1,2})[/\\-.]([0-9OQDILSZBG]{1,2})[/\\-.]([0-9OQDILSZBG]{4})\\b");

    public static final Pattern DATE_YEAR_ONLY =
            Pattern.compile("\\b((?:19|20)\\d{2})\\b");

    // ─────────────────────────────────────────────────
    //  GENDER
    // ─────────────────────────────────────────────────

    public static final Pattern GENDER_MALE   = Pattern.compile("\\bMALE\\b");
    public static final Pattern GENDER_FEMALE = Pattern.compile("\\bFEMALE\\b");
    public static final Pattern GENDER_TRANS  = Pattern.compile("\\bTRANSGENDER\\b|\\bTHIRD GENDER\\b");

    // ─────────────────────────────────────────────────
    //  NAME (generic title-case Indian name line)
    // ─────────────────────────────────────────────────

    /**
     * Indian name: 2–5 words, each 2–30 uppercase letters.
     * Used when keyword-anchored extraction fails.
     */
    public static final Pattern GENERIC_NAME =
            Pattern.compile("\\b([A-Z]{2,30}(?:\\s[A-Z]{1,30}){1,4})\\b");

    // ─────────────────────────────────────────────────
    //  PIN CODE
    // ─────────────────────────────────────────────────

    public static final Pattern PIN_CODE =
            Pattern.compile("\\b([1-9][0-9]{5})\\b");

    // ─────────────────────────────────────────────────
    //  HELPERS
    // ─────────────────────────────────────────────────

    /**
     * Find the first match of a pattern in text.
     *
     * @return Optional containing the full match (group 0) or group 1 if present.
     */
    public static Optional<String> findFirst(Pattern pattern, String text) {
        if (text == null) return Optional.empty();
        Matcher m = pattern.matcher(text);
        if (m.find()) {
            String result = m.groupCount() == 1 ? m.group(1) : m.group(0);
            return Optional.of(result.strip());
        }
        return Optional.empty();
    }

    /**
     * Find all non-overlapping matches of a pattern in text.
     */
    public static List<String> findAll(Pattern pattern, String text) {
        List<String> results = new ArrayList<>();
        if (text == null) return results;
        Matcher m = pattern.matcher(text);
        while (m.find()) {
            String r = m.groupCount() == 1 ? m.group(1) : m.group(0);
            results.add(r.strip());
        }
        return results;
    }

    /**
     * Extract text on the SAME LINE as a keyword, after the keyword.
     *
     * @param text     full OCR text
     * @param keyword  uppercase keyword to search for
     * @return         Optional of the text after the keyword on the same line
     */
    public static Optional<String> extractAfterKeyword(String text, String keyword) {
        if (text == null || keyword == null) return Optional.empty();
        int idx = text.indexOf(keyword);
        if (idx < 0) return Optional.empty();

        int start   = idx + keyword.length();
        int lineEnd = text.indexOf('\n', start);
        if (lineEnd < 0) lineEnd = text.length();

        String after = text.substring(start, lineEnd)
                           .replaceAll("^[\\s:/-]+", "")  // strip leading separators
                           .strip();
        return after.isEmpty() ? Optional.empty() : Optional.of(after);
    }

    /**
     * Extract N lines AFTER the line containing the keyword.
     *
     * @param text      full OCR text
     * @param keyword   keyword to locate
     * @param lineCount how many lines after the keyword line to return
     */
    public static Optional<String> extractLinesAfterKeyword(String text, String keyword, int lineCount) {
        if (text == null || keyword == null) return Optional.empty();
        String[] lines = text.split("\n");
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].contains(keyword)) {
                StringBuilder sb = new StringBuilder();
                for (int j = i + 1; j <= i + lineCount && j < lines.length; j++) {
                    if (!lines[j].isBlank()) sb.append(lines[j].strip()).append(" ");
                }
                String result = sb.toString().strip();
                return result.isEmpty() ? Optional.empty() : Optional.of(result);
            }
        }
        return Optional.empty();
    }

    /**
     * Normalise Aadhaar number to "XXXX XXXX XXXX" format.
     */
    public static String formatAadhaar(String raw) {
        String digits = raw.replaceAll("\\s", "");
        if (digits.length() != 12) return raw;
        return digits.substring(0, 4) + " " + digits.substring(4, 8) + " " + digits.substring(8);
    }

    public static boolean isValidAadhaar(String raw) {
        if (raw == null) {
            return false;
        }
        String digits = normaliseDigitLookalikes(raw).replaceAll("[^0-9]", "");
        return digits.matches("^[2-9]\\d{11}$") && hasValidVerhoeffChecksum(digits);
    }

    public static String normaliseDigitLookalikes(String raw) {
        if (raw == null) {
            return null;
        }

        StringBuilder builder = new StringBuilder(raw.length());
        for (char ch : raw.toUpperCase().toCharArray()) {
            builder.append(switch (ch) {
                case 'O', 'Q', 'D' -> '0';
                case 'I', 'L' -> '1';
                case 'Z' -> '2';
                case 'S' -> '5';
                case 'B' -> '8';
                case 'G' -> '6';
                default -> ch;
            });
        }
        return builder.toString();
    }

    public static String normaliseAlphabeticLookalikes(String raw) {
        if (raw == null) {
            return null;
        }

        StringBuilder builder = new StringBuilder(raw.length());
        for (char ch : raw.toUpperCase().toCharArray()) {
            builder.append(switch (ch) {
                case '0' -> 'O';
                case '1' -> 'I';
                case '2' -> 'Z';
                case '5' -> 'S';
                case '6' -> 'G';
                case '8' -> 'B';
                default -> ch;
            });
        }
        return builder.toString();
    }

    public static List<String> findDates(String text) {
        Set<String> results = new LinkedHashSet<>();
        if (text == null) {
            return new ArrayList<>();
        }

        Matcher exact = DATE_FULL.matcher(text.toUpperCase());
        while (exact.find()) {
            results.add(normaliseDate(exact.group()));
        }

        Matcher fuzzy = DATE_FUZZY.matcher(text.toUpperCase());
        while (fuzzy.find()) {
            results.add(normaliseDate(fuzzy.group()));
        }

        results.remove(null);
        results.remove("");
        return new ArrayList<>(results);
    }

    public static String firstDate(String text) {
        List<String> dates = findDates(text);
        return dates.isEmpty() ? null : dates.get(0);
    }

    /**
     * Normalise a date to DD/MM/YYYY regardless of separator.
     */
    public static String normaliseDate(String raw) {
        if (raw == null) return null;
        String candidate = normaliseDigitLookalikes(raw.toUpperCase());
        Matcher m = DATE_FULL.matcher(candidate);
        if (m.find()) {
            String day = m.group(1);
            if (day.length() == 1) day = "0" + day;
            String month = m.group(2);
            if (month.length() == 1) month = "0" + month;
            return day + "/" + month + "/" + m.group(3);
        }
        return candidate.strip();
    }

    private static boolean hasValidVerhoeffChecksum(String digits) {
        int checksum = 0;
        for (int i = 0; i < digits.length(); i++) {
            int digit = digits.charAt(digits.length() - 1 - i) - '0';
            checksum = VERHOEFF_D[checksum][VERHOEFF_P[i % 8][digit]];
        }
        return checksum == 0;
    }
}
