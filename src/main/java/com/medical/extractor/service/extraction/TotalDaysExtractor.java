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
 * Extracts total absent days from medical certificate text with derived logic
 */
@Service
public class TotalDaysExtractor {
    private static final Logger logger = LoggerFactory.getLogger(TotalDaysExtractor.class);

    private static final Pattern[] DAYS_PATTERNS = {
            Pattern.compile("(?i)for\\s+(\\d{1,3})\\s+days?", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(?i)(?:period\\s+of\\s+)?absence\\s+(?:for|of)\\s+(\\d{1,3})\\s*(?:days?)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(?i)(?:rest|leave)\\s+for\\s+(\\d{1,3})\\s*(?:days?)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(?i)total\\s+(?:leave|absence|rest)\\s*[:=]?\\s*(\\d{1,3})\\s*(?:days?)?", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(?i)(?:no\\.?\\s*of|number\\s+of)\\s+days?\\s*[:=]?\\s*(\\d{1,3})", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(?i)(\\d{1,3})\\s+(?:calendar\\s+)?days?(?:\\s+(?:of|absence|leave|rest))?", Pattern.CASE_INSENSITIVE)
    };

    private static final Map<String, Integer> NUMBER_WORDS_MAP = Map.ofEntries(
            Map.entry("one", 1), Map.entry("two", 2), Map.entry("three", 3),
            Map.entry("four", 4), Map.entry("five", 5), Map.entry("six", 6),
            Map.entry("seven", 7), Map.entry("eight", 8), Map.entry("nine", 9),
            Map.entry("ten", 10), Map.entry("eleven", 11), Map.entry("twelve", 12),
            Map.entry("thirteen", 13), Map.entry("fourteen", 14), Map.entry("fifteen", 15),
            Map.entry("sixteen", 16), Map.entry("seventeen", 17), Map.entry("eighteen", 18),
            Map.entry("nineteen", 19), Map.entry("twenty", 20), Map.entry("thirty", 30)
    );

    /**
     * Extract total days with derived logic - calculate from dates if missing
     */
    public String extractTotalDays(String text, String fromDate, String toDate) {
        if (text == null || text.isBlank()) {
            // If no text, calculate from dates
            return calculateDaysFromDates(fromDate, toDate);
        }

        List<Candidate> candidates = new ArrayList<>();

        // Extract from "for ___ days" pattern first
        for (Pattern pattern : DAYS_PATTERNS) {
            Matcher matcher = pattern.matcher(text);
            while (matcher.find()) {
                try {
                    int days = Integer.parseInt(matcher.group(1).trim());
                    if (isValidDayCount(days)) {
                        candidates.add(scoreCandidate(String.valueOf(days), matcher.group(0), fromDate, toDate));
                    }
                } catch (Exception ex) {
                    logger.debug("Could not parse days from: {}", matcher.group(1), ex);
                }
            }
        }

        // Try word-based patterns
        for (Map.Entry<String, Integer> entry : NUMBER_WORDS_MAP.entrySet()) {
            Pattern p = Pattern.compile("(?i)\\b" + entry.getKey() + "\\s+days?\\b");
            Matcher m = p.matcher(text);
            if (m.find()) {
                int days = entry.getValue();
                if (isValidDayCount(days)) {
                    candidates.add(scoreCandidate(String.valueOf(days), m.group(0), fromDate, toDate));
                }
            }
        }

        String calculatedDays = calculateDaysFromDates(fromDate, toDate);

        if (!candidates.isEmpty()) {
            // Sort by score descending
            candidates.sort((a, b) -> Double.compare(b.score, a.score));
            String extractedDays = candidates.get(0).value;

            // IF mismatch: prefer calculated value
            if (calculatedDays != null && !extractedDays.equals(calculatedDays)) {
                Integer extracted = parseInteger(extractedDays);
                Integer calculated = parseInteger(calculatedDays);

                if (extracted != null && calculated != null && Math.abs(extracted - calculated) > 1) {
                    logger.debug("Days mismatch - extracted: {}, calculated: {}, preferring calculated", extractedDays, calculatedDays);
                    return calculatedDays;
                }
            }

            logger.debug("Total days extracted: {} (score: {})", extractedDays, candidates.get(0).score);
            return extractedDays;
        }

        // IF missing: calculate from dates
        logger.debug("No days found in text, calculating from dates: {}", calculatedDays);
        return calculatedDays;
    }

    /**
     * Calculate days from date range
     */
    private String calculateDaysFromDates(String fromDate, String toDate) {
        if (fromDate == null || toDate == null) {
            return null;
        }

        try {
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("dd/MM/yyyy");
            LocalDate from = LocalDate.parse(fromDate, formatter);
            LocalDate to = LocalDate.parse(toDate, formatter);

            if (to.isBefore(from)) {
                return null;
            }

            long days = ChronoUnit.DAYS.between(from, to) + 1; // Include both dates
            return String.valueOf(days);
        } catch (Exception ex) {
            logger.debug("Could not calculate days from dates", ex);
            return null;
        }
    }

    /**
     * Score a days candidate with date validation
     */
    private Candidate scoreCandidate(String days, String context, String fromDate, String toDate) {
        double score = 50.0; // Base score

        try {
            int dayCount = Integer.parseInt(days);

            // Reasonable range (1-365 days)
            if (dayCount >= 1 && dayCount <= 365) {
                score += 30;
            }

            // Prefer shorter leave periods (1-90 days)
            if (dayCount >= 1 && dayCount <= 90) {
                score += 20;
            }

            // Context keywords boost
            String lowerContext = context.toLowerCase();
            if (lowerContext.contains("absence") || lowerContext.contains("rest") || lowerContext.contains("leave")) {
                score += 15;
            }

            if (lowerContext.contains("for")) {
                score += 25; // Highest priority for "for ___ days"
            }

            if (lowerContext.contains("period") || lowerContext.contains("total")) {
                score += 10;
            }

            // Validate against calculated days
            String calculatedDays = calculateDaysFromDates(fromDate, toDate);
            if (calculatedDays != null) {
                Integer extracted = parseInteger(days);
                Integer calculated = parseInteger(calculatedDays);

                if (extracted != null && calculated != null) {
                    if (extracted.equals(calculated)) {
                        score += 40; // Exact match
                    } else if (Math.abs(extracted - calculated) <= 1) {
                        score += 15; // Close match
                    } else {
                        score -= 45; // Significant mismatch
                    }
                }
            }

        } catch (Exception ex) {
            logger.debug("Error scoring days candidate", ex);
        }

        return new Candidate(days, score);
    }

    /**
     * Validate day count
     */
    private boolean isValidDayCount(int days) {
        return days > 0 && days <= 365;
    }

    /**
     * Parse integer safely
     */
    private Integer parseInteger(String value) {
        try {
            return value == null ? null : Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static class Candidate {
        String value;
        double score;

        Candidate(String value, double score) {
            this.value = value;
            this.score = score;
        }

        @Override
        public String toString() {
            return String.format("[%s] (score: %.2f)", value, score);
        }
    }
}
