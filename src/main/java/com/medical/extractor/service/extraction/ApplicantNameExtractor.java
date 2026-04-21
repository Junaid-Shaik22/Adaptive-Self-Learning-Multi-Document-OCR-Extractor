package com.medical.extractor.service.extraction;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts applicant/patient name from medical certificate text with boundary logic
 */
@Service
public class ApplicantNameExtractor {
    private static final Logger logger = LoggerFactory.getLogger(ApplicantNameExtractor.class);

    private static final Pattern[] NAME_PATTERNS = {
            Pattern.compile("(?i)\\b(?:shri|smt|kum|mr|ms|mrs|patient)\\s+"),
            Pattern.compile("(?i)\\bpatient\\s+name[:=]?\\s*"),
            Pattern.compile("(?i)\\bname\\s*[:=]?\\s*"),
            Pattern.compile("(?i)\\bcertify\\s+that\\s+"),
            Pattern.compile("(?i)\\b(?:this|the)\\s+is\\s+to\\s+certify\\s+that\\s+")
    };

    private static final List<String> BOUNDARY_KEYWORDS = List.of(
            "from", "to", "rest", "absence", "doctor", "medical officer",
            "date", "signature", "department", "hospital", "clinic"
    );

    /**
     * Extract applicant name with boundary detection
     */
    public String extractApplicantName(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }

        List<Candidate> candidates = new ArrayList<>();

        // Try each pattern
        for (Pattern pattern : NAME_PATTERNS) {
            Matcher matcher = pattern.matcher(text);
            while (matcher.find()) {
                String name = extractNameWithBoundary(text, matcher.start(), matcher.end());
                if (isValidName(name)) {
                    candidates.add(scoreCandidate(name, matcher.start()));
                }
            }
        }

        if (candidates.isEmpty()) {
            logger.debug("No applicant name candidates found");
            return null;
        }

        // Sort by score descending
        candidates.sort((a, b) -> Double.compare(b.score, a.score));

        String best = candidates.get(0).value;
        logger.debug("Applicant name extracted: {} (score: {})", best, candidates.get(0).score);

        return best;
    }

    /**
     * Extract name with strict boundary detection
     */
    private String extractNameWithBoundary(String text, int startPos, int endPos) {
        // Extract text AFTER the keyword
        String afterKeyword = text.substring(endPos).trim();

        // Find boundary: next keyword, number, or punctuation
        int boundaryIndex = findBoundaryIndex(afterKeyword);

        String candidate = boundaryIndex > 0 ?
            afterKeyword.substring(0, boundaryIndex).trim() :
            afterKeyword;

        // Clean up the name
        return cleanName(candidate);
    }

    /**
     * Find boundary index in text
     */
    private int findBoundaryIndex(String text) {
        // Stop at parentheses or brackets first (employee IDs, etc.)
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '(' || c == '[' || c == '{' || c == '<') {
                return i;
            }
        }

        // Stop at next keyword
        for (String keyword : BOUNDARY_KEYWORDS) {
            int index = text.toLowerCase().indexOf(keyword.toLowerCase());
            if (index >= 0) {
                return index;
            }
        }

        // Stop at punctuation
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '.' || c == ',' || c == ';' || c == ':' || c == '/' || c == '\\') {
                return i;
            }
        }

        // Stop at first number (as last resort)
        for (int i = 0; i < text.length(); i++) {
            if (Character.isDigit(text.charAt(i))) {
                return i;
            }
        }

        return -1; // No boundary found
    }

    /**
     * Clean and normalize name
     */
    private String cleanName(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }

        // Remove extra whitespace
        name = name.replaceAll("\\s+", " ").trim();

        // Remove trailing punctuation
        name = name.replaceAll("[.,;:]+$", "");

        // Normalize handwritten characters
        name = name.replaceAll("4", "a")
                  .replaceAll("0", "o")
                  .replaceAll("1", "i")
                  .replaceAll("3", "e")
                  .replaceAll("5", "s")
                  .replaceAll("8", "b");

        // Remove non-alphabetic characters except spaces, hyphens, apostrophes
        name = name.replaceAll("[^A-Za-z\\s'-]", "");

        // Remove titles/prefixes
        name = name.replaceAll("(?i)^(?:mr|ms|mrs|shri|smt|kum|patient)\\s+", "");

        return name.trim();
    }

    /**
     * Validate name with strict rules
     */
    private boolean isValidName(String name) {
        if (name == null || name.isBlank()) {
            return false;
        }

        String trimmed = name.trim();

        // Must be at least 3 chars
        if (trimmed.length() < 3) {
            return false;
        }

        // Must be less than 80 chars
        if (trimmed.length() > 80) {
            return false;
        }

        // Must contain only alphabetic characters, spaces, hyphens, apostrophes
        if (!trimmed.matches("^[A-Za-z\\s'-]+$")) {
            return false;
        }

        // Must not contain digits
        if (trimmed.matches(".*\\d.*")) {
            return false;
        }

        // Must have 2-4 words max
        long wordCount = Arrays.stream(trimmed.split("\\s+"))
                .filter(w -> !w.isBlank()).count();
        if (wordCount < 2 || wordCount > 4) {
            return false;
        }

        // Must have at least one letter
        if (!trimmed.matches(".*[a-zA-Z].*")) {
            return false;
        }

        return true;
    }

    /**
     * Score name candidate
     */
    private Candidate scoreCandidate(String name, int position) {
        double score = 100.0; // Base score for valid names

        // Prefer earlier positions
        if (position < 1000) {
            score += Math.max(0, 20 - (position / 50));
        }

        // Prefer 2-3 words
        long wordCount = Arrays.stream(name.split("\\s+"))
                .filter(w -> !w.isBlank()).count();
        if (wordCount >= 2 && wordCount <= 3) {
            score += 15;
        }

        // Prefer proper capitalization
        if (Character.isUpperCase(name.charAt(0))) {
            score += 10;
        }

        // Prefer reasonable length
        int len = name.length();
        if (len >= 5 && len <= 30) {
            score += 10;
        }

        return new Candidate(name, score);
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

