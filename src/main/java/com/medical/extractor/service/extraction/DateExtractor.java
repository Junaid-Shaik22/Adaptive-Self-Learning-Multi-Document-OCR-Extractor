package com.medical.extractor.service.extraction;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts from date and to date from medical certificate text with context + correction
 */
@Service
public class DateExtractor {
    private static final Logger logger = LoggerFactory.getLogger(DateExtractor.class);

    private static final Pattern[] DATE_RANGE_PATTERNS = {
            Pattern.compile("(?i)(?:absence\\s+from\\s+duty\\s+)?from\\s+(.{1,30})\\s+(?:to|till|upto|up\\s*to)\\s+(.{1,30})"),
            Pattern.compile("(?i)(?:rest\\s+)?from\\s+(.{1,30})\\s+(?:to|till|upto|up\\s*to)\\s+(.{1,30})"),
            Pattern.compile("(?i)(?:leave\\s+)?from\\s+(.{1,30})\\s+(?:to|till|upto|up\\s*to)\\s+(.{1,30})"),
            Pattern.compile("(?i)(?:with\\s+effect\\s+)?from\\s+(.{1,30})\\s+(?:to|till|upto|up\\s*to)\\s+(.{1,30})")
    };

    private static final Pattern[] INDIVIDUAL_DATE_PATTERNS = {
            Pattern.compile("(\\d{1,2})[.//\\-](\\d{1,2})[.//\\-](\\d{2,4})"),
            Pattern.compile("(\\d{1,2})\\.(\\d{1,2})\\.(\\d{4})"),
            Pattern.compile("(\\d{4})[.//\\-](\\d{1,2})[.//\\-](\\d{1,2})")
    };

    /**
     * Extract from and to dates with OCR correction and validation
     */
    public Map<String, String> extractDates(String text) {
        Map<String, String> dates = new HashMap<>();

        if (text == null || text.isBlank()) {
            return dates;
        }

        // Apply OCR correction to entire text first
        String correctedText = correctOcrText(text);

        List<DateRangeCandidate> candidates = new ArrayList<>();

        // Try range patterns - extract BOTH dates in SAME LINE
        String[] lines = correctedText.split("\n");
        for (String line : lines) {
            line = line.trim();
            if (line.isBlank()) continue;

            for (Pattern pattern : DATE_RANGE_PATTERNS) {
                Matcher matcher = pattern.matcher(line);
                if (matcher.find()) {
                    String fromToken = extractDateToken(matcher.group(1));
                    String toToken = extractDateToken(matcher.group(2));

                    if (fromToken != null && toToken != null) {
                        LocalDate fromDate = parseDate(fromToken);
                        LocalDate toDate = parseDate(toToken);

                        if (fromDate != null && toDate != null && !toDate.isBefore(fromDate)) {
                            candidates.add(new DateRangeCandidate(
                                normalizeDate(fromToken),
                                normalizeDate(toToken),
                                scoreDateRange(line, fromDate, toDate)
                            ));
                        }
                    }
                }
            }
        }

        if (!candidates.isEmpty()) {
            // Sort by score and pick best
            candidates.sort((a, b) -> Double.compare(b.score, a.score));
            DateRangeCandidate best = candidates.get(0);

            dates.put("fromDate", best.fromDate);
            dates.put("toDate", best.toDate);
            logger.debug("Date range extracted: {} to {} (score: {})", best.fromDate, best.toDate, best.score);
            return dates;
        }

        logger.debug("No valid date range found");
        return dates;
    }

    /**
     * Apply OCR correction before validation
     */
    private String correctOcrText(String text) {
        if (text == null) return null;

        String corrected = text;
        // O → 0, I → 1 corrections
        corrected = corrected.replaceAll("(?<=[\\d/\\-])O|O(?=[\\d/\\-])", "0");
        corrected = corrected.replaceAll("(?<=[\\d/\\-])I|I(?=[\\d/\\-])", "1");
        corrected = corrected.replaceAll("(?<=[\\d/\\-])l|l(?=[\\d/\\-])", "1");

        // 7 <-> / inside dates
        corrected = corrected.replaceAll("(\\d{1,2})[7|T](\\d{1,2})[7|T](\\d{2,4})", "$1/$2/$3");

        return corrected;
    }

    /**
     * Extract date token from string
     */
    private String extractDateToken(String dateStr) {
        if (dateStr == null || dateStr.isBlank()) {
            return null;
        }

        dateStr = dateStr.trim();

        // Try each date pattern
        for (Pattern pattern : INDIVIDUAL_DATE_PATTERNS) {
            Matcher matcher = pattern.matcher(dateStr);
            if (matcher.find()) {
                return matcher.group(); // Return the full match
            }
        }

        return null;
    }

    /**
     * Parse date from token
     */
    private LocalDate parseDate(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }

        for (Pattern pattern : INDIVIDUAL_DATE_PATTERNS) {
            Matcher matcher = pattern.matcher(token);
            if (matcher.find()) {
                try {
                    String day = matcher.group(1);
                    String month = matcher.group(2);
                    String year = matcher.group(3);

                    // Normalize year
                    if (year.length() == 2) {
                        int y = Integer.parseInt(year);
                        year = (y > 30) ? "19" + year : "20" + year;
                    }

                    // Pad with zeros
                    day = String.format("%02d", Integer.parseInt(day));
                    month = String.format("%02d", Integer.parseInt(month));

                    // Validate
                    if (isValidDate(day, month, year)) {
                        return LocalDate.of(Integer.parseInt(year), Integer.parseInt(month), Integer.parseInt(day));
                    }
                } catch (Exception ex) {
                    logger.debug("Could not parse date token: {}", token, ex);
                }
            }
        }

        return null;
    }

    /**
     * Normalize date to DD/MM/YYYY format
     */
    private String normalizeDate(String token) {
        LocalDate date = parseDate(token);
        if (date != null) {
            return date.format(DateTimeFormatter.ofPattern("dd/MM/yyyy"));
        }
        return token;
    }

    /**
     * Score date range candidate
     */
    private double scoreDateRange(String line, LocalDate from, LocalDate to) {
        double score = 100.0;

        // Prefer exact phrases
        String lower = line.toLowerCase();
        if (lower.contains("from") && lower.contains("to")) {
            score += 30;
        }
        if (lower.contains("rest from")) {
            score += 25;
        }
        if (lower.contains("absence from")) {
            score += 20;
        }

        // Prefer logical date order
        if (!to.isBefore(from)) {
            score += 20;
        }

        // Prefer reasonable date ranges (not too long)
        long days = ChronoUnit.DAYS.between(from, to) + 1;
        if (days >= 1 && days <= 365) {
            score += 15;
        } else if (days > 365) {
            score -= 30;
        }

        // Prefer dates in same line
        score += 10;

        return score;
    }

    /**
     * Validate date components
     */
    private boolean isValidDate(String day, String month, String year) {
        try {
            int d = Integer.parseInt(day);
            int m = Integer.parseInt(month);
            int y = Integer.parseInt(year);

            if (d < 1 || d > 31 || m < 1 || m > 12 || y < 1900 || y > 2100) {
                return false;
            }

            LocalDate.of(y, m, d);
            return true;
        } catch (Exception ex) {
            return false;
        }
    }

    /**
     * Calculate days between dates
     */
    public long calculateDaysBetween(String fromDate, String toDate) {
        if (fromDate == null || toDate == null) {
            return 0;
        }

        try {
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("dd/MM/yyyy");
            LocalDate from = LocalDate.parse(fromDate, formatter);
            LocalDate to = LocalDate.parse(toDate, formatter);

            long days = ChronoUnit.DAYS.between(from, to) + 1; // Include both dates
            logger.debug("Days between {} and {}: {}", fromDate, toDate, days);

            return days;
        } catch (Exception ex) {
            logger.debug("Could not calculate days between dates", ex);
            return 0;
        }
    }

    /**
     * Validate date sequence
     */
    public boolean isValidDateSequence(String fromDate, String toDate) {
        if (fromDate == null || toDate == null) {
            return false;
        }

        try {
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("dd/MM/yyyy");
            LocalDate from = LocalDate.parse(fromDate, formatter);
            LocalDate to = LocalDate.parse(toDate, formatter);

            return !from.isAfter(to);
        } catch (Exception ex) {
            logger.debug("Could not validate date sequence", ex);
            return false;
        }
    }

    private static class DateRangeCandidate {
        String fromDate;
        String toDate;
        double score;

        DateRangeCandidate(String fromDate, String toDate, double score) {
            this.fromDate = fromDate;
            this.toDate = toDate;
            this.score = score;
        }
    }
}
