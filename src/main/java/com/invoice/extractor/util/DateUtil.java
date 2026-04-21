package com.invoice.extractor.util;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.time.temporal.ChronoField;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class DateUtil {
    private static final List<Pattern> DATE_PATTERNS = List.of(
            Pattern.compile("\\b\\d{1,2}/\\d{1,2}/\\d{2,4}\\b"),
            Pattern.compile("\\b\\d{1,2}-\\d{1,2}-\\d{2,4}\\b"),
            Pattern.compile("\\b\\d{1,2}\\.\\d{1,2}\\.\\d{2,4}\\b"),
            Pattern.compile("\\b\\d{1,2}\\.[A-Za-z]{3,9}\\.\\d{2,4}\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\b\\d{1,2}-[A-Za-z]{3,9}-\\d{2,4}\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\b\\d{4}-\\d{1,2}-\\d{1,2}\\b")
    );

    private static final List<DateTimeFormatter> DATE_FORMATTERS = List.of(
            new DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern("d/M/uuuu").toFormatter(Locale.ENGLISH).withResolverStyle(ResolverStyle.STRICT),
            new DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern("dd/MM/uuuu").toFormatter(Locale.ENGLISH).withResolverStyle(ResolverStyle.STRICT),
            new DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern("d-M-uuuu").toFormatter(Locale.ENGLISH).withResolverStyle(ResolverStyle.STRICT),
            new DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern("dd-MM-uuuu").toFormatter(Locale.ENGLISH).withResolverStyle(ResolverStyle.STRICT),
            new DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern("d-[M]-uuuu").toFormatter(Locale.ENGLISH).withResolverStyle(ResolverStyle.STRICT),
            new DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern("dd-[MM]-uuuu").toFormatter(Locale.ENGLISH).withResolverStyle(ResolverStyle.STRICT),
            new DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern("d.M.uuuu").toFormatter(Locale.ENGLISH).withResolverStyle(ResolverStyle.STRICT),
            new DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern("dd.MM.uuuu").toFormatter(Locale.ENGLISH).withResolverStyle(ResolverStyle.STRICT),
            new DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern("d.M.").appendValueReduced(ChronoField.YEAR, 2, 2, 2000).toFormatter(Locale.ENGLISH).withResolverStyle(ResolverStyle.STRICT),
            new DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern("dd.MM.").appendValueReduced(ChronoField.YEAR, 2, 2, 2000).toFormatter(Locale.ENGLISH).withResolverStyle(ResolverStyle.STRICT),
            new DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern("d-MMM-uuuu").toFormatter(Locale.ENGLISH).withResolverStyle(ResolverStyle.STRICT),
            new DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern("dd-MMM-uuuu").toFormatter(Locale.ENGLISH).withResolverStyle(ResolverStyle.STRICT),
            new DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern("d-MMM-").appendValueReduced(ChronoField.YEAR, 2, 2, 2000).toFormatter(Locale.ENGLISH).withResolverStyle(ResolverStyle.STRICT),
            new DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern("dd-MMM-").appendValueReduced(ChronoField.YEAR, 2, 2, 2000).toFormatter(Locale.ENGLISH).withResolverStyle(ResolverStyle.STRICT),
            new DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern("d.MMM.uuuu").toFormatter(Locale.ENGLISH).withResolverStyle(ResolverStyle.STRICT),
            new DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern("dd.MMM.uuuu").toFormatter(Locale.ENGLISH).withResolverStyle(ResolverStyle.STRICT),
            new DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern("uuuu-MM-dd").toFormatter(Locale.ENGLISH).withResolverStyle(ResolverStyle.STRICT)
    );

    private DateUtil() {
    }

    public static List<String> findCandidateDates(String text) {
        List<String> matches = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return matches;
        }
        for (Pattern pattern : DATE_PATTERNS) {
            Matcher matcher = pattern.matcher(text);
            while (matcher.find()) {
                matches.add(matcher.group());
            }
        }
        return matches;
    }

    public static LocalDate parseDate(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        for (DateTimeFormatter formatter : DATE_FORMATTERS) {
            try {
                return LocalDate.parse(value, formatter);
            } catch (DateTimeParseException ignored) {
            }
        }
        return null;
    }

    public static boolean isValidInvoiceDate(String value) {
        LocalDate parsed = parseDate(value);
        if (parsed == null) {
            return false;
        }
        LocalDate cutoff = LocalDate.now().plusMonths(3);
        return parsed.getYear() >= 2000 && !parsed.isAfter(cutoff);
    }

    /**
     * Attempt to repair a date string whose 2-digit year resolves to
     * a future date by subtracting 10 from the year.
     */
    public static String repairFutureDate(String value) {
        if (value == null || value.isBlank()) {
            return value;
        }
        LocalDate parsed = parseDate(value);
        if (parsed == null) {
            return value;
        }
        LocalDate cutoff = LocalDate.now().plusMonths(3);
        if (!parsed.isAfter(cutoff)) {
            return value;
        }
        // Try subtracting 10 years (e.g. 2026 → 2016, not useful)
        // Better: try subtracting multiples of 10 until we find a valid past date
        for (int delta = 10; delta <= 30; delta += 10) {
            LocalDate repaired = parsed.minusYears(delta);
            if (repaired.getYear() >= 2000 && !repaired.isAfter(cutoff)) {
                // Rebuild the date string in the same format
                return rebuildDateString(value, repaired);
            }
        }
        return value;
    }

    private static String rebuildDateString(String original, LocalDate repaired) {
        // Try to preserve the original format
        for (DateTimeFormatter formatter : DATE_FORMATTERS) {
            try {
                LocalDate.parse(original, formatter);
                return repaired.format(formatter);
            } catch (DateTimeParseException ignored) {
            }
        }
        // Fallback: use dd-MM-yyyy
        return repaired.format(DateTimeFormatter.ofPattern("dd-MM-yyyy"));
    }
}
