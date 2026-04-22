package com.invoice.extractor.extractor;

import com.invoice.extractor.service.impl.LineIndexingService;
import com.invoice.extractor.util.DateUtil;
import com.invoice.extractor.util.OcrLayoutUtil;
import com.invoice.extractor.util.RegexUtil;
import java.util.List;
import java.util.Locale;

public class VendorExtractor implements FieldExtractor<String> {
    private static final List<String> COMPANY_KEYWORDS = List.of(
            "ltd", "limited", "pvt", "private", "llp", "corporation", "industries",
            "enterprises", "solutions", "engineering", "chemicals", "courier", "services",
            "electronics", "hydraulics", "products", "bearing", "technosoft", "energy", "systems",
            "company", "agency", "traders", "brothers", "associates", "logistics", "co"
    );
    private static final List<String> TRIMMABLE_SUFFIX_KEYWORDS = List.of(
            "ltd", "limited", "pvt", "private", "llp", "corporation", "industries",
            "enterprises", "solutions", "systems", "company", "agency", "traders", "associates", "co"
    );
    private static final List<String> VENDOR_LABEL_KEYWORDS = List.of("vendor", "supplier", "seller", "from");
    private static final int MIN_STRICT_SCORE = 90;
    private static final int MIN_RELAXED_SCORE = 60;

    @Override
    public String extract(String[] lines, int[] zones) {
        return null;
    }

    public String extract(LineIndexingService.Zones zones, String vendorGstin) {
        return extractResult(zones, vendorGstin).getValue();
    }

    public FieldExtractionResult<String> extractResult(LineIndexingService.Zones zones, String vendorGstin) {
        List<LineIndexingService.IndexedLine> headerLines = headerWindow(zones);
        VendorCandidate strictCandidate = findBestScoredCandidate(headerLines, vendorGstin, true);
        VendorCandidate nearGstin = findNearGstinCandidate(headerLines, vendorGstin);
        VendorCandidate relaxedCandidate = findBestScoredCandidate(headerLines, vendorGstin, false);
        VendorCandidate uppercaseFallback = findUppercaseFallback(headerLines);
        VendorCandidate voucherPayee = findVoucherPayeeCandidate(zones.allLines);

        VendorCandidate best = null;
        if (strictCandidate != null && strictCandidate.score >= MIN_STRICT_SCORE) {
            best = strictCandidate;
        }
        if (nearGstin != null && nearGstin.score >= MIN_RELAXED_SCORE && isBetterCandidate(nearGstin, best)) {
            best = nearGstin;
        }
        if (relaxedCandidate != null && relaxedCandidate.score >= MIN_RELAXED_SCORE && isBetterCandidate(relaxedCandidate, best)) {
            best = relaxedCandidate;
        }
        if (uppercaseFallback != null && uppercaseFallback.score >= MIN_RELAXED_SCORE && isBetterCandidate(uppercaseFallback, best)) {
            best = uppercaseFallback;
        }
        if (voucherPayee != null && isBetterCandidate(voucherPayee, best)) {
            best = voucherPayee;
        }

        return best == null ? new FieldExtractionResult<>(null, "fallback", null) : best.toResult();
    }

    private VendorCandidate findBestScoredCandidate(List<LineIndexingService.IndexedLine> lines,
                                                    String vendorGstin,
                                                    boolean strict) {
        VendorCandidate best = null;
        for (LineIndexingService.IndexedLine line : lines) {
            for (String fragment : OcrLayoutUtil.fragments(line.getText())) {
                VendorCandidate candidate = buildScoredCandidate(fragment, line, vendorGstin, strict, false);
                if (candidate != null && (best == null || candidate.score > best.score)) {
                    best = candidate;
                }
            }
        }
        return best;
    }

    private VendorCandidate findNearGstinCandidate(List<LineIndexingService.IndexedLine> lines, String vendorGstin) {
        if (!RegexUtil.isValidGstin(vendorGstin)) {
            return null;
        }
        String normalizedGstin = vendorGstin.replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
        int gstinIndex = -1;
        LineIndexingService.IndexedLine gstinLine = null;
        for (int i = 0; i < lines.size(); i++) {
            String compact = lines.get(i).getOriginalText().replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
            if (compact.contains(normalizedGstin)) {
                gstinIndex = i;
                gstinLine = lines.get(i);
                break;
            }
        }
        if (gstinIndex < 0) {
            return null;
        }

        VendorCandidate best = null;
        for (int i = Math.max(0, gstinIndex - 6); i <= Math.min(lines.size() - 1, gstinIndex + 1); i++) {
            if (i == gstinIndex) {
                continue;
            }
            if (gstinLine != null && !sameColumn(gstinLine, lines.get(i))) {
                continue;
            }
            String lower = lines.get(i).getText().toLowerCase(Locale.ROOT);
            if (OcrLayoutUtil.isBuyerSectionHeader(lower)) {
                continue;
            }
            for (String fragment : OcrLayoutUtil.fragments(lines.get(i).getText())) {
                VendorCandidate candidate = buildScoredCandidate(fragment, lines.get(i), vendorGstin, false, true);
                if (candidate != null) {
                    candidate.score += Math.max(0, 35 - Math.abs(gstinIndex - i) * 8);
                    candidate.method = candidate.score >= MIN_STRICT_SCORE ? "keyword" : "regex";
                    if (best == null || candidate.score > best.score) {
                        best = candidate;
                    }
                }
            }
        }
        return best;
    }

    private VendorCandidate findUppercaseFallback(List<LineIndexingService.IndexedLine> lines) {
        VendorCandidate best = null;
        for (int i = 0; i < Math.min(lines.size(), 8); i++) {
            for (String fragment : OcrLayoutUtil.fragments(lines.get(i).getText())) {
                String candidateText = sanitizeCandidate(fragment);
                if (!isFallbackFriendly(candidateText)) {
                    continue;
                }
                VendorCandidate candidate = new VendorCandidate(candidateText, "fallback", lines.get(i).getLineNumber());
                candidate.score = 58 + Math.max(0, 16 - i * 2);
                if (OcrLayoutUtil.looksLikeMeaningfulUppercaseLine(candidateText)) {
                    candidate.score += 18;
                }
                if (containsCompanyKeyword(candidateText)) {
                    candidate.score += 16;
                    candidate.method = "keyword";
                }
                if (best == null || candidate.score > best.score) {
                    best = candidate;
                }
            }
        }
        return best;
    }

    private VendorCandidate findVoucherPayeeCandidate(List<LineIndexingService.IndexedLine> lines) {
        if (lines == null) {
            return null;
        }
        for (LineIndexingService.IndexedLine line : lines) {
            String text = RegexUtil.normalizeLine(line.getText());
            if (!text.toLowerCase(Locale.ROOT).startsWith("to ")) {
                continue;
            }
            String candidateText = sanitizeCandidate(text.substring(3));
            if (!isValidScoredCandidate(candidateText, false)) {
                continue;
            }
            VendorCandidate candidate = new VendorCandidate(candidateText, "fallback", line.getLineNumber());
            candidate.score = 88;
            return candidate;
        }
        return null;
    }

    private VendorCandidate buildScoredCandidate(String rawValue,
                                                 LineIndexingService.IndexedLine line,
                                                 String vendorGstin,
                                                 boolean strict,
                                                 boolean proximityMode) {
        String candidateText = sanitizeCandidate(rawValue);
        if (!isValidScoredCandidate(candidateText, strict)) {
            return null;
        }

        int score = scoreScoredCandidate(candidateText, line, vendorGstin, strict, proximityMode);
        if (strict && score < MIN_STRICT_SCORE - 15) {
            return null;
        }
        if (!strict && score < MIN_RELAXED_SCORE - 12) {
            return null;
        }

        VendorCandidate candidate = new VendorCandidate(
                candidateText,
                score >= MIN_STRICT_SCORE || containsCompanyKeyword(candidateText) ? "keyword" : "regex",
                line.getLineNumber()
        );
        candidate.score = score;
        return candidate;
    }

    private boolean isValidScoredCandidate(String text, boolean strict) {
        if (text == null || text.isBlank() || text.length() < 5 || text.length() > 120) {
            return false;
        }
        if (!text.matches(".*[A-Za-z].*") || text.matches("^\\d+$")) {
            return false;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        if (OcrLayoutUtil.isHeaderNoise(lower)
                || OcrLayoutUtil.isVoucherLike(lower)
                || OcrLayoutUtil.isGovernmentLike(lower)
                || OcrLayoutUtil.isLogisticsLike(lower)
                || lower.matches("^\\(?page\\s*\\d+\\)?$")
                || lower.contains("page ")
                || lower.contains("gstin")
                || lower.contains("buyer")
                || lower.contains("bill to")
                || lower.contains("ship to")
                || lower.contains("consignee")) {
            return false;
        }
        if (text.trim().endsWith(":")) {
            return false;
        }
        if (looksLikeAddressFragment(lower, text)) {
            return false;
        }
        if (!containsYearSuffix(text) && !DateUtil.findCandidateDates(text).isEmpty()) {
            return false;
        }
        int digits = digitCount(text);
        int letters = letterCount(text);
        if (digits > Math.max(2, letters / 3) && !containsCompanyKeyword(text) && !containsYearSuffix(text)) {
            return false;
        }
        if (text.length() < 8 && !containsCompanyKeyword(text)) {
            return false;
        }
        if (alphaWordCount(text) < 2 && !containsCompanyKeyword(text) && !OcrLayoutUtil.looksLikeMeaningfulUppercaseLine(text)) {
            return false;
        }
        if (longestAlphaWord(text) < 4 && !containsCompanyKeyword(text)) {
            return false;
        }
        if (!hasMeaningfulWords(text, strict ? 2 : 1)) {
            return false;
        }
        return containsCompanyKeyword(text) || OcrLayoutUtil.looksLikeMeaningfulUppercaseLine(text) || !strict;
    }

    private int scoreScoredCandidate(String text,
                                     LineIndexingService.IndexedLine line,
                                     String vendorGstin,
                                     boolean strict,
                                     boolean proximityMode) {
        String lower = text.toLowerCase(Locale.ROOT);
        int score = 38;
        score += Math.max(0, 44 - line.getLineNumber() * 2);
        score += containsCompanyKeyword(text) ? 34 : 0;
        score += OcrLayoutUtil.hasBusinessSignal(lower) ? 18 : 0;
        score += OcrLayoutUtil.looksLikeMeaningfulUppercaseLine(text) ? 16 : 0;
        score += RegexUtil.containsAnyKeyword(lower, VENDOR_LABEL_KEYWORDS) ? 18 : 0;
        score += lower.startsWith("m/s") ? 8 : 0;
        score += line.getColumn() == LineIndexingService.Column.LEFT_COLUMN ? 10 : 0;
        score += line.getColumn() == LineIndexingService.Column.RIGHT_COLUMN && !proximityMode ? -16 : 0;
        if (RegexUtil.isValidGstin(vendorGstin)
                && line.getOriginalText().replaceAll("\\s+", "").toUpperCase(Locale.ROOT).contains(vendorGstin.toUpperCase(Locale.ROOT))) {
            score += 24;
        }
        if (proximityMode) {
            score += 12;
        }
        if (containsYearSuffix(text)) {
            score += 6;
        }
        if (text.contains(",")) {
            score -= 8;
        }
        if (text.length() > 70) {
            score -= 18;
        } else if (text.length() > 45) {
            score -= 8;
        }
        if (looksLikeAddressFragment(lower, text)) {
            score -= 50;
        }
        if (OcrLayoutUtil.isGovernmentLike(lower)) {
            score -= 105;
        }
        if (OcrLayoutUtil.isHeaderNoise(lower) || OcrLayoutUtil.isVoucherLike(lower)) {
            score -= 95;
        }
        if (OcrLayoutUtil.isLogisticsLike(lower)) {
            score -= 90;
        }
        if (!containsYearSuffix(text) && !DateUtil.findCandidateDates(text).isEmpty()) {
            score -= 85;
        }
        if (!containsCompanyKeyword(text) && digitCount(text) > 0) {
            score -= 26;
        }
        score += alphaWordCount(text) >= 2 ? 8 : -24;
        score += longestAlphaWord(text) >= 6 ? 10 : -12;
        if (strict && !containsCompanyKeyword(text) && !OcrLayoutUtil.looksLikeMeaningfulUppercaseLine(text)) {
            score -= 28;
        }
        return score;
    }

    private boolean isFallbackFriendly(String text) {
        if (!isValidScoredCandidate(text, false)) {
            return false;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        return !looksLikeAddressFragment(lower, text)
                && !OcrLayoutUtil.isGovernmentLike(lower)
                && !OcrLayoutUtil.isHeaderNoise(lower);
    }

    private boolean containsCompanyKeyword(String txt) {
        return RegexUtil.containsAnyKeyword(txt.toLowerCase(Locale.ROOT), COMPANY_KEYWORDS);
    }

    private String sanitizeCandidate(String value) {
        String normalized = RegexUtil.normalizeLine(value);
        normalized = normalized.replaceAll("^[^A-Za-z0-9]+", "").trim();
        normalized = normalized.replaceFirst("(?i)^\\(?\\s*(?:tax\\s+invoice|invoice|e-?invoice|voucher)\\s*\\)?\\s*", "");
        normalized = normalized.replaceAll("(?i)^(?:original|duplicate|triplicate|extra)\\s+for\\s+[A-Za-z/ ]+", "").trim();
        normalized = normalized.replaceFirst("(?i)^.*?\\b(?:m/s\\.?|seller|supplier|vendor|from|for)\\b\\s*[:#-]*\\s*", "");
        normalized = normalized.replaceFirst("^[a-z]{2,16}\\s+(?=[A-Z][A-Za-z&().-]+\\s+[A-Z].*\\b(?:ltd|limited|pvt|private|llp|industries|corporation|engineering|electronics|systems|solutions|chemicals)\\b)", "");
        normalized = OcrLayoutUtil.truncateAtKeyword(normalized, OcrLayoutUtil.HEADER_METADATA_KEYWORDS);
        normalized = OcrLayoutUtil.truncateAtKeyword(normalized, OcrLayoutUtil.BUYER_STOP_KEYWORDS);
        normalized = normalized.replaceAll("(?i)^tax invoice\\s*", "").trim();
        normalized = normalized.replaceAll("(?i)^(?:tin\\s*no|gst\\s*no|pan\\s*no|cst)\\s*[:#-]*\\s*", "").trim();
        normalized = normalized.replaceFirst("(?i)\\b(?:gstin|gstin/uin|gst no|tin no|pan no)\\b.*$", "").trim();
        normalized = normalized.replaceFirst("(?i)\\b(?:invoice\\s*no|invoice\\s*number|dated|date|ref\\.?\\s*no|order\\s*no)\\b.*$", "").trim();
        normalized = normalized.replaceFirst("(?i)^\\(?page\\s*\\d+\\)?$", "").trim();
        normalized = normalized.replaceFirst("\\s*[-:|]+\\s*$", "").trim();
        String lower = normalized.toLowerCase(Locale.ROOT);
        int suffixIndex = lastCompanySuffixIndex(lower);
        if (suffixIndex >= 0) {
            String tail = normalized.substring(suffixIndex);
            if (!tail.matches("(?i).*(?:\\(\\d{4}[-/]\\d{2,4}\\)|\\b(?:ltd|limited|pvt|private|llp|industries|corporation|systems|solutions|company|agency|traders|associates|co)\\b).*")) {
                normalized = normalized.substring(0, suffixIndex).trim();
            } else if (normalized.matches(".*\\b(?:ltd|limited|pvt|llp)\\b\\s+[A-Za-z]{2,20}.*")) {
                normalized = normalized.replaceFirst("(?i)(\\b(?:ltd|limited|llp)\\b).*$", "$1").trim();
            }
        }
        normalized = normalized.replaceFirst("\\s*[-–]\\s*\\(\\d{4}[-/]\\d{2,4}\\)\\s*$", "").trim();
        // Also strip year suffix without preceding dash: "Ranco Industries (2022-2023)"
        normalized = normalized.replaceFirst("\\s*\\(\\d{4}[-/]\\d{2,4}\\)\\s*$", "").trim();
        normalized = normalized.replaceAll("\\s{2,}", " ").trim();
        return normalized.replaceAll("\\s+[a-z]{1,3}$", "").trim();
    }

    private boolean hasMeaningfulWords(String text, int minimumWords) {
        int meaningfulWords = 0;
        for (String word : text.split("\\s+")) {
            String alpha = word.replaceAll("[^A-Za-z]", "");
            if (alpha.length() >= 3) {
                meaningfulWords++;
            }
        }
        return meaningfulWords >= minimumWords;
    }

    private int alphaWordCount(String text) {
        int count = 0;
        for (String word : text.split("\\s+")) {
            if (word.replaceAll("[^A-Za-z]", "").length() >= 2) {
                count++;
            }
        }
        return count;
    }

    private int longestAlphaWord(String text) {
        int longest = 0;
        for (String word : text.split("\\s+")) {
            longest = Math.max(longest, word.replaceAll("[^A-Za-z]", "").length());
        }
        return longest;
    }

    private boolean containsYearSuffix(String text) {
        return text != null && text.matches(".*\\(\\d{4}[-/]\\d{2,4}\\).*");
    }

    private int digitCount(String value) {
        int digits = 0;
        for (char ch : value.toCharArray()) {
            if (Character.isDigit(ch)) {
                digits++;
            }
        }
        return digits;
    }

    private int letterCount(String value) {
        int letters = 0;
        for (char ch : value.toCharArray()) {
            if (Character.isLetter(ch)) {
                letters++;
            }
        }
        return letters;
    }

    private int lastCompanySuffixIndex(String lower) {
        int best = -1;
        for (String keyword : TRIMMABLE_SUFFIX_KEYWORDS) {
            int index = lower.lastIndexOf(keyword);
            if (index > best) {
                best = index;
            }
        }
        return best;
    }

    private List<LineIndexingService.IndexedLine> headerWindow(LineIndexingService.Zones zones) {
        List<LineIndexingService.IndexedLine> header = new java.util.ArrayList<>();
        int headerEnd = zones.getHeaderEndLine() > 0 ? zones.getHeaderEndLine() : 25;
        int cutoff = zones.getTableHeaderLine() == null ? Integer.MAX_VALUE : zones.getTableHeaderLine().getLineNumber();
        for (LineIndexingService.IndexedLine line : zones.allLines) {
            String lower = line.getText().toLowerCase(Locale.ROOT);
            if (line.getLineNumber() > headerEnd || line.getLineNumber() >= cutoff) {
                break;
            }
            if (line.getLineNumber() > 1 && OcrLayoutUtil.isBuyerSectionHeader(lower)) {
                break;
            }
            header.add(line);
        }
        return header.isEmpty() ? zones.topZone : header;
    }

    private boolean looksLikeAddressFragment(String lower, String text) {
        int addressHits = 0;
        for (String keyword : OcrLayoutUtil.ADDRESS_KEYWORDS) {
            if (lower.contains(keyword)) {
                addressHits++;
            }
        }
        if (text.matches(".*\\b\\d{5,6}\\b.*")) {
            return true;
        }
        if (addressHits >= 2) {
            return true;
        }
        return addressHits >= 1 && (text.contains(",") || text.matches(".*\\d.*"));
    }

    private boolean sameColumn(LineIndexingService.IndexedLine anchor, LineIndexingService.IndexedLine candidate) {
        if (anchor == null || candidate == null) {
            return false;
        }
        return anchor.getColumn() == LineIndexingService.Column.FULL_WIDTH
                || candidate.getColumn() == LineIndexingService.Column.FULL_WIDTH
                || anchor.getColumn() == candidate.getColumn();
    }

    private boolean isBetterCandidate(VendorCandidate candidate, VendorCandidate currentBest) {
        if (candidate == null) {
            return false;
        }
        if (currentBest == null) {
            return true;
        }
        if (candidate.score != currentBest.score) {
            return candidate.score > currentBest.score;
        }
        boolean candidateCompany = containsCompanyKeyword(candidate.value);
        boolean bestCompany = containsCompanyKeyword(currentBest.value);
        if (candidateCompany != bestCompany) {
            return candidateCompany;
        }
        return candidate.value.length() > currentBest.value.length();
    }

    private static class VendorCandidate {
        private final String value;
        private String method;
        private final Integer lineNumber;
        private int score;

        private VendorCandidate(String value, String method, Integer lineNumber) {
            this.value = value;
            this.method = method;
            this.lineNumber = lineNumber;
        }

        private FieldExtractionResult<String> toResult() {
            return new FieldExtractionResult<>(value, method, lineNumber);
        }
    }
}
