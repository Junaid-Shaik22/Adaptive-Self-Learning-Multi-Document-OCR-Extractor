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
 * DrivingLicenseExtractor – extracts fields from Driving License OCR text.
 *
 * Fields extracted:
 *   - Name
 *   - DL Number
 *   - DOB
 *   - Address
 *   - Valid From
 *   - Valid To
 *
 * Handles variations: state RTOs, laminated cards, smart cards, multi-page PDFs.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DrivingLicenseExtractor implements DocumentExtractor {

    private final FieldValidator fieldValidator;

    // ─── DL-specific patterns ─────────────────────────────────────────────────

    /** DL number: state(2) + RTO(2/3) + year(4) + serial(7), various separators. */
    private static final Pattern DL_PATTERN = Pattern.compile(
            "\\b([A-Z]{2}[-\\s]?[0-9]{2,3}[-\\s]?[0-9]{4}[-\\s]?[0-9]{7})\\b");

    /** Alternate compact formats such as DL0420110149646 or TS01220180013985. */
    private static final Pattern DL_COMPACT = Pattern.compile(
            "\\b([A-Z]{2}[0-9]{13,14})\\b");

    private static final Pattern DOB_LABEL = Pattern.compile(
            "(?:DOB|DATE OF BIRTH|D\\.O\\.B)[:\\s]+([0-9OQDILSZBG]{1,2}[/\\-.][0-9OQDILSZBG]{1,2}[/\\-.][0-9OQDILSZBG]{4})");

    private static final Pattern VALID_FROM_LABEL = Pattern.compile(
            "(?:VALID FROM|ISSUE DATE|VALIDITY FROM|ISSUED ON)[:\\s]+([0-9OQDILSZBG]{1,2}[/\\-.][0-9OQDILSZBG]{1,2}[/\\-.][0-9OQDILSZBG]{4})");

    private static final Pattern VALID_TO_LABEL = Pattern.compile(
            "(?:VALID TO|VALID TILL|EXPIRY DATE|VALIDITY TO|VALID UPTO|EXPIRES ON)[:\\s]+([0-9OQDILSZBG]{1,2}[/\\-.][0-9OQDILSZBG]{1,2}[/\\-.][0-9OQDILSZBG]{4})");


    private static final Pattern HOUSE_PATTERN = Pattern.compile(
            "\\b\\d+[A-Z]?(?:[-/]\\d+[A-Z]?)+(?:[/\\-]\\d+)?\\b");

    // Address markers for DL
    private static final String[] ADDR_KEYWORDS = {
        "S/O", "D/O", "W/O", "C/O", "HOUSE", "FLAT", "PLOT", "DOOR",
        "VILLAGE", "VPO", "PO", "DIST", "DISTRICT", "NEAR", "ROAD",
        "STREET", "NAGAR", "COLONY", "WARD", "ADDRESS", "ADDR"
    };

    // DL document section keywords to skip when scanning for name
    private static final List<String> SKIP_KEYWORDS = List.of(
            "DRIVING", "LICENCE", "LICENSE", "TRANSPORT", "VALID", "CLASS",
            "VEHICLE", "BLOOD", "INDIA", "GOVERNMENT", "DEPT", "DEPARTMENT",
            "MOTOR", "DATE", "DOB", "COV", "RTO", "ISSUED", "AUTHORITY",
            "STATE", "UNION", "INDIAN", "REPUBLIC"
    );

    private static final Set<String> ADDRESS_STOP_WORDS = Set.of(
            "SIGNATURE", "LICENCING AUTHORITY", "LICENSING AUTHORITY",
            "AUTHORITY", "VALID FROM", "VALID TO", "ISSUED", "COV",
            "BLOOD GROUP"
    );

    private static final Set<String> NAME_NOISE_FRAGMENTS = Set.of(
            "SIGNAT", "LICEN", "LIGEN", "AUTH", "TELANGANA", "UNION"
    );

    private static final Set<String> COMMON_NAME_WORDS = Set.of(
            "MOHD", "MOHAMMED", "MOHAMMAD", "KHADER", "VALI",
            "FARAZUDDIN", "MOIZUDDIN", "AHMED", "ALI", "KUMAR", "SINGH", "KHAN"
    );

    @Override
    public ExtractionResult extract(String cleanedText) {
        log.info("DrivingLicenseExtractor running...");
        List<String> lines = normaliseLines(cleanedText);

        ExtractionResult.ExtractionResultBuilder builder =
                ExtractionResult.builder()
                                .documentType(DocumentType.DRIVING_LICENSE.name());

        // ── 1. DL Number ───────────────────────────────────────────────────────
        String dlNumber = extractDlNumber(lines, cleanedText);
        builder.dlNumber(dlNumber);
        log.debug("DL Number: {}", dlNumber);

        // ── 2. DOB ─────────────────────────────────────────────────────────────
        String dob = extractDob(lines, cleanedText);
        builder.dob(dob);
        log.debug("DOB: {}", dob);

        // ── 3. Valid From / Valid To ───────────────────────────────────────────
        String validFrom = extractDate(lines, cleanedText, VALID_FROM_LABEL, "VALID FROM", "ISSUE DATE", "VALIDITY FROM", "ISSUED ON");
        String validTo   = extractDate(lines, cleanedText, VALID_TO_LABEL, "VALID TO", "VALID TILL", "EXPIRY DATE", "VALIDITY TO", "VALID UPTO", "EXPIRES ON");
        builder.validFrom(validFrom);
        builder.validTo(validTo);
        log.debug("Valid From: {}  |  Valid To: {}", validFrom, validTo);

        // ── 4. Name ────────────────────────────────────────────────────────────
        String name = extractName(lines, dob, dlNumber);
        builder.name(name);
        log.debug("Name: {}", name);

        // ── 5. Address ─────────────────────────────────────────────────────────
        String address = extractAddress(lines, name, dlNumber);
        builder.address(address);
        log.debug("Address: {}", address);

        // ── 6. Validation ──────────────────────────────────────────────────────
        List<String> errors = fieldValidator.combine(
                fieldValidator.validateDl(dlNumber),
                fieldValidator.validateName(name, "Name"),
                fieldValidator.validateDate(dob, "DOB"),
                fieldValidator.validateDate(validFrom, "Valid From"),
                fieldValidator.validateDate(validTo,   "Valid To")
        );
        if (!errors.isEmpty()) builder.validationErrors(errors);

        int found = countNonNull(dlNumber, name, address, dob, validFrom, validTo);
        builder.confidence(computeConfidence(found, 6));

        return builder.build();
    }

    // ─── Private helpers ──────────────────────────────────────────────────────

    private String extractDlNumber(List<String> lines, String text) {
        String bestCandidate = null;
        int bestScore = Integer.MIN_VALUE;

        for (int i = 0; i < lines.size(); i++) {
            List<String> candidates = new ArrayList<>();
            candidates.add(lines.get(i));
            if (i + 1 < lines.size()) {
                candidates.add(lines.get(i) + lines.get(i + 1));
                candidates.add(lines.get(i) + " " + lines.get(i + 1));
            }
            if (i + 2 < lines.size()) {
                candidates.add(lines.get(i) + lines.get(i + 1) + lines.get(i + 2));
            }

            for (String candidateSource : candidates) {
                String candidate = extractDlCandidateFromText(candidateSource);
                if (candidate == null) {
                    continue;
                }

                int score = 40;
                if (i <= 4) {
                    score += 30;
                }
                if (containsAny(candidateSource, "TS", "DL", "LICENCE", "LICENSE")) {
                    score += 20;
                }
                if (score > bestScore) {
                    bestScore = score;
                    bestCandidate = candidate;
                }
            }
        }

        if (bestCandidate != null) {
            return bestCandidate;
        }

        return extractDlCandidateFromText(text);
    }

    private String extractDlCandidateFromText(String text) {
        Matcher compactMatcher = DL_COMPACT.matcher(text.replaceAll("\\s+", ""));
        while (compactMatcher.find()) {
            String candidate = normaliseDlCandidate(compactMatcher.group(1));
            if (candidate != null) {
                return candidate;
            }
        }

        Matcher patternMatcher = DL_PATTERN.matcher(text);
        while (patternMatcher.find()) {
            String candidate = normaliseDlCandidate(patternMatcher.group(1));
            if (candidate != null) {
                return candidate;
            }
        }

        String compactText = text.replaceAll("[^A-Z0-9]", "");
        Matcher rawMatcher = Pattern.compile("[A-Z0-9]{15,16}").matcher(compactText);
        while (rawMatcher.find()) {
            String candidate = normaliseDlCandidate(rawMatcher.group());
            if (candidate != null) {
                return candidate;
            }
        }

        return null;
    }

    private String normaliseDlCandidate(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }

        String compact = raw.replaceAll("[^A-Z0-9]", "");
        if (compact.length() < 15 || compact.length() > 16) {
            return null;
        }

        char[] chars = compact.toCharArray();
        for (int i = 0; i < chars.length; i++) {
            if (i < 2) {
                chars[i] = switch (chars[i]) {
                    case '0' -> 'O';
                    case '1' -> 'I';
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
        return RegexUtility.DL_NUMBER_SHORT.matcher(candidate).matches() ? candidate : null;
    }

    private String extractDob(List<String> lines, String text) {
        Matcher m = DOB_LABEL.matcher(text);
        if (m.find()) {
            return RegexUtility.normaliseDate(m.group(1));
        }

        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (!containsAny(line, "DOB", "DATE OF BIRTH", "BIRTH")) {
                continue;
            }
            List<String> dates = RegexUtility.findDates(line);
            if (!dates.isEmpty()) {
                return dates.get(0);
            }
            if (i + 1 < lines.size()) {
                dates = RegexUtility.findDates(lines.get(i + 1));
                if (!dates.isEmpty()) {
                    return dates.get(0);
                }
            }
        }

        return null;
    }

    private String extractDate(List<String> lines, String text, Pattern pattern, String... labels) {
        Matcher m = pattern.matcher(text);
        if (m.find()) {
            return RegexUtility.normaliseDate(m.group(1));
        }

        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (!containsAny(line, labels)) {
                continue;
            }

            String candidate = RegexUtility.firstDate(line);
            if (candidate == null && i + 1 < lines.size()) {
                candidate = RegexUtility.firstDate(lines.get(i + 1));
            }
            if (candidate != null) {
                return candidate;
            }
        }

        return null;
    }

    private String extractName(List<String> lines, String dob, String dlNumber) {
        String bestCandidate = null;
        int bestScore = Integer.MIN_VALUE;
        int dlLineIndex = findDlAnchorIndex(lines, dlNumber);

        for (int i = 0; i < lines.size(); i++) {
            String line = cleanNameCandidate(lines.get(i));
            int score = scoreNameCandidate(line, dob, dlNumber);
            if (score == Integer.MIN_VALUE) {
                continue;
            }

            if (dlLineIndex >= 0) {
                if (i == dlLineIndex + 1) {
                    score += 120;
                } else if (i == dlLineIndex + 2) {
                    score += 70;
                } else if (i > dlLineIndex + 2) {
                    score -= Math.min(90, (i - dlLineIndex - 2) * 18);
                }
            } else if (dlNumber != null && i > 0 && lines.get(i - 1).replaceAll("\\s", "").contains(dlNumber)) {
                score += 40;
            }
            if (i <= 4) {
                score += 15;
            }
            if (i + 1 < lines.size() && isAddressStart(lines.get(i + 1), dlNumber)) {
                score += 35;
            }

            if (score > bestScore) {
                bestScore = score;
                bestCandidate = line;
            }
        }

        return bestCandidate;
    }

    private int scoreNameCandidate(String line, String dob, String dlNumber) {
        if (line == null || line.length() < 4 || line.length() > 50) {
            return Integer.MIN_VALUE;
        }
        if (!line.matches("[A-Z][A-Z ]*")) {
            return Integer.MIN_VALUE;
        }
        if (containsNameNoise(line)) {
            return Integer.MIN_VALUE;
        }
        if (dob != null && line.contains(dob)) {
            return Integer.MIN_VALUE;
        }
        if (dlNumber != null && line.replaceAll("\\s", "").contains(dlNumber)) {
            return Integer.MIN_VALUE;
        }
        for (String skip : SKIP_KEYWORDS) {
            if (line.contains(skip)) {
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
            if (word.length() >= 4 || "MD".equals(word)) {
                strongWords += 1;
                score += 8;
            }
            if (COMMON_NAME_WORDS.contains(word)) {
                score += 8;
            }
        }

        return strongWords >= 2 ? score : Integer.MIN_VALUE;
    }

    private String extractAddress(List<String> lines, String name, String dlNumber) {
        int nameIndex = indexOfLineContaining(lines, name);
        int searchStart = nameIndex >= 0 ? nameIndex + 1 : 0;

        int startIndex = -1;
        for (int i = searchStart; i < lines.size(); i++) {
            if (isAddressStart(lines.get(i), dlNumber)) {
                startIndex = i;
                break;
            }
        }

        if (startIndex < 0 && searchStart > 0) {
            for (int i = 0; i < searchStart; i++) {
                if (isAddressStart(lines.get(i), dlNumber)) {
                    startIndex = i;
                    break;
                }
            }
        }

        if (startIndex < 0) {
            return null;
        }

        List<String> collected = new ArrayList<>();
        for (int i = startIndex; i < lines.size(); i++) {
            String cleaned = cleanAddressLine(lines.get(i));
            if (cleaned.isBlank()) {
                continue;
            }
            if (isAddressStop(cleaned)) {
                break;
            }
            if (name != null && cleaned.equals(name)) {
                continue;
            }
            if (extractDlCandidateFromText(cleaned) != null) {
                continue;
            }
            if (scoreNameCandidate(cleaned, null, null) >= 44
                    && !isAddressStart(cleaned, dlNumber)) {
                continue;
            }
            addAddressLine(collected, cleaned);
            if (RegexUtility.PIN_CODE.matcher(cleaned).find()) {
                break;
            }
            if (collected.size() >= 6) {
                break;
            }
        }

        return collected.isEmpty() ? null : String.join(", ", collected);
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

    private String cleanNameCandidate(String line) {
        if (line == null) {
            return null;
        }
        String[] tokens = line.replaceAll("[^A-Z0-9 ]", " ")
                .replaceAll("\\s{2,}", " ")
                .trim()
                .split("\\s+");

        List<String> cleaned = new ArrayList<>();
        for (String token : tokens) {
            if (token.isBlank()) {
                continue;
            }
            long letters = token.chars().filter(Character::isLetter).count();
            long digits = token.chars().filter(Character::isDigit).count();
            if (letters >= 2 && digits <= 2) {
                token = RegexUtility.normaliseAlphabeticLookalikes(token);
            }
            token = token.replaceAll("[^A-Z]", "");
            if (!token.isBlank()) {
                cleaned.add(token);
            }
        }

        return String.join(" ", cleaned).trim();
    }

    private boolean isAddressStart(String line, String dlNumber) {
        if (line == null || line.isBlank()) {
            return false;
        }

        String compact = line.replaceAll("[^A-Z0-9]", "");
        if (!compact.isEmpty()) {
            if (dlNumber != null && compact.contains(dlNumber)) {
                return false;
            }
            if (extractDlCandidateFromText(line) != null) {
                return false;
            }
            if (compact.matches("[0-9]{8,16}")) {
                return false;
            }
        }

        if (HOUSE_PATTERN.matcher(line).find()) {
            return true;
        }
        for (String keyword : ADDR_KEYWORDS) {
            if (line.contains(keyword)) {
                return true;
            }
        }
        if (RegexUtility.PIN_CODE.matcher(line).find() && line.matches(".*[A-Z].*")) {
            return true;
        }
        return line.matches("^[0-9]{1,3}[)\\].-]\\s+.*");
    }

    private boolean isAddressStop(String line) {
        for (String stopWord : ADDRESS_STOP_WORDS) {
            if (line.contains(stopWord)) {
                return true;
            }
        }
        return containsNameNoise(line);
    }

    private String cleanAddressLine(String rawLine) {
        String cleaned = rawLine.replaceFirst("^(?:ADDRESS|ADDR)\\s*:?\\s*", "")
                .replaceFirst("^[0-9]{1,3}[)\\].]\\s+", "")
                .replaceAll("(?i)\\bINDIAN UNION DRIVING LICEN[CS]E\\b", "")
                .replaceAll("(?i)\\bTELANGANA STATE\\b", "")
                .replaceAll("(?i)\\bSIGNAT\\w*\\b.*$", "")
                .replaceAll("(?i)\\bLICEN\\w*\\s+AUTH\\w*\\b.*$", "")
                .replaceAll("\\s+,", ",")
                .replaceAll(",\\s*,", ",")
                .replaceAll(",{2,}", ",")
                .replaceAll(",\\s*$", "")
                .replaceAll("\\s{2,}", " ")
                .trim();

        if (cleaned.contains(",")) {
            String[] parts = cleaned.split("\\s*,\\s*");
            if (parts.length > 1) {
                String tail = parts[parts.length - 1].trim();
                String prefix = cleaned.substring(0, cleaned.lastIndexOf(',')).trim();
                int longestPrefixToken = 0;
                for (String token : prefix.split("\\s+")) {
                    longestPrefixToken = Math.max(longestPrefixToken, token.length());
                }
                if (tail.matches("[A-Z][A-Z ]{4,}")
                        && (prefix.replaceAll("[A-Z]", "").length() >= prefix.length() / 2 || longestPrefixToken <= 3)) {
                    cleaned = tail;
                }
            }
        }

        if (cleaned.isBlank()
                || extractDlCandidateFromText(cleaned) != null
                || cleaned.matches("^[0-9]{1,6}$")
                || cleaned.matches("^[0-9]{8,16}$")) {
            return "";
        }

        Matcher houseMatcher = HOUSE_PATTERN.matcher(cleaned);
        if (houseMatcher.find() && houseMatcher.start() > 0) {
            cleaned = cleaned.substring(houseMatcher.start()).trim();
        }

        return cleaned;
    }

    private boolean containsNameNoise(String line) {
        for (String fragment : NAME_NOISE_FRAGMENTS) {
            if (line.contains(fragment)) {
                return true;
            }
        }
        return false;
    }

    private void addAddressLine(List<String> collected, String candidate) {
        String candidateKey = comparableAddress(candidate);
        for (int i = 0; i < collected.size(); i++) {
            String existing = collected.get(i);
            String existingKey = comparableAddress(existing);
            if (candidateKey.equals(existingKey)) {
                return;
            }
            if (candidateKey.contains(existingKey) || existingKey.contains(candidateKey)) {
                if (candidateKey.length() > existingKey.length()) {
                    collected.set(i, candidate);
                }
                return;
            }
        }
        collected.add(candidate);
    }

    private String comparableAddress(String line) {
        return line.replaceAll("[^A-Z0-9]", "");
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

    private int findDlAnchorIndex(List<String> lines, String dlNumber) {
        int directIndex = indexOfLineContaining(lines, dlNumber);
        if (directIndex >= 0) {
            return directIndex;
        }

        for (int i = 0; i < lines.size(); i++) {
            if (dlNumber != null && dlNumber.equals(extractDlCandidateFromText(lines.get(i)))) {
                return i;
            }
            if (i + 1 < lines.size()) {
                String combined = lines.get(i) + " " + lines.get(i + 1);
                if (dlNumber != null && dlNumber.equals(extractDlCandidateFromText(combined))) {
                    return i;
                }
            }
        }
        return -1;
    }

    private boolean containsAny(String text, String... values) {
        for (String value : values) {
            if (text.contains(value)) {
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
        int pct = (Math.min(found, total) * 100) / total;
        return pct >= 66 ? "HIGH" : pct >= 33 ? "MEDIUM" : "LOW";
    }
}
