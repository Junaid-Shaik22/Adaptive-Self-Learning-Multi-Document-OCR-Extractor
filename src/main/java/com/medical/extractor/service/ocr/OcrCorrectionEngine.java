package com.medical.extractor.service.ocr;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

/**
 * Corrects common OCR errors with context awareness
 */
@Service
public class OcrCorrectionEngine {
    private static final Logger logger = LoggerFactory.getLogger(OcrCorrectionEngine.class);
    

    /**
     * Apply all corrections to text
     */
    public String correctText(String text) {
        if (text == null || text.isBlank()) {
            return text;
        }

        logger.debug("Starting OCR correction on text length: {}", text.length());

        text = correctCharacterErrors(text);
        text = correctDateFormats(text);
        text = correctCommonWords(text);

        logger.debug("OCR correction complete");
        return text;
    }

    /**
     * Correct character-level OCR errors with context awareness
     */
    public String correctCharacterErrors(String text) {
        if (text == null || text.isBlank()) {
            return text;
        }

        StringBuilder corrected = new StringBuilder();
        String[] words = text.split("\\s+");

        for (String word : words) {
            corrected.append(correctWord(word)).append(" ");
        }

        return corrected.toString().trim();
    }

    /**
     * Apply context-aware character corrections to a single word
     */
    private String correctWord(String word) {
        if (word == null || word.length() < 2) {
            return word;
        }

        StringBuilder corrected = new StringBuilder(word);

        // Fix common character mistakes
        for (int i = 0; i < corrected.length(); i++) {
            char c = corrected.charAt(i);

            // O -> 0 in date-like patterns
            if (c == 'O' && (isDigitLike(corrected, i - 1) || isDigitLike(corrected, i + 1))) {
                corrected.setCharAt(i, '0');
            }
            // I -> 1 in numeric contexts
            else if (c == 'I' && (isNumericContext(corrected, i))) {
                corrected.setCharAt(i, '1');
            }
            // l -> 1 in numeric contexts
            else if (c == 'l' && (isNumericContext(corrected, i))) {
                corrected.setCharAt(i, '1');
            }
            // S -> 5 in numeric contexts
            else if (c == 'S' && isNumericContext(corrected, i)) {
                corrected.setCharAt(i, '5');
            }
            // B -> 8 in numeric contexts
            else if (c == 'B' && isNumericContext(corrected, i)) {
                corrected.setCharAt(i, '8');
            }
        }

        return corrected.toString();
    }

    /**
     * Check if character is in a numeric context
     */
    private boolean isNumericContext(StringBuilder sb, int index) {
        return (index > 0 && Character.isDigit(sb.charAt(index - 1)))
                || (index < sb.length() - 1 && Character.isDigit(sb.charAt(index + 1)));
    }

    /**
     * Check if adjacent character is digit-like
     */
    private boolean isDigitLike(StringBuilder sb, int index) {
        if (index < 0 || index >= sb.length()) return false;
        char c = sb.charAt(index);
        return Character.isDigit(c) || c == '/' || c == '-' || c == '.';
    }

    /**
     * Correct date formats and common OCR errors in dates
     */
    public String correctDateFormats(String text) {
        if (text == null || text.isBlank()) {
            return text;
        }

        // Fix dates like "1O/02/2O24" -> "10/02/2024"
        text = fixDateOcr(text);

        return text;
    }

    /**
     * Fix common date OCR errors
     */
    private String fixDateOcr(String text) {
        // Replace O with 0 in date patterns
        text = text.replaceAll("(\\d)O(\\d)", "$10$2"); // e.g., "1O02" -> "1002"
        text = text.replaceAll("O(\\d{2})(/)|(\\d{2})O", "0$2$3"); // e.g., "O2/" -> "02/"

        // Fix year patterns
        text = text.replaceAll("(\\d{2})(2O2)([0-9])", "$1202$3"); // "2O24" -> "2024"
        text = text.replaceAll("(/)([0-9])(O)([0-9])", "$1$203$4"); // "/2O24" -> "/2024"

        return text;
    }

    /**
     * Correct common OCR word mistakes
     */
    public String correctCommonWords(String text) {
        if (text == null || text.isBlank()) {
            return text;
        }

        Map<String, String> corrections = new HashMap<>();
        corrections.put("\\bceftify\\b", "certify");
        corrections.put("\\bcertigy\\b", "certify");
        corrections.put("\\brecmmended\\b", "recommended");
        corrections.put("\\breccomended\\b", "recommended");
        corrections.put("\\bmedicai\\b", "medical");
        corrections.put("\\babsence\\b", "absence");
        corrections.put("\\bfitness\\b", "fitness");
        corrections.put("\\bfiitness\\b", "fitness");
        corrections.put("\\brequired\\b", "required");
        corrections.put("\\bfrom\\b", "from");
        corrections.put("\\bfromm\\b", "from");

        for (Map.Entry<String, String> entry : corrections.entrySet()) {
            text = text.replaceAll("(?i)" + entry.getKey(), entry.getValue());
        }

        return text;
    }

    /**
     * Normalize date format to standard dd/MM/yyyy
     */
    public String normalizeDateFormat(String dateStr) {
        if (dateStr == null || dateStr.isBlank()) {
            return null;
        }

        // Try to parse various date formats
        String[] formats = {
                "dd/MM/yyyy", "dd-MM-yyyy", "dd.MM.yyyy",
                "d/M/yyyy", "d-M-yyyy", "d.M.yyyy",
                "dd/MM/yy", "dd-MM-yy", "dd.MM.yy"
        };

        for (String format : formats) {
            try {
                DateTimeFormatter formatter = DateTimeFormatter.ofPattern(format);
                LocalDate date = LocalDate.parse(dateStr.trim(), formatter);
                return date.format(DateTimeFormatter.ofPattern("dd/MM/yyyy"));
            } catch (Exception ex) {
                // Try next format
            }
        }

        return dateStr;
    }

    /**
     * Validate and correct date sequence
     */
    public String validateDateSequence(String fromDate, String toDate) {
        try {
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("dd/MM/yyyy");
            LocalDate from = LocalDate.parse(fromDate, formatter);
            LocalDate to = LocalDate.parse(toDate, formatter);

            if (from.isAfter(to)) {
                logger.warn("Date sequence error: from date {} is after to date {}", fromDate, toDate);
                // Swap dates
                return toDate + " to " + fromDate;
            }

            return fromDate + " to " + toDate;
        } catch (Exception ex) {
            logger.debug("Could not validate date sequence", ex);
            return fromDate + " to " + toDate;
        }
    }
}
