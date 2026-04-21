package com.medical.extractor.util;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.time.temporal.ChronoField;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class MedicalRegexUtil {
    public static final Pattern DATE_TOKEN_PATTERN = Pattern.compile(
            "\\b(?:\\d{1,2}[./-]\\d{1,2}[./-]\\d{2,4}|\\d{1,2}[./-][A-Za-z]{3,9}[./-]\\d{2,4}|\\d{1,2}\\s+[A-Za-z]{3,9},?\\s+\\d{2,4})\\b",
            Pattern.CASE_INSENSITIVE
    );

    private static final List<DateTimeFormatter> DATE_FORMATTERS = List.of(
            new DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern("d/M/uuuu").toFormatter(Locale.ENGLISH).withResolverStyle(ResolverStyle.STRICT),
            new DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern("dd/MM/uuuu").toFormatter(Locale.ENGLISH).withResolverStyle(ResolverStyle.STRICT),
            new DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern("d-M-uuuu").toFormatter(Locale.ENGLISH).withResolverStyle(ResolverStyle.STRICT),
            new DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern("dd-MM-uuuu").toFormatter(Locale.ENGLISH).withResolverStyle(ResolverStyle.STRICT),
            new DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern("d.M.uuuu").toFormatter(Locale.ENGLISH).withResolverStyle(ResolverStyle.STRICT),
            new DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern("dd.MM.uuuu").toFormatter(Locale.ENGLISH).withResolverStyle(ResolverStyle.STRICT),
            new DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern("d-MMM-uuuu").toFormatter(Locale.ENGLISH).withResolverStyle(ResolverStyle.STRICT),
            new DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern("dd-MMM-uuuu").toFormatter(Locale.ENGLISH).withResolverStyle(ResolverStyle.STRICT),
            new DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern("d-MMMM-uuuu").toFormatter(Locale.ENGLISH).withResolverStyle(ResolverStyle.STRICT),
            new DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern("dd-MMMM-uuuu").toFormatter(Locale.ENGLISH).withResolverStyle(ResolverStyle.STRICT),
            new DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern("d MMM uuuu").toFormatter(Locale.ENGLISH).withResolverStyle(ResolverStyle.STRICT),
            new DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern("dd MMM uuuu").toFormatter(Locale.ENGLISH).withResolverStyle(ResolverStyle.STRICT),
            new DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern("d MMMM uuuu").toFormatter(Locale.ENGLISH).withResolverStyle(ResolverStyle.STRICT),
            new DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern("dd MMMM uuuu").toFormatter(Locale.ENGLISH).withResolverStyle(ResolverStyle.STRICT),
            new DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern("d MMM, uuuu").toFormatter(Locale.ENGLISH).withResolverStyle(ResolverStyle.STRICT),
            new DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern("dd MMM, uuuu").toFormatter(Locale.ENGLISH).withResolverStyle(ResolverStyle.STRICT),
            new DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern("d/M/").appendValueReduced(ChronoField.YEAR, 2, 2, 2000).toFormatter(Locale.ENGLISH).withResolverStyle(ResolverStyle.STRICT),
            new DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern("dd/MM/").appendValueReduced(ChronoField.YEAR, 2, 2, 2000).toFormatter(Locale.ENGLISH).withResolverStyle(ResolverStyle.STRICT),
            new DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern("d-M-").appendValueReduced(ChronoField.YEAR, 2, 2, 2000).toFormatter(Locale.ENGLISH).withResolverStyle(ResolverStyle.STRICT),
            new DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern("dd-MM-").appendValueReduced(ChronoField.YEAR, 2, 2, 2000).toFormatter(Locale.ENGLISH).withResolverStyle(ResolverStyle.STRICT),
            new DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern("d.M.").appendValueReduced(ChronoField.YEAR, 2, 2, 2000).toFormatter(Locale.ENGLISH).withResolverStyle(ResolverStyle.STRICT),
            new DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern("dd.MM.").appendValueReduced(ChronoField.YEAR, 2, 2, 2000).toFormatter(Locale.ENGLISH).withResolverStyle(ResolverStyle.STRICT),
            new DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern("d-MMM-").appendValueReduced(ChronoField.YEAR, 2, 2, 2000).toFormatter(Locale.ENGLISH).withResolverStyle(ResolverStyle.STRICT),
            new DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern("dd-MMM-").appendValueReduced(ChronoField.YEAR, 2, 2, 2000).toFormatter(Locale.ENGLISH).withResolverStyle(ResolverStyle.STRICT),
            new DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern("d MMM ").appendValueReduced(ChronoField.YEAR, 2, 2, 2000).toFormatter(Locale.ENGLISH).withResolverStyle(ResolverStyle.STRICT),
            new DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern("dd MMM ").appendValueReduced(ChronoField.YEAR, 2, 2, 2000).toFormatter(Locale.ENGLISH).withResolverStyle(ResolverStyle.STRICT),
            new DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern("d MMM, ").appendValueReduced(ChronoField.YEAR, 2, 2, 2000).toFormatter(Locale.ENGLISH).withResolverStyle(ResolverStyle.STRICT),
            new DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern("dd MMM, ").appendValueReduced(ChronoField.YEAR, 2, 2, 2000).toFormatter(Locale.ENGLISH).withResolverStyle(ResolverStyle.STRICT)
    );

    private MedicalRegexUtil() {
    }

    public static LocalDate parseDate(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = normalizeDateCandidate(value);
        for (DateTimeFormatter formatter : DATE_FORMATTERS) {
            try {
                return LocalDate.parse(normalized, formatter);
            } catch (DateTimeParseException ignored) {
            }
        }
        return null;
    }

    public static String normalizeDate(String value) {
        LocalDate date = parseDate(value);
        if (date == null) {
            return null;
        }
        return date.format(DateTimeFormatter.ofPattern("dd-MM-uuuu"));
    }

    public static String firstDateToken(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        Matcher matcher = DATE_TOKEN_PATTERN.matcher(text);
        while (matcher.find()) {
            String token = normalizeDateCandidate(matcher.group());
            if (parseDate(token) != null) {
                return token;
            }
        }
        return null;
    }

    private static String normalizeDateCandidate(String value) {
        String normalized = value.replace(',', ' ')
                .replaceAll("\\s+", " ")
                .trim();
        if (normalized.matches("(?i).*[./-].*") && !normalized.matches("(?i).*\\b[A-Z]{3,}\\b.*")) {
            normalized = normalized
                    .replace('O', '0')
                    .replace('o', '0')
                    .replace('Q', '0')
                    .replace('D', '0')
                    .replace('I', '1')
                    .replace('l', '1')
                    .replace('|', '1');
        }
        return normalized;
    }
}
