package com.medical.extractor.extractor;

import com.invoice.extractor.util.RegexUtil;
import com.medical.extractor.model.MedicalLeaveData;
import com.medical.extractor.model.MedicalOcrDocument;
import com.medical.extractor.model.MedicalOcrPage;
import com.medical.extractor.service.impl.MedicalLineIndexingService;
import com.medical.extractor.util.MedicalRegexUtil;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MedicalExtractor {
    private static final Pattern APPLICANT_PATTERN_1 = Pattern.compile(
            "(?i)(?:this\\s+is\\s+to\\s+certify\\s+that\\s+)?(?:mr|ms|mrs|shri|smt|kum|patient)(?:\\s*/\\s*(?:mr|ms|mrs|shri|smt|kum|patient))*\\.?\\s+([A-Za-z][A-Za-z0-9 .()/'-]{1,80}?)(?=\\s*(?:\\(\\d{4,}\\)|is\\b|aged\\b|of\\b|$))"
    );
    private static final Pattern APPLICANT_PATTERN_2 = Pattern.compile(
            "(?i)(?:shri|smt|kum|mr|ms|mrs|patient)\\.?\\s+([A-Za-z][A-Za-z0-9 .()/'-]{1,80}?)(?=\\s*(?:\\(\\d{4,}\\)|is\\b|of\\b|$))"
    );
    private static final Pattern APPLICANT_PATTERN_3 = Pattern.compile(
            "(?i)(?:shri|smt|kum|mr|ms|mrs|patient)(?:\\s*/\\s*(?:shri|smt|kum|mr|ms|mrs|patient))+\\s+([A-Za-z][A-Za-z0-9 .()/'-]{1,80}?)(?=\\s*(?:\\(\\d{4,}\\)|is\\b|of\\b|$))"
    );
    private static final Pattern APPLICANT_OF_PATTERN = Pattern.compile(
            "(?i)(?:shri|smt|kum|mr|ms|mrs|patient)\\.?\\s+([A-Za-z][A-Za-z0-9 .()/'-]{1,80}?)(?=\\s*(?:\\(\\d{4,}\\))?\\s+of\\b)"
    );
    private static final Pattern APPLICANT_LABEL_PATTERN = Pattern.compile(
            "(?i)(?:name|applicant)\\s*[:.]*\\s*(.{0,80})$"
    );
    private static final Pattern RANGE_PATTERN = Pattern.compile(
            "(?i)(?:from)\\s+(.{1,30}?)\\s+(?:to)\\s+(.{1,30}?)\\b"
    );
    private static final Pattern LABELED_FROM_PATTERN = Pattern.compile(
            "(?i)(?:from|wef)\\s*[:.]*\\s*(.{0,30})$"
    );
    private static final Pattern LABELED_TO_PATTERN = Pattern.compile(
            "(?i)(?:to|till|upto)\\s*[:.]*\\s*(.{0,30})$"
    );
    private static final Pattern SIMPLE_FROM_PATTERN = Pattern.compile("(?i)^\\s*(.{0,30})$");
    private static final Pattern SIMPLE_TO_PATTERN = Pattern.compile("(?i)^\\s*(.{0,30})$");
    private static final Pattern DAYS_PATTERN = Pattern.compile(
            "(?i)(?:days?)\\s*[:.]*\\s*(\\d{1,3})\\s*(?:days?)?\\b"
    );
    private static final Pattern DAYS_WORD_PATTERN = Pattern.compile(
            "(?i)(?:days?)\\s*[:.]*\\s*([A-Za-z-]+)\\b"
    );
    private static final List<String> ORG_IGNORE_PHRASES = List.of(
            "government of india", "department of atomic energy", "medical certificate", "fitness certificate",
            "certificate", "doctor", "registration", "report no", "signature", "circular", "recruitment",
            "mandatory courses", "attention is invited", "consumption of mandatory courses"
    );
    private static final List<String> ORGANIZATION_KEYWORDS = List.of(
            "hospital", "health center", "health centre", "medical section", "medical unit", "clinic", "dispensary",
            "nuclear fuel complex"
    );
    private static final List<String> RANGE_KEYWORDS = List.of("absence", "rest", "from", "to", "duty", "leave");
    private static final List<String> LEAVE_PAGE_KEYWORDS = List.of(
            "recommended for leave", "communication of leave", "absence from duty", "with effect from", "advised rest"
    );
    private static final List<String> FITNESS_PAGE_KEYWORDS = List.of(
            "fitness to return to duty", "fit to resume duty", "fit to resume duties"
    );
    private static final Map<String, Integer> NUMBER_WORDS = new LinkedHashMap<>();

    static {
        NUMBER_WORDS.put("one", 1);
        NUMBER_WORDS.put("two", 2);
        NUMBER_WORDS.put("three", 3);
        NUMBER_WORDS.put("four", 4);
        NUMBER_WORDS.put("five", 5);
        NUMBER_WORDS.put("six", 6);
        NUMBER_WORDS.put("seven", 7);
        NUMBER_WORDS.put("eight", 8);
        NUMBER_WORDS.put("nine", 9);
        NUMBER_WORDS.put("ten", 10);
        NUMBER_WORDS.put("eleven", 11);
        NUMBER_WORDS.put("twelve", 12);
        NUMBER_WORDS.put("thirteen", 13);
        NUMBER_WORDS.put("fourteen", 14);
        NUMBER_WORDS.put("fifteen", 15);
        NUMBER_WORDS.put("sixteen", 16);
        NUMBER_WORDS.put("seventeen", 17);
        NUMBER_WORDS.put("eighteen", 18);
        NUMBER_WORDS.put("nineteen", 19);
        NUMBER_WORDS.put("twenty", 20);
        NUMBER_WORDS.put("twenty-one", 21);
        NUMBER_WORDS.put("twenty-two", 22);
        NUMBER_WORDS.put("twenty-three", 23);
        NUMBER_WORDS.put("twenty-four", 24);
        NUMBER_WORDS.put("twenty-five", 25);
        NUMBER_WORDS.put("twenty-six", 26);
        NUMBER_WORDS.put("twenty-seven", 27);
        NUMBER_WORDS.put("twenty-eight", 28);
        NUMBER_WORDS.put("twenty-nine", 29);
        NUMBER_WORDS.put("thirty", 30);
        NUMBER_WORDS.put("thirty-one", 31);
    }

    public MedicalLeaveData extract(MedicalOcrDocument document) {
        MedicalLeaveData data = new MedicalLeaveData();
        if (document == null || document.getPages().isEmpty()) {
            return data;
        }

        PageSelection selection = selectRelevantPages(document);
        MedicalLineIndexingService.Zones headerZones = MedicalLineIndexingService.indexLinesAndZones(selection.headerText());
        MedicalLineIndexingService.Zones primaryZones = MedicalLineIndexingService.indexLinesAndZones(selection.primaryText());
        MedicalLineIndexingService.Zones fallbackZones = MedicalLineIndexingService.indexLinesAndZones(selection.combinedRelevantText());

        data.setOrganizationName(firstNonBlank(
                extractOrganizationName(headerZones),
                extractOrganizationName(primaryZones),
                extractOrganizationName(fallbackZones)
        ));

        data.setApplicantName(firstNonBlank(
                extractApplicantName(primaryZones.allLines),
                extractApplicantName(fallbackZones.allLines)
        ));

        DateRange dateRange = firstNonNull(
                extractDateRange(primaryZones.allLines, selection.primaryText()),
                extractDateRange(fallbackZones.allLines, selection.combinedRelevantText())
        );
        data.setFromDate(dateRange == null ? null : dateRange.from());
        data.setToDate(dateRange == null ? null : dateRange.to());
        data.setTotalAbsentDays(firstNonBlank(
                extractAbsentDays(primaryZones.allLines, dateRange),
                extractAbsentDays(fallbackZones.allLines, dateRange)
        ));

        validate(data);
        return data;
    }

    private PageSelection selectRelevantPages(MedicalOcrDocument document) {
        List<MedicalOcrPage> relevantPages = new ArrayList<>();
        MedicalOcrPage primaryPage = null;
        MedicalOcrPage headerPage = null;
        int bestScore = Integer.MIN_VALUE;

        for (MedicalOcrPage page : document.getPages()) {
            String text = page.getText() == null ? "" : page.getText();
            int score = scoreMedicalPage(text);
            if (score > bestScore) {
                bestScore = score;
                primaryPage = page;
            }
            if (score > 0) {
                relevantPages.add(page);
                if (headerPage == null) {
                    headerPage = page;
                }
            }
        }

        if (primaryPage == null && !document.getPages().isEmpty()) {
            primaryPage = document.getPages().get(0);
        }
        if (headerPage == null) {
            headerPage = primaryPage;
        }
        if (relevantPages.isEmpty() && bestScore <= 0) {
            return new PageSelection("", "", "");
        }
        if (relevantPages.isEmpty() && primaryPage != null) {
            relevantPages = List.of(primaryPage);
        }

        String primaryText = primaryPage == null ? "" : safeText(primaryPage.getText());
        String headerText = headerPage == null ? primaryText : safeText(headerPage.getText());
        String combinedRelevantText = joinPageTexts(relevantPages);
        if (combinedRelevantText.isBlank()) {
            combinedRelevantText = safeText(document.getCombinedText());
        }
        return new PageSelection(primaryText, headerText, combinedRelevantText);
    }

    private int scoreMedicalPage(String text) {
        String lower = safeText(text).toLowerCase(Locale.ROOT);
        if (lower.isBlank()) {
            return Integer.MIN_VALUE;
        }
        int score = 0;
        score += lower.contains("medical certificate") ? 60 : 0;
        score += lower.contains("medical & fitness certificate") ? 90 : 0;
        score += lower.contains("medical section") ? 20 : 0;
        score += keywordHitCount(lower, ORGANIZATION_KEYWORDS) * 8;
        score += keywordHitCount(lower, LEAVE_PAGE_KEYWORDS) * 65;
        score += lower.contains("days with effect from") ? 35 : 0;
        score += lower.contains("suffering from") ? 25 : 0;
        score -= keywordHitCount(lower, FITNESS_PAGE_KEYWORDS) * 30;
        score -= lower.contains("circular") ? 180 : 0;
        score -= lower.contains("recruitment") ? 180 : 0;
        score -= lower.contains("mandatory courses") ? 180 : 0;
        score -= lower.contains("apar") ? 80 : 0;
        return score;
    }

    private String extractOrganizationName(MedicalLineIndexingService.Zones zones) {
        if (zones == null || zones.allLines.isEmpty()) {
            return null;
        }
        String best = null;
        int bestScore = Integer.MIN_VALUE;
        for (int i = 0; i < zones.allLines.size(); i++) {
            String candidate = cleanOrganizationCandidate(zones.allLines.get(i).getText());
            if (candidate == null) {
                continue;
            }
            String lower = candidate.toLowerCase(Locale.ROOT);
            int score = 0;
            score += looksUppercaseDominant(candidate) ? 80 : 0;
            score += keywordHitCount(lower, ORGANIZATION_KEYWORDS) * 30;
            score += lower.contains("nuclear fuel complex") ? 55 : 0;
            score += lower.contains("hospital") ? 45 : 0;
            score += i <= 2 ? 20 : 0;
            score += Math.max(0, 18 - (i * 2));
            score += Math.min(35, candidate.length());
            score -= lower.matches(".*\\(\\d{4,}\\).*") ? 25 : 0;
            score -= lower.contains("medical officer") ? 80 : 0;
            score -= containsNoiseForOrganization(lower) ? 80 : 0;
            if (score > bestScore && isValidOrganization(candidate)) {
                bestScore = score;
                best = candidate;
            }
        }
        return best;
    }

    private String extractApplicantName(List<MedicalLineIndexingService.IndexedLine> lines) {
        if (lines == null || lines.isEmpty()) {
            return null;
        }
        String best = null;
        int bestScore = Integer.MIN_VALUE;
        for (int i = 0; i < lines.size(); i++) {
            String text = lines.get(i).getText();
            Candidate labeled = extractLabeledApplicant(lines, i);
            Candidate c1 = extractNameByPattern(text, APPLICANT_PATTERN_1, 120, i);
            Candidate c2 = extractNameByPattern(text, APPLICANT_PATTERN_2, 110, i);
            Candidate c3 = extractNameByPattern(text, APPLICANT_PATTERN_3, 95, i);
            Candidate c4 = extractNameByPattern(text, APPLICANT_OF_PATTERN, 130, i);
            for (Candidate candidate : new Candidate[]{labeled, c1, c2, c3, c4}) {
                if (candidate == null) {
                    continue;
                }
                if (candidate.score() > bestScore && isValidApplicant(candidate.value())) {
                    bestScore = candidate.score();
                    best = candidate.value();
                }
            }
        }
        return best;
    }

    private Candidate extractLabeledApplicant(List<MedicalLineIndexingService.IndexedLine> lines, int index) {
        String text = lines.get(index).getText();
        Matcher matcher = APPLICANT_LABEL_PATTERN.matcher(text);
        if (!matcher.find()) {
            return null;
        }
        String candidate = cleanApplicantName(matcher.group(1));
        if (candidate == null) {
            for (int i = index + 1; i < lines.size() && i <= index + 2; i++) {
                if (isNameBoundaryLine(lines.get(i).getText().toLowerCase(Locale.ROOT))) {
                    break;
                }
                candidate = cleanApplicantName(lines.get(i).getText());
                if (candidate != null) {
                    break;
                }
            }
        }
        if (candidate == null) {
            return null;
        }
        return new Candidate(candidate, 150 + Math.max(0, 12 - index));
    }

    private DateRange extractDateRange(List<MedicalLineIndexingService.IndexedLine> lines, String combinedText) {
        if (lines == null || lines.isEmpty()) {
            return null;
        }
        DateRange best = null;
        int bestScore = Integer.MIN_VALUE;
        for (int i = 0; i < lines.size(); i++) {
            String text = correctHandwrittenDatesString(lines.get(i).getText());
            String lower = text.toLowerCase(Locale.ROOT);
            Matcher matcher = RANGE_PATTERN.matcher(text);
            while (matcher.find()) {
                String fromToken = MedicalRegexUtil.firstDateToken(matcher.group(1));
                String toToken = MedicalRegexUtil.firstDateToken(matcher.group(2));
                LocalDate from = MedicalRegexUtil.parseDate(fromToken);
                LocalDate to = MedicalRegexUtil.parseDate(toToken);
                if (from == null || to == null || to.isBefore(from)) {
                    continue;
                }
                int score = 100 + keywordHitCount(lower, RANGE_KEYWORDS) * 12;
                score += lower.contains("with effect from") ? 20 : 0;
                score += lower.contains("advised rest") ? 25 : 0;
                score -= containsDoctorNoise(lower) ? 40 : 0;
                score += Math.max(0, 18 - i);
                if (score > bestScore) {
                    bestScore = score;
                    best = new DateRange(
                            MedicalRegexUtil.normalizeDate(fromToken),
                            MedicalRegexUtil.normalizeDate(toToken)
                    );
                }
            }
        }
        if (best != null) {
            return best;
        }

        DateRange labeledRange = extractLabeledDateRange(lines);
        if (labeledRange != null) {
            return labeledRange;
        }

        String normalizedText = correctHandwrittenDatesString(safeText(combinedText.replace('\n', ' ')));
        Matcher matcher = RANGE_PATTERN.matcher(normalizedText);
        while (matcher.find()) {
            String fromToken = MedicalRegexUtil.firstDateToken(matcher.group(1));
            String toToken = MedicalRegexUtil.firstDateToken(matcher.group(2));
            LocalDate from = MedicalRegexUtil.parseDate(fromToken);
            LocalDate to = MedicalRegexUtil.parseDate(toToken);
            if (from != null && to != null && !to.isBefore(from)) {
                return new DateRange(
                        MedicalRegexUtil.normalizeDate(fromToken),
                        MedicalRegexUtil.normalizeDate(toToken)
                );
            }
        }

        List<String> fallbackTokens = extractDateTokens(normalizedText);
        if (fallbackTokens.size() >= 2 && normalizedText.toLowerCase(Locale.ROOT).matches(".*\\b(?:absence|rest|from|to|leave)\\b.*")) {
            LocalDate from = MedicalRegexUtil.parseDate(fallbackTokens.get(0));
            LocalDate to = MedicalRegexUtil.parseDate(fallbackTokens.get(1));
            if (from != null && to != null && !to.isBefore(from)) {
                return new DateRange(
                        MedicalRegexUtil.normalizeDate(fallbackTokens.get(0)),
                        MedicalRegexUtil.normalizeDate(fallbackTokens.get(1))
                );
            }
        }
        return null;
    }

    private DateRange extractLabeledDateRange(List<MedicalLineIndexingService.IndexedLine> lines) {
        DateTokenCandidate from = extractLabeledDate(lines, true);
        DateTokenCandidate to = extractLabeledDate(lines, false);
        if (from == null || to == null) {
            return null;
        }
        LocalDate fromDate = MedicalRegexUtil.parseDate(from.token());
        LocalDate toDate = MedicalRegexUtil.parseDate(to.token());
        if (fromDate == null || toDate == null || toDate.isBefore(fromDate)) {
            return null;
        }
        return new DateRange(
                MedicalRegexUtil.normalizeDate(from.token()),
                MedicalRegexUtil.normalizeDate(to.token())
        );
    }

    private DateTokenCandidate extractLabeledDate(List<MedicalLineIndexingService.IndexedLine> lines, boolean fromDate) {
        Pattern primaryPattern = fromDate ? LABELED_FROM_PATTERN : LABELED_TO_PATTERN;
        Pattern fallbackPattern = fromDate ? SIMPLE_FROM_PATTERN : SIMPLE_TO_PATTERN;
        DateTokenCandidate best = null;
        for (int i = 0; i < lines.size(); i++) {
            String text = correctHandwrittenDatesString(lines.get(i).getText());
            String lower = text.toLowerCase(Locale.ROOT);
            String token = extractDateFromPattern(text, primaryPattern);
            int score = 0;
            if (token != null) {
                score = 110 + Math.max(0, 18 - i);
            } else if (looksLikeSimpleDateLabel(lower, fromDate)) {
                token = extractDateFromPattern(text, fallbackPattern);
                if (token == null) {
                    token = extractDateFromFollowingLines(lines, i);
                }
                score = token == null ? 0 : 85 + Math.max(0, 15 - i);
            }
            if (token == null) {
                continue;
            }
            if (best == null || score > best.score()) {
                best = new DateTokenCandidate(token, score);
            }
        }
        return best;
    }

    private String extractAbsentDays(List<MedicalLineIndexingService.IndexedLine> lines, DateRange range) {
        if (lines == null || lines.isEmpty()) {
            return range == null ? null : calculateDaysFromRange(range);
        }
        String daysFromRange = calculateDaysFromRange(range);
        Integer computedDays = parseInteger(daysFromRange);
        Integer best = null;
        int bestScore = Integer.MIN_VALUE;

        for (int i = 0; i < lines.size(); i++) {
            String text = lines.get(i).getText();
            String lower = text.toLowerCase(Locale.ROOT);
            Integer candidate = extractDaysCandidate(text);
            if (candidate == null || candidate <= 0 || candidate > 365) {
                continue;
            }
            int score = 90;
            score += lower.contains("absence") || lower.contains("rest") ? 25 : 0;
            score += lower.contains("days") ? 10 : 0;
            score += lower.contains("with effect from") ? 12 : 0;
            score -= containsDoctorNoise(lower) ? 25 : 0;
            if (computedDays != null) {
                if (candidate.equals(computedDays)) {
                    score += 40;
                } else if (Math.abs(candidate - computedDays) <= 1) {
                    score += 15;
                } else {
                    score -= 45;
                }
            }
            if (score > bestScore) {
                bestScore = score;
                best = candidate;
            }
        }

        if (best == null) {
            return daysFromRange;
        }
        if (computedDays != null && Math.abs(best - computedDays) > 1) {
            return daysFromRange;
        }
        return String.valueOf(best);
    }

    private Candidate extractNameByPattern(String text, Pattern pattern, int baseScore, int lineNumber) {
        if (text == null || text.isBlank()) {
            return null;
        }
        Matcher matcher = pattern.matcher(text);
        if (!matcher.find()) {
            return null;
        }
        String candidate = cleanApplicantName(matcher.group(1));
        if (candidate == null) {
            return null;
        }
        int score = baseScore + Math.max(0, 15 - lineNumber);
        return new Candidate(candidate, score);
    }

    private String cleanApplicantName(String candidate) {
        if (candidate == null) {
            return null;
        }
        
        String cleaned = RegexUtil.normalizeLine(candidate)
                .replaceAll("(?i)\\(\\d{4,}\\)", " ")
                .replaceAll("(?i)\\bof\\b.*$", "")
                .replaceAll("(?i)\\bis\\s+suffering.*$", "")
                .replaceAll("(?i)\\baged\\b.*$", "")
                .replaceAll("[^A-Za-z .'-]", " ")
                .replaceAll("\\s+", " ")
                .trim();
        
        // Normalize handwritten digits back to visual letter equivalents (after removing numbers)
        String normalized = cleaned.replaceAll("4", "a")
                                     .replaceAll("0", "o")
                                     .replaceAll("1", "i")
                                     .replaceAll("3", "e")
                                     .replaceAll("5", "s")
                                     .replaceAll("8", "b");

        String previous;
        do {
            previous = normalized;
            normalized = normalized.replaceFirst("(?i)^(?:(?:mr|ms|mrs|shri|smt|kum|patient|pt)\\.?\\s*/\\s*)*(?:mr|ms|mrs|shri|smt|kum|patient|pt)\\.?\\s+", "").trim();
        } while (!normalized.equals(previous));
        normalized = normalized.replaceAll("\\b([A-Za-z])\\s+([A-Za-z])\\b", "$1.$2").trim();
        if (normalized.length() < 3) {
            return null;
        }
        return normalized;
    }

    private String correctHandwrittenDatesString(String text) {
        if (text == null) return null;
        String corrected = text;
            corrected = corrected.replaceAll("(?<=[\\d./-])I|I(?=[\\d./-])", "1");
            corrected = corrected.replaceAll("(?<=[\\d./-])l|l(?=[\\d./-])", "1");
            corrected = corrected.replaceAll("(?<=[\\d./-])O|O(?=[\\d./-])", "0");
            corrected = corrected.replaceAll("(?<=[\\d./-])o|o(?=[\\d./-])", "0");
        // 7 <-> / inside digits to fix missing slashes
        corrected = corrected.replaceAll("(\\d{1,2})[7T](\\d{1,2})[7T](\\d{2,4})", "$1/$2/$3");
        return corrected;
    }

    private String cleanOrganizationCandidate(String value) {
        String cleaned = RegexUtil.normalizeLine(value)
            .replaceAll("(?i)\\bfor\\b.*$", "")
            .replaceAll("(?i)\\bfitness\\b.*$", "")
            .replaceAll("(?i)\\(\\d{4,}\\)", "")
            .replaceAll("(?i)\\bhospita\\b", "HOSPITAL")
            .trim();
        if (cleaned.isBlank()) {
            return null;
        }
        String lower = cleaned.toLowerCase(Locale.ROOT);
        if (lower.startsWith("this is to certify")
                || lower.startsWith("i, dr")
                || lower.startsWith("period of absence")
                || lower.startsWith("absence from duty")
                || lower.startsWith("from ")
                || lower.startsWith("mandatory courses")
                || lower.startsWith("attention is invited")
                || lower.startsWith("she / he")) {
            return null;
        }
        if (MedicalRegexUtil.firstDateToken(cleaned) != null) {
            return null;
        }
        return containsIgnoredOrganizationText(lower) ? null : cleaned;
    }

    private boolean looksUppercaseDominant(String text) {
        int uppercase = 0;
        int letters = 0;
        for (char ch : text.toCharArray()) {
            if (Character.isLetter(ch)) {
                letters++;
                if (Character.isUpperCase(ch)) {
                    uppercase++;
                }
            }
        }
        return letters >= 6 && uppercase >= Math.ceil(letters * 0.75);
    }

    private boolean containsIgnoredOrganizationText(String lower) {
        for (String phrase : ORG_IGNORE_PHRASES) {
            if (lower.contains(phrase)) {
                return true;
            }
        }
        return false;
    }

    private boolean containsNoiseForOrganization(String lower) {
        return lower.contains("doctor")
                || lower.contains("signature")
                || lower.contains("reg")
                || lower.contains("circular")
                || lower.contains("recruitment");
    }

    private boolean containsDoctorNoise(String lower) {
        return lower.contains("dr")
                || lower.contains("doctor")
                || lower.contains("signature")
                || lower.contains("registration")
                || lower.contains("stamp")
                || lower.contains("medical officer");
    }

    private int keywordHitCount(String text, List<String> keywords) {
        int count = 0;
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                count++;
            }
        }
        return count;
    }

    private List<String> extractDateTokens(String text) {
        List<String> tokens = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return tokens;
        }
        Matcher matcher = MedicalRegexUtil.DATE_TOKEN_PATTERN.matcher(text);
        while (matcher.find()) {
            String token = matcher.group();
            if (MedicalRegexUtil.parseDate(token) != null) {
                tokens.add(token);
            }
        }
        return tokens;
    }

    private String extractDateFromPattern(String text, Pattern pattern) {
        Matcher matcher = pattern.matcher(text);
        if (!matcher.find()) {
            return null;
        }
        return MedicalRegexUtil.firstDateToken(matcher.group(1));
    }

    private String extractDateFromFollowingLines(List<MedicalLineIndexingService.IndexedLine> lines, int index) {
        for (int i = index + 1; i < lines.size() && i <= index + 2; i++) {
            String lower = lines.get(i).getText().toLowerCase(Locale.ROOT);
            if (containsDoctorNoise(lower) || isNameBoundaryLine(lower)) {
                break;
            }
            String token = MedicalRegexUtil.firstDateToken(lines.get(i).getText());
            if (token != null) {
                return token;
            }
        }
        return null;
    }

    private boolean looksLikeSimpleDateLabel(String lower, boolean fromDate) {
        if (fromDate) {
            return lower.matches("(?i)^\\s*(?:from|from date)\\b.*") && (lower.contains("date") || lower.length() <= 20);
        }
        return lower.matches("(?i)^\\s*(?:to|to date|till|upto|up to)\\b.*") && (lower.contains("date") || lower.length() <= 20);
    }

    private boolean isNameBoundaryLine(String lower) {
        return lower.contains("from")
                || lower.contains("to")
                || lower.contains("rest")
                || lower.contains("absence")
                || lower.contains("doctor")
                || lower.contains("medical officer")
                || lower.contains("date")
                || lower.contains("signature");
    }

    private Integer extractDaysCandidate(String text) {
        Matcher numericMatcher = DAYS_PATTERN.matcher(text);
        if (numericMatcher.find()) {
            return parseInteger(numericMatcher.group(1));
        }
        Matcher wordMatcher = DAYS_WORD_PATTERN.matcher(text);
        if (wordMatcher.find()) {
            return parseDayWord(wordMatcher.group(1));
        }
        return null;
    }

    private Integer parseDayWord(String token) {
        if (token == null) {
            return null;
        }
        String normalized = token.toLowerCase(Locale.ROOT).replaceAll("[^a-z-]", "");
        return NUMBER_WORDS.get(normalized);
    }

    private String calculateDaysFromRange(DateRange range) {
        if (range == null) {
            return null;
        }
        LocalDate from = MedicalRegexUtil.parseDate(range.from());
        LocalDate to = MedicalRegexUtil.parseDate(range.to());
        if (from == null || to == null || to.isBefore(from)) {
            return null;
        }
        return String.valueOf(Math.toIntExact(ChronoUnit.DAYS.between(from, to)) + 1);
    }

    private Integer parseInteger(String value) {
        try {
            return value == null ? null : Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private boolean isValidOrganization(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        String lower = value.toLowerCase(Locale.ROOT);
        if (lower.matches("^\\d+$")
                || lower.contains("circular")
                || lower.contains("recruitment")
                || lower.contains("mandatory courses")
                || lower.startsWith("this is to certify")) {
            return false;
        }
        int letters = 0;
        for (char ch : value.toCharArray()) {
            if (Character.isLetter(ch)) {
                letters++;
            }
        }
        return letters >= 4;
    }

    private boolean isValidApplicant(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        String lower = value.toLowerCase(Locale.ROOT);
        if (lower.contains("government") || lower.contains("department") || lower.contains("doctor")) {
            return false;
        }
        if (!value.matches("^[A-Za-z .'-]{3,80}$")) {
            return false;
        }
        int letters = 0;
        for (char ch : value.toCharArray()) {
            if (Character.isLetter(ch)) {
                letters++;
            }
        }
        return letters >= 3;
    }

    private void validate(MedicalLeaveData data) {
        if (!isValidOrganization(data.getOrganizationName())) {
            data.setOrganizationName(null);
        }
        if (!isValidApplicant(data.getApplicantName())) {
            data.setApplicantName(null);
        }

        LocalDate from = MedicalRegexUtil.parseDate(data.getFromDate());
        LocalDate to = MedicalRegexUtil.parseDate(data.getToDate());
        if (from == null || to == null || to.isBefore(from)) {
            data.setFromDate(null);
            data.setToDate(null);
        } else {
            data.setFromDate(MedicalRegexUtil.normalizeDate(data.getFromDate()));
            data.setToDate(MedicalRegexUtil.normalizeDate(data.getToDate()));
            String expectedDays = String.valueOf(Math.toIntExact(ChronoUnit.DAYS.between(from, to)) + 1);
            Integer statedDays = parseInteger(data.getTotalAbsentDays());
            if (statedDays == null || Math.abs(statedDays - Integer.parseInt(expectedDays)) > 1) {
                data.setTotalAbsentDays(expectedDays);
            }
        }

        if (data.getTotalAbsentDays() != null && !data.getTotalAbsentDays().matches("^\\d{1,3}$")) {
            data.setTotalAbsentDays(null);
        }
    }

    private String joinPageTexts(List<MedicalOcrPage> pages) {
        StringBuilder builder = new StringBuilder();
        if (pages == null) {
            return "";
        }
        for (MedicalOcrPage page : pages) {
            String text = safeText(page.getText());
            if (text.isBlank()) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append("\n");
            }
            builder.append(text);
        }
        return builder.toString();
    }

    private String safeText(String text) {
        return text == null ? "" : text
        .replace('\u0000', ' ')
        .trim();
    }

    @SafeVarargs
    private final <T> T firstNonNull(T... values) {
        if (values == null) {
            return null;
        }
        for (T value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private record Candidate(String value, int score) {
    }

    private record DateRange(String from, String to) {
    }

    private record DateTokenCandidate(String token, int score) {
    }

    private record PageSelection(String primaryText, String headerText, String combinedRelevantText) {
    }
}
