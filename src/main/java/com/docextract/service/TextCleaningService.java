package com.docextract.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * TextCleaningService - normalises raw Tesseract output for reliable parsing.
 *
 * Operations (in order):
 *  1. Null/empty guard
 *  2. Convert to UPPERCASE
 *  3. Fix common OCR character substitutions in context
 *  4. Remove non-printable / control characters
 *  5. Normalize whitespace
 *  6. Preserve line breaks
 *  7. Strip leading/trailing whitespace per line
 *  8. Remove completely blank lines (> 2 consecutive)
 */
@Slf4j
@Service
public class TextCleaningService {

    // Patterns compiled once
    private static final Pattern CTRL_CHARS = Pattern.compile("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F\\x7F]");
    private static final Pattern MULTI_SPACE = Pattern.compile("[ \\t]+");
    private static final Pattern MULTI_NEWLINE = Pattern.compile("\\n{3,}");
    private static final Pattern SPECIAL_CHARS = Pattern.compile("[^A-Z0-9\\s/\\-.:,()#@&'\"\\[\\]]");

    /**
     * Clean and normalise a raw OCR string.
     *
     * @param rawText  text from Tesseract
     * @return         cleaned, uppercased text
     */
    public String clean(String rawText) {
        if (rawText == null || rawText.isBlank()) {
            return "";
        }

        String text = rawText;

        // 1. Uppercase
        text = text.toUpperCase();

        // 2. Remove control characters
        text = CTRL_CHARS.matcher(text).replaceAll("");

        // 3. Fix common OCR mistakes in NUMBER contexts
        //    (applied per-line so context is preserved)
        text = fixOcrMistakesContextual(text);

        // 4. Remove special/garbage characters (keep alphanumeric + useful punctuation)
        text = SPECIAL_CHARS.matcher(text).replaceAll(" ");

        // 5. Normalize horizontal whitespace
        text = MULTI_SPACE.matcher(text).replaceAll(" ");

        // 6. Strip each line
        StringBuilder sb = new StringBuilder();
        for (String line : text.split("\n")) {
            String stripped = line.strip();
            if (!stripped.isEmpty()) {
                sb.append(stripped).append("\n");
            }
        }
        text = sb.toString();

        // 7. Collapse excessive blank lines
        text = MULTI_NEWLINE.matcher(text).replaceAll("\n\n");

        return text.strip();
    }

    public String mergeCleanedTexts(List<String> rawTexts) {
        Map<String, String> uniqueLines = new LinkedHashMap<>();

        for (String rawText : rawTexts) {
            String cleaned = clean(rawText);
            if (cleaned.isBlank()) {
                continue;
            }

            for (String line : cleaned.split("\n")) {
                String stripped = line.strip();
                if (stripped.isEmpty()) {
                    continue;
                }

                String canonical = stripped.replaceAll("[^A-Z0-9]", "");
                if (canonical.length() < 3) {
                    continue;
                }

                int candidateScore = scoreLine(stripped);
                if (candidateScore < -10) {
                    continue;
                }

                String existing = uniqueLines.get(canonical);
                if (existing == null || candidateScore > scoreLine(existing)) {
                    uniqueLines.put(canonical, stripped);
                }
            }
        }

        return String.join("\n", uniqueLines.values()).strip();
    }

    // OCR correction heuristics

    /**
     * Fix common OCR misreads contextually:
     * - In numeric/date tokens: O->0, I->1, S->5, B->8, Z->2
     * - Avoid blanket replacements on mixed identifiers like DL/PAN prefixes
     */
    private String fixOcrMistakesContextual(String text) {
        StringBuilder result = new StringBuilder();

        for (String line : text.split("\n")) {
            result.append(fixLine(line)).append("\n");
        }
        return result.toString();
    }

    private String fixLine(String line) {
        line = restoreCommonLabels(line);

        String[] parts = line.split("\\s+");
        StringBuilder rebuilt = new StringBuilder();
        for (String part : parts) {
            if (part.isEmpty()) {
                continue;
            }
            if (rebuilt.length() > 0) {
                rebuilt.append(' ');
            }
            rebuilt.append(shouldNormaliseAsNumeric(part) ? normaliseNumericToken(part) : part);
        }

        return restoreCommonLabels(rebuilt.toString());
    }

    private boolean shouldNormaliseAsNumeric(String token) {
        String compact = token.replaceAll("[^A-Z0-9]", "");
        if (compact.length() < 4) {
            return false;
        }

        if (compact.matches("^[A-Z]{2,}[A-Z0-9]*$")) {
            return false;
        }

        if (token.matches("^[0-9OQDILSZBG]{1,2}[/\\-.][0-9OQDILSZBG]{1,2}[/\\-.][0-9OQDILSZBG]{4}$")) {
            return true;
        }

        long digitLikeChars = compact.chars()
                .filter(ch -> Character.isDigit(ch) || "OQDILSZBG".indexOf(ch) >= 0)
                .count();

        return digitLikeChars >= compact.length() - 1;
    }

    private String normaliseNumericToken(String token) {
        StringBuilder builder = new StringBuilder(token.length());
        for (char ch : token.toCharArray()) {
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

    private int scoreLine(String line) {
        int score = line.length();
        String[] words = line.trim().split("\\s+");
        long shortWords = java.util.Arrays.stream(words)
                .filter(word -> !word.isBlank())
                .filter(word -> word.length() < 3)
                .count();

        if (line.matches(".*\\b[A-Z]{5}[0-9]{4}[A-Z]\\b.*")) {
            score += 35;
        }

        if (line.matches(".*\\d{2}/\\d{2}/\\d{4}.*")) {
            score += 25;
        }
        if (line.matches(".*\\b[2-9]\\d{3}\\s\\d{4}\\s\\d{4}\\b.*")) {
            score += 20;
        }
        if (line.contains("AADHAAR") || line.contains("DOB") || line.contains("VID")
                || line.contains("NAME") || line.contains("FATHER")
                || line.contains("ACCOUNT") || line.contains("GOVT")) {
            score += 12;
        }
        if (line.matches("[A-Z]{3,}(?:\\s[A-Z]{3,}){1,4}")) {
            score += 16;
        }

        score -= (int) line.chars().filter(ch -> ch == '?').count() * 4;
        score -= (int) line.chars().filter(ch -> ch == '\'').count() * 2;
        score -= (int) shortWords * 10;

        if (words.length >= 4 && shortWords >= words.length - 1) {
            score -= 30;
        }
        return score;
    }

    private String restoreCommonLabels(String line) {
        return line.replace("D08", "DOB")
                   .replace(" D0B", " DOB")
                   .replace("/D0B", "/DOB")
                   .replace("D0B", "DOB")
                   .replace("B4/D0B", "B4/DOB")
                   .replace("BA/D0B", "BA/DOB")
                   .replace("DATE 0F BIRTH", "DATE OF BIRTH")
                   .replace("YOUR AADHAAR N0", "YOUR AADHAAR NO")
                   .replace("Y0UR AADHAAR N0", "YOUR AADHAAR NO")
                   .replace("ENRO1MENT", "ENROLMENT")
                   .replace("ENR01MENT", "ENROLMENT")
                   .replace("V1D", "VID")
                   .replace("FATHER'5", "FATHER'S")
                   .replace("60VT", "GOVT")
                   .replace("G0VT", "GOVT");
    }
}
