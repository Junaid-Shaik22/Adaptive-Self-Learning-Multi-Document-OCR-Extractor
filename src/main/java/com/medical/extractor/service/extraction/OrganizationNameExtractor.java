package com.medical.extractor.service.extraction;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Extracts organization name from medical certificate text with strict logic
 * Only considers TOP 15% of document, uppercase lines with < 6 words
 */
@Service
public class OrganizationNameExtractor {
    private static final Logger logger = LoggerFactory.getLogger(OrganizationNameExtractor.class);

    private static final List<String> IGNORE_PHRASES = List.of(
            "government of india", "department of atomic energy", "medical certificate",
            "fitness certificate", "certificate", "doctor", "registration", "report no",
            "signature", "circular", "recruitment", "mandatory courses",
            "attention is invited", "consumption of mandatory courses",
            "medical officer", "hospital", "clinic", "dispensary"
    );

    /**
     * Extract organization name with strict boundary logic
     */
    public String extractOrganizationName(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }

        List<String> lines = Arrays.asList(text.split("\n"));
        int topLineCount = Math.max(1, (lines.size() * 15) / 100); // TOP 15%

        List<Candidate> candidates = new ArrayList<>();

        // Only consider top 15% of document
        for (int i = 0; i < Math.min(topLineCount, lines.size()); i++) {
            String line = lines.get(i).trim();
            if (isValidOrgCandidate(line)) {
                candidates.add(scoreCandidate(line, i));
            }
        }

        if (candidates.isEmpty()) {
            logger.debug("No organization name candidates found");
            return null;
        }

        // Sort by score descending
        candidates.sort((a, b) -> Double.compare(b.score, a.score));

        String best = candidates.get(0).value;
        logger.debug("Organization name extracted: {} (score: {})", best, candidates.get(0).score);

        return best;
    }

    /**
     * Validate organization candidate with strict rules
     */
    private boolean isValidOrgCandidate(String line) {
        if (line == null || line.isBlank() || line.length() < 3) {
            return false;
        }

        String lower = line.toLowerCase();

        // Reject lines containing numbers
        if (line.matches(".*\\d.*")) {
            return false;
        }

        // Reject address lines (contains commas, slashes, etc.)
        if (line.contains(",") || line.contains("/") || line.contains("\\")) {
            return false;
        }

        // Reject long paragraphs (> 6 words)
        long wordCount = Arrays.stream(line.split("\\s+"))
                .filter(w -> !w.isBlank()).count();
        if (wordCount > 6) {
            return false;
        }

        // Reject ignore phrases
        for (String phrase : IGNORE_PHRASES) {
            if (lower.contains(phrase)) {
                return false;
            }
        }

        // Must be mostly uppercase
        long uppercaseCount = line.chars().filter(Character::isUpperCase).count();
        long letterCount = line.chars().filter(Character::isLetter).count();
        if (letterCount > 0 && (double) uppercaseCount / letterCount < 0.7) {
            return false;
        }

        return true;
    }
    
    
    /**
     * Score organization candidate
     */
    private Candidate scoreCandidate(String line, int position) {
        double score = 100.0; // Base score for valid candidates

        // Prefer earlier positions in top 15%
        score += Math.max(0, 15 - position);

        // Prefer reasonable length (3-30 chars)
        int len = line.length();
        if (len >= 3 && len <= 30) {
            score += 20;
        } else if (len > 30) {
            score -= 10;
        }

        // Prefer uppercase dominant
        long uppercaseCount = line.chars().filter(Character::isUpperCase).count();
        long letterCount = line.chars().filter(Character::isLetter).count();
        if (letterCount > 0) {
            double uppercaseRatio = (double) uppercaseCount / letterCount;
            score += uppercaseRatio * 15;
        }

        return new Candidate(line, score);
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
