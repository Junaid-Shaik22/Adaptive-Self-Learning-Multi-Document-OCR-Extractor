package com.medical.extractor.service.ocr;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.regex.Pattern;

/**
 * Cleans OCR output text to normalize formatting and reduce noise
 */
@Service
public class OcrTextCleaner {
    private static final Logger logger = LoggerFactory.getLogger(OcrTextCleaner.class);
    
    private static final Pattern EXTRA_SPACES = Pattern.compile("\\s{2,}");
    private static final Pattern EXTRA_NEWLINES = Pattern.compile("\\n{3,}");
    private static final Pattern NOISE_SYMBOLS = Pattern.compile("[^a-zA-Z0-9\\s.,()/\\-:;@\\n]");

    /**
     * Clean OCR text comprehensively
     */
    public String cleanText(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }

        logger.debug("Starting text cleaning. Original length: {}", text.length());

        // 1. Normalize Unicode
        text = text.trim();
        
        // 2. Remove extra spaces (but preserve single spaces)
        text = EXTRA_SPACES.matcher(text).replaceAll(" ");
        
        // 3. Normalize line breaks (Windows/Unix/Mac)
        text = text.replaceAll("\\r\\n", "\n").replaceAll("\\r", "\n");
        
        // 4. Reduce extra newlines
        text = EXTRA_NEWLINES.matcher(text).replaceAll("\n\n");
        
        // 5. Remove noise symbols (keep only common characters)
        text = NOISE_SYMBOLS.matcher(text).replaceAll("");
        
        // 6. Clean up individual lines
        text = cleanLines(text);
        
        // 7. Merge broken words
        text = fixBrokenWords(text);
        
        text = text.trim();
        logger.debug("Text cleaning complete. Final length: {}", text.length());
        
        return text;
    }

    /**
     * Clean individual lines
     */
    private String cleanLines(String text) {
        StringBuilder sb = new StringBuilder();
        String[] lines = text.split("\n");
        
        for (String line : lines) {
            line = line.trim();
            if (!line.isBlank()) {
                // Remove leading/trailing special characters from line
                line = line.replaceAll("^[^a-zA-Z0-9]+", "").replaceAll("[^a-zA-Z0-9]+$", "");
                if (!line.isBlank()) {
                    sb.append(line).append("\n");
                }
            }
        }
        
        return sb.toString();
    }

    /**
     * Fix broken words (e.g., "a b c" -> "abc" when appropriate)
     */
    private String fixBrokenWords(String text) {
        // Fix common single-letter breaks
        text = text.replaceAll("\\b([a-zA-Z])\\s+([a-zA-Z])\\s+([a-zA-Z])\\b", "$1$2$3");
        text = text.replaceAll("\\b([a-zA-Z])\\s+([a-zA-Z])\\b(?![a-zA-Z])", "$1$2");
        
        return text;
    }

    /**
     * Clean text for specific field extraction (aggressive cleaning)
     */
    public String cleanForFieldExtraction(String line) {
        if (line == null || line.isBlank()) {
            return "";
        }

        line = line.trim();
        
        // Remove noise
        line = NOISE_SYMBOLS.matcher(line).replaceAll("");
        
        // Normalize spaces
        line = EXTRA_SPACES.matcher(line).replaceAll(" ");
        
        // Remove leading/trailing non-alphanumeric
        line = line.replaceAll("^[^a-zA-Z0-9]*", "").replaceAll("[^a-zA-Z0-9]*$", "");
        
        return line.trim();
    }

    /**
     * Extract words only (remove numbers and special chars for name extraction)
     */
    public String extractWordsOnly(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }

        // Remove numbers and special characters, keep only letters and spaces
        String cleaned = text.replaceAll("[^a-zA-Z\\s]", "");
        cleaned = EXTRA_SPACES.matcher(cleaned).replaceAll(" ");
        return cleaned.trim();
    }

    /**
     * Normalize whitespace and line breaks
     */
    public String normalizeWhitespace(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }

        text = text.replaceAll("\\r\\n", "\n").replaceAll("\\r", "\n");
        text = EXTRA_SPACES.matcher(text).replaceAll(" ");
        text = EXTRA_NEWLINES.matcher(text).replaceAll("\n\n");
        
        return text.trim();
    }
}
