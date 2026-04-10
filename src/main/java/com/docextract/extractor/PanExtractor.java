package com.docextract.extractor;

import com.docextract.factory.DocumentExtractor;
import com.docextract.model.DocumentType;
import com.docextract.model.ExtractionResult;
import com.docextract.util.FieldValidator;
import com.docextract.util.RegexUtility;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * PanExtractor – extracts fields from PAN card OCR text.
 *
 * Fields extracted:
 *   - Name
 *   - Father's Name
 *   - DOB
 *   - PAN Number
 *
 * PAN card layout (top → bottom):
 *   INCOME TAX DEPARTMENT / GOVT OF INDIA
 *   PERMANENT ACCOUNT NUMBER
 *   PAN NUMBER (e.g. ABCDE1234F)
 *   Name
 *   Father's Name
 *   Date of Birth (DD/MM/YYYY)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PanExtractor implements DocumentExtractor {

    private final FieldValidator fieldValidator;

    // ─── PAN-specific label patterns ──────────────────────────────────────────

    private static final Pattern DOB_LABEL = Pattern.compile(
            "(?:DATE OF BIRTH|DOB|D\\.O\\.B)[:\\s/]+([0-9]{2}[/\\-.][0-9]{2}[/\\-.][0-9]{4})");



    private static final Pattern PAN_CANDIDATE = Pattern.compile("\\b[A-Z0-9]{10}\\b");

    // Known PAN header/label lines to skip during name scanning
    private static final List<String> SKIP_KEYWORDS = List.of(
            "INCOME TAX", "DEPARTMENT", "PERMANENT ACCOUNT", "GOVERNMENT",
            "GOVT", "INDIA", "ACCOUNT NUMBER", "DATE OF BIRTH", "DOB",
            "SIGNATURE", "FATHER", "NAME"
    );

    private static final Set<String> NAME_SKIP_WORDS = Set.of(
            "INCOME", "TAX", "DEPARTMENT", "GOVT", "GOVERNMENT", "INDIA",
            "PERMANENT", "ACCOUNT", "NUMBER", "CARD", "SIGNATURE", "DATE",
            "BIRTH"
    );

    private static final Set<String> COMMON_NAME_WORDS = Set.of(
            "SHAIK", "SHAIKH", "SHEIKH", "MOHD", "MOHAMMED", "MOHAMMAD",
            "KHADER", "VALI", "FARAZUDDIN", "FARAZ", "AHMED", "ALI",
            "KUMAR", "DEVI", "SINGH", "KHAN", "BEGUM", "BANO", "JUNAID"
    );

    @Override
    public ExtractionResult extract(String cleanedText) {
        log.info("PanExtractor running...");

        ExtractionResult.ExtractionResultBuilder builder =
                ExtractionResult.builder()
                                .documentType(DocumentType.PAN.name());

        // ── 1. PAN Number ──────────────────────────────────────────────────────
        String panNumber = extractPanNumber(cleanedText);
        builder.panNumber(panNumber);
        log.debug("PAN number: {}", panNumber);

        // ── 2. DOB ─────────────────────────────────────────────────────────────
        String dob = extractDobImproved(cleanedText);
        builder.dob(dob);
        log.debug("DOB: {}", dob);

        // ── 3. Name & Father Name ──────────────────────────────────────────────
        String[] nameAndFather = extractNameAndFatherNameImproved(cleanedText, panNumber, dob);
        builder.name(nameAndFather[0]);
        builder.fatherName(nameAndFather[1]);
        log.debug("Name: {}  |  Father: {}", nameAndFather[0], nameAndFather[1]);

        // ── 4. Validation ──────────────────────────────────────────────────────
        List<String> errors = fieldValidator.combine(
                fieldValidator.validatePan(panNumber),
                fieldValidator.validateName(nameAndFather[0], "Name"),
                fieldValidator.validateDob(dob)
        );
        if (!errors.isEmpty()) builder.validationErrors(errors);

        int found = countNonNull(panNumber, dob, nameAndFather[0], nameAndFather[1]);
        builder.confidence(computeConfidence(found, 4));

        return builder.build();
    }

    // ─── Private helpers ──────────────────────────────────────────────────────


    private String extractDobImproved(String text) {
        List<String> lines = normaliseLines(text);
        String bestDob = null;
        int bestScore = Integer.MIN_VALUE;

        Matcher direct = DOB_LABEL.matcher(text);
        while (direct.find()) {
            String candidate = RegexUtility.normaliseDate(direct.group(1));
            if (isValidDate(candidate)) {
                return candidate;
            }
        }

        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            List<String> lineDates = RegexUtility.findAll(RegexUtility.DATE_FULL, line);
            for (String rawDate : lineDates) {
                String candidate = RegexUtility.normaliseDate(rawDate);
                if (!isValidDate(candidate)) {
                    continue;
                }

                int score = 20;
                if (containsAny(line, "DATE OF BIRTH", "DOB")) {
                    score += 60;
                }
                if ((i > 0 && containsAny(lines.get(i - 1), "DATE OF BIRTH", "DOB"))
                        || (i + 1 < lines.size() && containsAny(lines.get(i + 1), "DATE OF BIRTH", "DOB"))) {
                    score += 35;
                }

                if (score > bestScore) {
                    bestScore = score;
                    bestDob = candidate;
                }
            }
        }

        return bestDob;
    }

    private String[] extractNameAndFatherNameImproved(String text, String panNumber, String dob) {
        List<String> lines = normaliseLines(text);
        String name = null;
        String father = null;

        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);

            if (name == null && isNameLabelLine(line)) {
                name = chooseBetterPersonName(
                        extractValueAfterLabel(line),
                        nextLikelyName(lines, i + 1, dob)
                );
            }

            if (father == null && isFatherLabelLine(line)) {
                father = chooseBetterPersonName(
                        extractValueAfterLabel(line),
                        nextLikelyName(lines, i + 1, dob)
                );
            }
        }

        int panLineIndex = indexOfLineContaining(lines, panNumber);
        if (panLineIndex >= 0) {
            List<String> candidates = new ArrayList<>();
            for (int i = panLineIndex + 1; i < lines.size(); i++) {
                String line = lines.get(i);
                if (containsAny(line, "DATE OF BIRTH", "DOB")) {
                    break;
                }
                String cleanedCandidate = cleanPersonName(line);
                if (isStrongPersonName(cleanedCandidate, dob)) {
                    candidates.add(cleanedCandidate);
                    if (candidates.size() == 2) {
                        break;
                    }
                }
            }

            if (name == null && !candidates.isEmpty()) {
                name = candidates.get(0);
            }
            if (father == null && candidates.size() > 1) {
                father = candidates.get(1);
            }
        }

        if (name == null || father == null) {
            List<String> candidates = new ArrayList<>();
            for (String line : lines) {
                String cleanedCandidate = cleanPersonName(line);
                if (isStrongPersonName(cleanedCandidate, dob)) {
                    candidates.add(cleanedCandidate);
                }
            }

            if (name == null && !candidates.isEmpty()) {
                name = candidates.get(0);
            }
            if (father == null && candidates.size() > 1) {
                father = candidates.get(1);
            }
        }

        return new String[]{name, father};
    }

    private String extractPanNumber(String text) {
        List<String> lines = normaliseLines(text);

        Matcher directMatch = RegexUtility.PAN_NUMBER.matcher(text);
        if (directMatch.find()) {
            return directMatch.group(1);
        }

        String bestCandidate = null;
        int bestScore = Integer.MIN_VALUE;

        for (int i = 0; i < lines.size(); i++) {
            String compactLine = lines.get(i).replace(" ", "");
            Matcher matcher = PAN_CANDIDATE.matcher(compactLine);
            while (matcher.find()) {
                String candidate = normalisePanCandidate(matcher.group());
                if (candidate == null) {
                    continue;
                }

                int score = 40;
                if (containsAny(lines.get(i), "PERMANENT ACCOUNT", "ACCOUNT NUMBER", "PAN")) {
                    score += 30;
                }
                if (i > 0 && containsAny(lines.get(i - 1), "PERMANENT ACCOUNT", "ACCOUNT NUMBER")) {
                    score += 20;
                }

                if (score > bestScore) {
                    bestScore = score;
                    bestCandidate = candidate;
                }
            }
        }

        return bestCandidate;
    }

    private List<String> normaliseLines(String text) {
        Map<String, String> unique = new LinkedHashMap<>();
        for (String rawLine : text.split("\\R")) {
            String line = rawLine.strip().replaceAll("\\s{2,}", " ");
            if (line.isEmpty()) {
                continue;
            }
            String canonical = line.replaceAll("[^A-Z0-9]", "");
            if (!canonical.isEmpty() && !unique.containsKey(canonical)) {
                unique.put(canonical, line);
            }
        }
        return new ArrayList<>(unique.values());
    }

    private String normalisePanCandidate(String token) {
        String compact = token.replaceAll("[^A-Z0-9]", "");
        if (compact.length() != 10) {
            return null;
        }

        char[] chars = compact.toCharArray();
        for (int i = 0; i < chars.length; i++) {
            if (i < 5 || i == 9) {
                chars[i] = switch (chars[i]) {
                    case '0' -> 'O';
                    case '1' -> 'I';
                    case '8' -> 'B';
                    case '5' -> 'S';
                    default -> chars[i];
                };
            } else {
                chars[i] = switch (chars[i]) {
                    case 'O', 'Q', 'D' -> '0';
                    case 'I', 'L' -> '1';
                    case 'Z' -> '2';
                    case 'S' -> '5';
                    case 'B' -> '8';
                    case 'G' -> '6';
                    default -> chars[i];
                };
            }
        }

        String candidate = new String(chars);
        return RegexUtility.PAN_NUMBER.matcher(candidate).matches() ? candidate : null;
    }

    private boolean isStrongPersonName(String line, String dob) {
        return scorePersonName(line, dob) >= 45;
    }

    private boolean isNameLabelLine(String line) {
        return line.contains("NAME") && !line.contains("FATHER");
    }

    private boolean isFatherLabelLine(String line) {
        return line.contains("FATHER");
    }

    private String extractValueAfterLabel(String line) {
        String value = line.replaceFirst("^.*?(?:FATHER[' ]*S?\\s*NAME|NAME)\\s*[:/|\\-]*\\s*", "")
                .strip();
        value = cleanPersonName(value);
        return isStrongPersonName(value, null) ? value : null;
    }

    private String nextLikelyName(List<String> lines, int startIndex, String dob) {
        for (int i = startIndex; i < lines.size(); i++) {
            String line = lines.get(i);
            if (containsAny(line, "DATE OF BIRTH", "DOB")) {
                break;
            }
            String cleaned = cleanPersonName(line);
            if (isStrongPersonName(cleaned, dob)) {
                return cleaned;
            }
        }
        return null;
    }

    private String chooseBetterPersonName(String first, String second) {
        int firstScore = scorePersonName(first, null);
        int secondScore = scorePersonName(second, null);
        return secondScore > firstScore ? second : first;
    }

    private String cleanPersonName(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }

        String candidate = raw.replaceAll("[^A-Z ]", " ")
                .replaceAll("\\s{2,}", " ")
                .trim();

        if (candidate.isEmpty()) {
            return null;
        }

        String[] tokens = candidate.split("\\s+");
        List<String> cleaned = new ArrayList<>();

        for (String token : tokens) {
            if (NAME_SKIP_WORDS.contains(token)) {
                break;
            }

            boolean meaningful = token.length() >= 3 || "MD".equals(token);
            if (!meaningful) {
                if (cleaned.size() >= 2) {
                    break;
                }
                continue;
            }

            cleaned.add(token);
            if (cleaned.size() == 4) {
                break;
            }
        }

        if (cleaned.size() < 2) {
            return null;
        }

        return String.join(" ", cleaned);
    }

    private int scorePersonName(String line, String dob) {
        if (line == null || line.isBlank() || line.length() < 5 || line.length() > 60) {
            return Integer.MIN_VALUE;
        }
        if (!line.matches("[A-Z][A-Z ]*")) {
            return Integer.MIN_VALUE;
        }
        if (dob != null && line.contains(dob)) {
            return Integer.MIN_VALUE;
        }
        for (String skip : SKIP_KEYWORDS) {
            if (line.startsWith(skip) || line.contains(skip)) {
                return Integer.MIN_VALUE;
            }
        }

        String[] words = line.split("\\s+");
        if (words.length < 2 || words.length > 4) {
            return Integer.MIN_VALUE;
        }

        int score = 20;
        int strongWords = 0;
        for (String word : words) {
            if (NAME_SKIP_WORDS.contains(word)) {
                return Integer.MIN_VALUE;
            }
            if (word.length() >= 4 || "MD".equals(word)) {
                strongWords += 1;
                score += 8;
            } else {
                score -= 8;
            }
            if (COMMON_NAME_WORDS.contains(word)) {
                score += 10;
            }
        }

        if (strongWords < 2) {
            return Integer.MIN_VALUE;
        }
        if (words.length == 3) {
            score += 10;
        }

        return score;
    }

    private int indexOfLineContaining(List<String> lines, String value) {
        if (value == null || value.isBlank()) {
            return -1;
        }
        String compact = value.replaceAll("\\s", "");
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).replaceAll("\\s", "").contains(compact)) {
                return i;
            }
        }
        return -1;
    }

    private boolean isValidDate(String value) {
        return fieldValidator.validateDob(value).isEmpty();
    }

    private boolean containsAny(String line, String... values) {
        for (String value : values) {
            if (line.contains(value)) {
                return true;
            }
        }
        return false;
    }

    private int countNonNull(Object... values) {
        int c = 0;
        for (Object v : values) if (v != null) c++;
        return c;
    }

    private String computeConfidence(int found, int total) {
        int pct = (found * 100) / total;
        return pct >= 75 ? "HIGH" : pct >= 50 ? "MEDIUM" : "LOW";
    }
}
