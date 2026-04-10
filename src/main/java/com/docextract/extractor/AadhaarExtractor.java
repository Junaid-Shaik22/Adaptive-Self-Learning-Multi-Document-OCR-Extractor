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
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AadhaarExtractor – extracts fields from Aadhaar card OCR text.
 *
 * Fields extracted:
 *   - Name
 *   - Aadhaar Number
 *   - DOB
 *   - Gender
 *   - Address
 *
 * Handles front-only, back-only, full card, and cropped images.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AadhaarExtractor implements DocumentExtractor {

    private final FieldValidator fieldValidator;

    // ─── Additional patterns specific to Aadhaar ──────────────────────────────
    private static final Pattern AADHAAR_LABELLED_PATTERN = Pattern.compile(
            "(?:YOUR\\s+AADHAAR\\s+NO|AADHAAR\\s+NO|AADHAAR\\s+NUMBER)[:\\s-]*([2-9]\\d{3}\\s?\\d{4}\\s?\\d{4})");

    private static final Pattern DOB_LABEL_PATTERN = Pattern.compile(
            "(?:DOB|DATE OF BIRTH|D\\.O\\.B|B4/DOB|BA/DOB|B4DOB|BADOB)[:\\s-]*([0-9]{2}[/\\-.][0-9]{2}[/\\-.][0-9]{4})");

    private static final Pattern YEAR_OF_BIRTH_PATTERN =
            Pattern.compile("YEAR OF BIRTH[:\\s-]*(\\d{4})");

    private static final Pattern RELATION_PATTERN =
            Pattern.compile("\\b(?:S/O|D/O|W/O|C/O)\\b");

    private static final Pattern HOUSE_PATTERN = Pattern.compile(
            "\\b\\d+[A-Z]?(?:[-/]\\d+[A-Z]?)+(?:[/\\-]\\d+)?\\b|\\b(?:HOUSE|FLAT|PLOT|DOOR)\\b");


    private static final Set<String> NAME_STOP_WORDS = Set.of(
            "GOVERNMENT", "INDIA", "AADHAAR", "AUTHORITY", "IDENTIFICATION",
            "UNIQUE", "UIDAI", "ENROLMENT", "ENROLLMENT", "ADDRESS", "YOUR",
            "VID", "SIGNATURE", "VALID", "MALE", "FEMALE", "TRANSGENDER",
            "DOB", "DATE", "BIRTH", "HYDERABAD", "AMBERPET", "PATEL",
            "NAGAR", "ANDHRA", "PRADESH", "TELANGANA", "TEMPLE", "DOWNLOAD"
    );

    private static final Set<String> ADDRESS_HINT_WORDS = Set.of(
            "S/O", "D/O", "W/O", "C/O", "HOUSE", "FLAT", "PLOT", "DOOR",
            "ROAD", "STREET", "LANE", "NAGAR", "COLONY", "VILLAGE",
            "MANDAL", "DIST", "DISTRICT", "STATE", "TEMPLE", "HYDERABAD",
            "AMBERPET", "PATEL", "TELANGANA", "ANDHRA", "PRADESH", "PIN"
    );

    private static final Set<String> ADDRESS_STOP_MARKERS = Set.of(
            "YOUR AADHAAR NO", "AADHAAR NO", "AADHAAR NUMBER", "DOB",
            "DATE OF BIRTH", "YEAR OF BIRTH", "MALE", "FEMALE",
            "TRANSGENDER", "VID", "DOWNLOAD DATE", "GOVERNMENT OF INDIA",
            "UNIQUE IDENTIFICATION", "ENROLMENT", "ENROLLMENT", "SIGNATURE"
    );

    private static final Set<String> COMMON_NAME_WORDS = Set.of(
            "MOHD", "MD", "MOHAMMAD", "MOHAMMED", "SHAIK", "SHEIKH",
            "SHAIKH", "BEGUM", "BANO", "KUMAR", "DEVI", "SINGH", "KHAN",
            "ALI", "VALI", "AHMED", "HUSSAIN"
    );

    @Override
    public ExtractionResult extract(String cleanedText) {
        log.info("AadhaarExtractor running...");
        List<String> lines = normaliseLines(cleanedText);

        ExtractionResult.ExtractionResultBuilder builder =
                ExtractionResult.builder()
                                .documentType(DocumentType.AADHAAR.name());

        // ── 1. Aadhaar Number ──────────────────────────────────────────────────
        String aadhaarNumber = extractAadhaarNumber(lines, cleanedText);
        builder.aadhaarNumber(aadhaarNumber);
        log.debug("Aadhaar number: {}", aadhaarNumber);

        // ── 2. DOB ─────────────────────────────────────────────────────────────
        String dob = extractDob(lines, cleanedText);
        builder.dob(dob);
        log.debug("DOB: {}", dob);

        // ── 3. Gender ──────────────────────────────────────────────────────────
        String gender = extractGender(cleanedText);
        builder.gender(gender);
        log.debug("Gender: {}", gender);

        // ── 4. Name ────────────────────────────────────────────────────────────
        String name = extractName(lines, dob, gender);
        builder.name(name);
        log.debug("Name: {}", name);

        // ── 5. Address ─────────────────────────────────────────────────────────
        String address = extractAddress(lines);
        builder.address(address);
        log.debug("Address: {}", address);

        // ── 6. Validation ──────────────────────────────────────────────────────
        List<String> errors = fieldValidator.combine(
                fieldValidator.validateAadhaar(aadhaarNumber),
                fieldValidator.validateName(name, "Name"),
                fieldValidator.validateDob(dob)
        );
        if (!errors.isEmpty()) builder.validationErrors(errors);

        // Confidence signal: number of found fields / total expected
        int found = countNonNull(aadhaarNumber, dob, gender, name, address);
        builder.confidence(computeConfidence(found, 5));

        return builder.build();
    }

    // ─── Private helpers ──────────────────────────────────────────────────────



    private String extractGender(String text) {
        if (RegexUtility.GENDER_TRANS.matcher(text).find())  return "Transgender";
        if (RegexUtility.GENDER_FEMALE.matcher(text).find()) return "Female";
        if (RegexUtility.GENDER_MALE.matcher(text).find())   return "Male";
        return null;
    }

    private boolean isLikelyName(String line, String dob, String gender) {
        if (line == null || line.isBlank()) return false;
        if (line.length() < 4 || line.length() > 60)  return false;
        if (!line.matches("[A-Z][A-Z .]*")) return false;  // only letters and spaces/dots
        if (line.contains("INDIA") || line.contains("AADHAAR") ||
            line.contains("UIDAI") || line.contains("GOVERNMENT") ||
            line.contains("AUTHORITY") || line.contains("IDENTIFICATION") ||
            line.contains("DOB") || line.contains("DATE")) return false;
        if (dob != null && line.contains(dob)) return false;
        if ("MALE".equals(line) || "FEMALE".equals(line)) return false;
        String[] words = line.split("\\s+");
        return words.length >= 2 && words.length <= 5;
    }

    private List<String> normaliseLines(String cleanedText) {
        Map<String, String> unique = new LinkedHashMap<>();
        for (String rawLine : cleanedText.split("\\R")) {
            String line = rawLine == null ? "" : rawLine.strip().replaceAll("\\s{2,}", " ");
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

    private String extractAadhaarNumber(List<String> lines, String cleanedText) {
        Matcher labelledMatcher = AADHAAR_LABELLED_PATTERN.matcher(cleanedText);
        if (labelledMatcher.find()) {
            return RegexUtility.formatAadhaar(labelledMatcher.group(1));
        }

        String bestCandidate = null;
        int bestScore = Integer.MIN_VALUE;

        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            for (String candidate : RegexUtility.findAll(RegexUtility.AADHAAR_NUMBER, line)) {
                String digits = candidate.replaceAll("\\s", "");
                if (!digits.matches("^[2-9]\\d{11}$")) {
                    continue;
                }

                int score = digits.length();
                if (containsAny(line, "YOUR AADHAAR NO", "AADHAAR NO", "AADHAAR NUMBER")) {
                    score += 120;
                }
                if (containsAny(line, "VID")) {
                    score -= 200;
                }
                if (i > 0 && containsAny(lines.get(i - 1), "YOUR AADHAAR NO", "AADHAAR NO")) {
                    score += 90;
                }
                if (i + 1 < lines.size() && containsAny(lines.get(i + 1), "VID")) {
                    score += 20;
                }
                if (containsAny(line, "ENROLMENT", "ENROLLMENT")) {
                    score -= 40;
                }

                if (score > bestScore) {
                    bestScore = score;
                    bestCandidate = candidate;
                }
            }
        }

        return bestCandidate != null ? RegexUtility.formatAadhaar(bestCandidate) : null;
    }

    private String extractDob(List<String> lines, String cleanedText) {
        Matcher directMatcher = DOB_LABEL_PATTERN.matcher(cleanedText);
        if (directMatcher.find()) {
            return RegexUtility.normaliseDate(directMatcher.group(1));
        }

        String bestCandidate = null;
        int bestScore = Integer.MIN_VALUE;

        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            String candidate = null;
            int score = 0;

            Matcher labelledMatcher = DOB_LABEL_PATTERN.matcher(line);
            if (labelledMatcher.find()) {
                candidate = labelledMatcher.group(1);
                score += 120;
            }

            if (candidate == null && containsAny(line, "DOB", "DATE OF BIRTH", "B4/DOB", "BA/DOB")) {
                candidate = firstDate(line);
                if (candidate != null) {
                    score += 90;
                } else if (i + 1 < lines.size()) {
                    candidate = firstDate(lines.get(i + 1));
                    if (candidate != null) {
                        score += 80;
                    }
                }
            }

            if (candidate == null) {
                candidate = firstDate(line);
                if (candidate != null) {
                    score += 20;
                }
            }

            if (candidate == null) {
                Matcher yearMatcher = YEAR_OF_BIRTH_PATTERN.matcher(line);
                if (yearMatcher.find()) {
                    candidate = yearMatcher.group(1);
                    score += 30;
                }
            }

            if (candidate == null) {
                continue;
            }

            if (containsAny(line, "MALE", "FEMALE", "TRANSGENDER")) {
                score += 15;
            }
            if ((i > 0 && containsAny(lines.get(i - 1), "MALE", "FEMALE", "TRANSGENDER"))
                    || (i + 1 < lines.size() && containsAny(lines.get(i + 1), "MALE", "FEMALE", "TRANSGENDER"))) {
                score += 15;
            }
            if (containsAny(line, "VID", "ENROLMENT", "ENROLLMENT")) {
                score -= 40;
            }

            if (score > bestScore) {
                bestScore = score;
                bestCandidate = candidate;
            }
        }

        return bestCandidate != null && bestCandidate.contains("/")
                ? RegexUtility.normaliseDate(bestCandidate)
                : bestCandidate;
    }

    private String extractName(List<String> lines, String dob, String gender) {
        Map<String, Integer> candidates = new LinkedHashMap<>();

        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);

            if (containsAny(line, "DOB", "DATE OF BIRTH", "YEAR OF BIRTH", "MALE", "FEMALE", "TRANSGENDER")) {
                addNameCandidate(candidates, previousNameFragment(lines, i - 1, dob, gender), 85);
                addNameCandidate(candidates, combinePreviousNameFragments(lines, i - 1, dob, gender), 95);
            }

            if (isLikelyNameLine(line, dob, gender)) {
                addNameCandidate(candidates, line, scoreNameLine(line, i, lines));
            }
        }

        return candidates.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .map(name -> refineNameWithSupport(name, lines))
                .orElse(null);
    }

    private String extractAddress(List<String> lines) {
        List<String> addressLines = collectAddressLines(lines);
        return addressLines.isEmpty() ? null : String.join(", ", addressLines);
    }

    private List<String> collectAddressLines(List<String> lines) {
        int start = -1;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if ("TO".equals(line) || line.startsWith("TO ")) {
                start = i + 1;
                break;
            }
        }

        if (start < 0) {
            for (int i = 0; i < lines.size(); i++) {
                if (isAddressLine(lines.get(i))) {
                    start = i;
                    break;
                }
            }
        }

        if (start < 0) {
            return List.of();
        }

        List<String> collected = new ArrayList<>();
        for (int i = start; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.isBlank()) {
                continue;
            }
            if (!collected.isEmpty() && isAddressStop(line)) {
                break;
            }
            if (shouldSkipAddressLine(line)) {
                continue;
            }
            if (collected.isEmpty() && !isAddressLine(line)) {
                continue;
            }

            String cleaned = cleanAddressLine(line);
            if (cleaned.isBlank()) {
                continue;
            }
            if (!collected.contains(cleaned)) {
                collected.add(cleaned);
            }

            if (RegexUtility.PIN_CODE.matcher(cleaned).find()) {
                break;
            }

            if (collected.size() >= 8) {
                break;
            }
        }

        return collected;
    }

    private boolean isLikelyNameLine(String line, String dob, String gender) {
        if (!isLikelyName(line, dob, gender)) {
            return false;
        }
        return !RELATION_PATTERN.matcher(line).find() && !isAddressLine(line);
    }

    private int scoreNameLine(String line, int index, List<String> lines) {
        int score = 30;
        String[] tokens = line.replaceAll("[^A-Z ]", " ").trim().split("\\s+");
        score += tokens.length == 3 ? 18 : 12;

        if (index + 1 < lines.size() && containsAny(lines.get(index + 1), "DOB", "DATE OF BIRTH", "MALE", "FEMALE", "TRANSGENDER")) {
            score += 55;
        }
        if (index + 2 < lines.size() && containsAny(lines.get(index + 2), "DOB", "DATE OF BIRTH", "MALE", "FEMALE", "TRANSGENDER")) {
            score += 20;
        }
        if (index > 0 && containsAny(lines.get(index - 1), "GOVERNMENT OF INDIA", "AADHAAR", "UNIQUE IDENTIFICATION")) {
            score += 10;
        }

        for (String token : tokens) {
            if (COMMON_NAME_WORDS.contains(token)) {
                score += 6;
            }
        }

        return score;
    }

    private String previousNameFragment(List<String> lines, int startIndex, String dob, String gender) {
        for (int i = startIndex; i >= 0; i--) {
            String candidate = lines.get(i);
            if (isLikelyNameLine(candidate, dob, gender)) {
                return candidate;
            }
            if (isAddressStop(candidate)) {
                break;
            }
        }
        return null;
    }

    private String combinePreviousNameFragments(List<String> lines, int startIndex, String dob, String gender) {
        String latest = previousNameFragment(lines, startIndex, dob, gender);
        if (latest == null) {
            return null;
        }

        int latestIndex = lines.indexOf(latest);
        if (latestIndex <= 0) {
            return latest;
        }

        String previous = previousNameFragment(lines, latestIndex - 1, dob, gender);
        if (previous == null) {
            return latest;
        }

        String combined = (previous + " " + latest).replaceAll("\\s{2,}", " ").trim();
        return isLikelyNameLine(combined, dob, gender) ? combined : latest;
    }

    private void addNameCandidate(Map<String, Integer> candidates, String value, int score) {
        if (value == null || value.isBlank()) {
            return;
        }
        candidates.merge(value, score, (oldV, newV) -> Math.max(oldV, newV));
    }

    private boolean isAddressLine(String line) {
        if (line == null || line.isBlank()) {
            return false;
        }
        if (line.matches(".*\\b\\d{1,2}[/\\-.]\\d{1,2}[/\\-.]\\d{4}\\b.*")
                && !RELATION_PATTERN.matcher(line).find()) {
            return false;
        }
        if (RELATION_PATTERN.matcher(line).find() || HOUSE_PATTERN.matcher(line).find() || RegexUtility.PIN_CODE.matcher(line).find()) {
            return true;
        }
        for (String hint : ADDRESS_HINT_WORDS) {
            if (line.contains(hint)) {
                return true;
            }
        }
        return false;
    }

    private boolean shouldSkipAddressLine(String line) {
        return containsAny(line, "GOVERNMENT OF INDIA", "UNIQUE IDENTIFICATION", "ENROLMENT",
                "ENROLLMENT", "SIGNATURE", "DOWNLOAD DATE", "UIDAI");
    }

    private boolean isAddressStop(String line) {
        if (line == null || line.isBlank()) {
            return false;
        }
        for (String marker : ADDRESS_STOP_MARKERS) {
            if (line.contains(marker)) {
                return true;
            }
        }
        return false;
    }

    private String cleanAddressLine(String line) {
        String cleaned = line.replaceFirst("^TO\\s*:?\\s*", "")
                .replaceFirst("^ADDRESS\\s*:?\\s*", "")
                .replaceAll("\\bG\\s+(S/O|D/O|W/O|C/O)\\b", "$1")
                .replaceFirst("^[A-Z0-9][\\s.:-]*(?=(?:\\(?\\d|[A-Z]{3,}))", "")
                .replaceAll("^[,;:.()\\s-]+", "")
                .replaceAll("\\s+,", ",")
                .replaceAll(",{2,}", ",")
                .replaceAll("\\s{2,}", " ")
                .trim();

        Matcher pinMatcher = RegexUtility.PIN_CODE.matcher(cleaned);
        if (pinMatcher.find()) {
            cleaned = cleaned.substring(0, pinMatcher.end()).trim();
        }

        cleaned = cleaned.replaceAll(",?\\s*\\b[6-9]\\d{9}\\b$", "")
                .replaceAll("[,;:.()\\s-]+$", "")
                .trim();

        return cleaned.matches("^[6-9]\\d{9}$") ? "" : cleaned;
    }

    private String refineNameWithSupport(String name, List<String> lines) {
        String[] tokens = name.split("\\s+");
        Set<String> supportTokens = extractSupportTokens(lines);

        for (int i = 0; i < tokens.length; i++) {
            String directNormalisation = normaliseCommonNameToken(tokens[i]);
            if (directNormalisation != null) {
                tokens[i] = directNormalisation;
                continue;
            }
            String replacement = findNearToken(tokens[i], supportTokens);
            if (replacement == null) {
                replacement = findNearToken(tokens[i], COMMON_NAME_WORDS);
            }
            if (replacement != null) {
                tokens[i] = replacement;
            }
        }
        return String.join(" ", tokens);
    }

    private String normaliseCommonNameToken(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        if (COMMON_NAME_WORDS.contains(token)) {
            return token;
        }

        String[] variants = {
                token.replace('L', 'I'),
                token.replace('1', 'I'),
                token.replace('0', 'O'),
                token.replace('5', 'S'),
                token.replace('8', 'B')
        };

        for (String variant : variants) {
            if (COMMON_NAME_WORDS.contains(variant)) {
                return variant;
            }
        }
        return null;
    }

    private Set<String> extractSupportTokens(List<String> lines) {
        java.util.Set<String> supportTokens = new java.util.LinkedHashSet<>(COMMON_NAME_WORDS);
        supportTokens.addAll(lines.stream()
                .filter(line -> !line.matches(".*\\d.*"))
                .filter(line -> !isAddressLine(line))
                .map(line -> line.replaceAll("[^A-Z ]", " "))
                .flatMap(line -> List.of(line.trim().split("\\s+")).stream())
                .filter(token -> token.length() >= 3)
                .filter(token -> !NAME_STOP_WORDS.contains(token))
                .collect(java.util.stream.Collectors.toSet()));
        return supportTokens;
    }

    private String findNearToken(String token, Set<String> supportTokens) {
        return supportTokens.stream()
                .filter(candidate -> !candidate.equals(token))
                .filter(candidate -> candidate.length() == token.length())
                .filter(candidate -> candidate.charAt(0) == token.charAt(0))
                .filter(candidate -> levenshtein(candidate, token) == 1)
                .min(Comparator.naturalOrder())
                .orElse(null);
    }

    private int levenshtein(String left, String right) {
        int[][] distance = new int[left.length() + 1][right.length() + 1];

        for (int i = 0; i <= left.length(); i++) {
            distance[i][0] = i;
        }
        for (int j = 0; j <= right.length(); j++) {
            distance[0][j] = j;
        }

        for (int i = 1; i <= left.length(); i++) {
            for (int j = 1; j <= right.length(); j++) {
                int cost = left.charAt(i - 1) == right.charAt(j - 1) ? 0 : 1;
                distance[i][j] = Math.min(
                        Math.min(distance[i - 1][j] + 1, distance[i][j - 1] + 1),
                        distance[i - 1][j - 1] + cost
                );
            }
        }

        return distance[left.length()][right.length()];
    }

    private String firstDate(String text) {
        Matcher matcher = RegexUtility.DATE_FULL.matcher(text);
        return matcher.find() ? matcher.group() : null;
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
        int count = 0;
        for (Object v : values) if (v != null) count++;
        return count;
    }

    private String computeConfidence(int found, int total) {
        int pct = (found * 100) / total;
        if (pct >= 80) return "HIGH";
        if (pct >= 50) return "MEDIUM";
        return "LOW";
    }
}
