package com.invoice.extractor.extractor;

import com.invoice.extractor.service.impl.LineIndexingService;
import com.invoice.extractor.util.OcrLayoutUtil;
import com.invoice.extractor.util.RegexUtil;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;

public class GstinExtractor implements FieldExtractor<String[]> {
    public static class Result {
        private final String vendorGstin;
        private final String buyerGstin;
        private final String vendorMethod;
        private final String buyerMethod;
        private final Integer vendorLineNumber;
        private final Integer buyerLineNumber;

        public Result(String vendorGstin, String buyerGstin, String vendorMethod, String buyerMethod, Integer vendorLineNumber, Integer buyerLineNumber) {
            this.vendorGstin = vendorGstin;
            this.buyerGstin = buyerGstin;
            this.vendorMethod = vendorMethod;
            this.buyerMethod = buyerMethod;
            this.vendorLineNumber = vendorLineNumber;
            this.buyerLineNumber = buyerLineNumber;
        }

        public String getVendorGstin() {
            return vendorGstin;
        }

        public String getBuyerGstin() {
            return buyerGstin;
        }

        public String getVendorMethod() {
            return vendorMethod;
        }

        public String getBuyerMethod() {
            return buyerMethod;
        }

        public Integer getVendorLineNumber() {
            return vendorLineNumber;
        }

        public Integer getBuyerLineNumber() {
            return buyerLineNumber;
        }
    }

    @Override
    public String[] extract(String[] lines, int[] zones) {
        return new String[0];
    }

    public String[] extract(LineIndexingService.Zones zones) {
        Result result = extractResult(zones);
        return new String[]{result.getVendorGstin(), result.getBuyerGstin()};
    }

    public Result extractResult(LineIndexingService.Zones zones) {
        List<SectionRange> buyerSections = buyerSections(zones);
        FieldExtractionResult<String> vendor = extractVendorGstin(zones, buyerSections);
        FieldExtractionResult<String> buyer = extractBuyerGstin(zones, buyerSections, vendor.getValue());

        return new Result(
                vendor.getValue(),
                buyer.getValue(),
                vendor.getMethod(),
                buyer.getMethod(),
                vendor.getLineNumber(),
                buyer.getLineNumber()
        );
    }

    private FieldExtractionResult<String> extractVendorGstin(LineIndexingService.Zones zones, List<SectionRange> buyerSections) {
        List<ScoredGstinCandidate> candidates = collectCandidates(zones, buyerSections);
        ScoredGstinCandidate best = pickBestCandidate(candidates, null, false);
        return best == null
                ? new FieldExtractionResult<>(null, "fallback", null)
                : new FieldExtractionResult<>(best.value, best.method(), best.lineNumber);
    }

    private FieldExtractionResult<String> extractBuyerGstin(LineIndexingService.Zones zones,
                                                            List<SectionRange> buyerSections,
                                                            String excluded) {
        List<ScoredGstinCandidate> candidates = collectCandidates(zones, buyerSections);
        ScoredGstinCandidate best = pickBestCandidate(candidates, excluded, true);
        return best == null
                ? new FieldExtractionResult<>(null, "fallback", null)
                : new FieldExtractionResult<>(best.value, best.method(), best.lineNumber);
    }

    private List<ScoredGstinCandidate> collectCandidates(LineIndexingService.Zones zones, List<SectionRange> buyerSections) {
        List<ScoredGstinCandidate> candidates = new ArrayList<>();
        for (int i = 0; i < zones.allLines.size(); i++) {
            LineIndexingService.IndexedLine line = zones.allLines.get(i);
            boolean labeled = hasGstinLabel(zones.allLines, i) || hasGstinLikeSignal(line.getText());
            boolean buyerSection = isLineInSections(line.getLineNumber(), buyerSections);
            boolean transportLike = isTransportLike(line.getText());
            boolean disallowedBuyer = isDisallowedBuyerLine(line.getText());
            String zone = zones.zoneForLineNumber(line.getLineNumber());
            int sectionDistance = nearestSectionDistance(line.getLineNumber(), buyerSections);
            int labelStrength = labelContextStrength(zones.allLines, i);

            for (String match : extractMatches(line)) {
                if (!RegexUtil.isValidGstin(match)) {
                    continue;
                }
                ScoredGstinCandidate candidate = new ScoredGstinCandidate(
                        match,
                        line.getLineNumber(),
                        zone,
                        labeled,
                        labelStrength,
                        buyerSection,
                        sectionDistance,
                        transportLike,
                        disallowedBuyer,
                        OcrLayoutUtil.isGovernmentLike(line.getText().toLowerCase())
                );
                candidates.add(candidate);
            }
        }

        for (ScoredGstinCandidate candidate : candidates) {
            int occurrences = 0;
            for (ScoredGstinCandidate other : candidates) {
                if (candidate.value.equalsIgnoreCase(other.value)) {
                    occurrences++;
                }
            }
            candidate.occurrences = occurrences;
        }
        return candidates;
    }

    private ScoredGstinCandidate pickBestCandidate(List<ScoredGstinCandidate> candidates,
                                                   String excluded,
                                                   boolean buyerRole) {
        ScoredGstinCandidate best = null;
        int bestScore = Integer.MIN_VALUE;
        for (ScoredGstinCandidate candidate : candidates) {
            if (!isAllowed(candidate.value, excluded)) {
                continue;
            }
            int score = buyerRole ? scoreBuyerCandidate(candidate) : scoreVendorCandidate(candidate);
            if (score > bestScore) {
                bestScore = score;
                best = candidate;
            }
        }
        return best;
    }

    private int scoreVendorCandidate(ScoredGstinCandidate candidate) {
        int score = 20;
        score += zoneBoost(candidate.zone, "TOP") * 18;
        score += "TOP".equals(candidate.zone) ? 24 : 0;
        score += "MIDDLE".equals(candidate.zone) ? 4 : 0;
        score += candidate.labeled ? 26 : 0;
        score += candidate.labelStrength * 8;
        score += RegexUtil.hasGstinChecksum(candidate.value) ? 24 : -12;
        score += candidate.occurrences > 1 ? Math.min(18, (candidate.occurrences - 1) * 6) : 0;
        score += candidate.transportLike ? -95 : 0;
        score += candidate.buyerSection ? -105 : 0;
        score += candidate.disallowedBuyerLine ? -120 : 0;
        score += candidate.sectionDistance <= 1 ? -40 : 0;
        score += "BOTTOM".equals(candidate.zone) ? -120 : 0;
        // Heavy penalty if GSTIN belongs to known government entity (likely buyer)
        score += isGovernmentEntityGstin(candidate.value) ? -150 : 0;
        score += candidate.governmentContext ? -30 : 0;
        return score;
    }

    private int scoreBuyerCandidate(ScoredGstinCandidate candidate) {
        int score = 10;
        score += zoneBoost(candidate.zone, "MIDDLE") * 16;
        score += candidate.buyerSection ? 60 : 0;
        score += candidate.sectionDistance == 0 ? 18 : 0;
        score += candidate.sectionDistance == 1 ? 12 : 0;
        score += candidate.labeled ? 24 : 0;
        score += candidate.labelStrength * 8;
        score += RegexUtil.hasGstinChecksum(candidate.value) ? 24 : -12;
        score += candidate.governmentContext ? 12 : 0;
        score += candidate.occurrences > 1 ? Math.min(15, (candidate.occurrences - 1) * 5) : 0;
        score += candidate.transportLike ? -120 : 0;
        score += candidate.disallowedBuyerLine ? -130 : 0;
        score += "TOP".equals(candidate.zone) && !candidate.buyerSection ? -55 : 0;
        score += "BOTTOM".equals(candidate.zone) ? -120 : 0;
        // Boost if GSTIN belongs to known government entity (likely buyer)
        score += isGovernmentEntityGstin(candidate.value) ? 80 : 0;
        return score;
    }

    private int zoneBoost(String zone, String preferredZone) {
        if (preferredZone.equals(zone)) {
            return 2;
        }
        if ("TABLE".equals(zone) || "BOTTOM".equals(zone)) {
            return -2;
        }
        return 0;
    }

    private int nearestSectionDistance(int lineNumber, List<SectionRange> sections) {
        int best = Integer.MAX_VALUE;
        for (SectionRange section : sections) {
            if (lineNumber >= section.startLineNumber && lineNumber <= section.endLineNumber) {
                return 0;
            }
            best = Math.min(best, Math.min(Math.abs(lineNumber - section.startLineNumber), Math.abs(lineNumber - section.endLineNumber)));
        }
        return best == Integer.MAX_VALUE ? 99 : best;
    }

    private int labelContextStrength(List<LineIndexingService.IndexedLine> lines, int index) {
        int strength = 0;
        for (int offset = -1; offset <= 1; offset++) {
            int current = index + offset;
            if (current < 0 || current >= lines.size()) {
                continue;
            }
            String lower = lines.get(current).getText().toLowerCase();
            if (lower.contains("gstin") || lower.contains("gstin/uin") || lower.contains("gst no")
                    || lower.contains("gst in") || lower.contains("uin") || lower.contains("party gst")) {
                strength++;
            }
            if (OcrLayoutUtil.isBuyerSectionHeader(lower)) {
                strength++;
            }
        }
        return strength;
    }

    private boolean isAllowed(String gstin, String excluded) {
        if (!RegexUtil.isValidGstin(gstin)) {
            return false;
        }
        return excluded == null || !excluded.equalsIgnoreCase(gstin);
    }

    private boolean hasGstinLabel(List<LineIndexingService.IndexedLine> lines, int index) {
        for (int offset = -1; offset <= 1; offset++) {
            int current = index + offset;
            if (current < 0 || current >= lines.size()) {
                continue;
            }
            String lower = lines.get(current).getText().toLowerCase();
            if (lower.contains("gstin") || lower.contains("gstin/uin") || lower.contains("gstinuin")
                    || lower.contains("gst no") || lower.contains("gst in") || lower.contains("uin")
                    || lower.contains("partygst") || lower.contains("party gst") || lower.contains("tin no")) {
                return true;
            }
        }
        return false;
    }

    private List<String> extractMatches(LineIndexingService.IndexedLine line) {
        Set<String> matches = new LinkedHashSet<>();
        boolean labeledLine = hasGstinLikeSignal(line.getText());
        for (String fragment : OcrLayoutUtil.fragments(line.getText())) {
            String compact = fragment.replaceAll("\\s+", "");
            Matcher directMatcher = RegexUtil.GSTIN_PATTERN.matcher(compact);
            while (directMatcher.find()) {
                String gstin = directMatcher.group().toUpperCase();
                if (RegexUtil.isValidGstin(gstin) && !needsRepair(gstin)) {
                    matches.add(gstin);
                } else if (needsRepair(gstin)) {
                    String repaired = RegexUtil.repairGstinCandidate(gstin);
                    if (RegexUtil.isValidGstin(repaired)) {
                        matches.add(repaired);
                    } else if (RegexUtil.isValidGstin(gstin)) {
                        matches.add(gstin);
                    }
                }
            }
            Matcher tokenMatcher = RegexUtil.GSTIN_TOKEN_PATTERN.matcher(fragment);
            while (tokenMatcher.find()) {
                collectCandidateToken(matches, tokenMatcher.group(), labeledLine);
            }
            collectSlidingWindowCandidates(matches, fragment, labeledLine);
            collectDeletionCandidates(matches, fragment, labeledLine);
        }
        return new ArrayList<>(matches);
    }

    private void collectSlidingWindowCandidates(Set<String> matches, String fragment, boolean labeledLine) {
        String normalized = stripGstinLabel(fragment).replaceAll("[^A-Za-z0-9]", "").toUpperCase();
        int maxLength = labeledLine ? 24 : 18;
        if (normalized.length() <= 15 || normalized.length() > maxLength) {
            return;
        }
        for (int start = 0; start <= normalized.length() - 15; start++) {
            collectCandidateToken(matches, normalized.substring(start, start + 15), labeledLine);
        }
    }

    private void collectDeletionCandidates(Set<String> matches, String fragment, boolean labeledLine) {
        if (!labeledLine || fragment == null) {
            return;
        }
        String normalized = stripGstinLabel(fragment).replaceAll("[^A-Za-z0-9]", "").toUpperCase();
        if (normalized.length() < 16 || normalized.length() > 24) {
            return;
        }
        for (int drop = 0; drop < normalized.length(); drop++) {
            String candidate = normalized.substring(0, drop) + normalized.substring(drop + 1);
            if (candidate.length() != 15) {
                continue;
            }
            collectCandidateToken(matches, candidate, true);
        }
    }

    private String stripGstinLabel(String fragment) {
        if (fragment == null) {
            return "";
        }
        String normalized = fragment.replaceFirst("(?i)^.*?\\b(?:gstin/uin|gstin|gst\\s*no|gst\\s*in|uin|tin\\s*no|party\\s*gst)\\b\\s*[:#-]*\\s*", "");
        return normalized.isBlank() ? fragment : normalized;
    }

    private void collectCandidateToken(Set<String> matches, String token, boolean labeledLine) {
        if (token == null) {
            return;
        }
        String normalized = token.replaceAll("[^A-Za-z0-9]", "").toUpperCase();
        if (normalized.length() != 15) {
            return;
        }
        if (!RegexUtil.isValidGstin(normalized) && !looksRepairableToken(normalized)) {
            return;
        }
        if (RegexUtil.isValidGstin(normalized) && !needsRepair(normalized)) {
            matches.add(normalized);
            return;
        }
        if (!needsRepair(normalized)) {
            if (RegexUtil.isValidGstin(normalized)) {
                matches.add(normalized);
            }
            return;
        }
        String repaired = RegexUtil.repairGstinCandidate(normalized);
        if (RegexUtil.isValidGstin(repaired)) {
            matches.add(repaired);
        } else if (RegexUtil.isValidGstin(normalized)) {
            matches.add(normalized);
        }
    }

    private boolean hasGstinLikeSignal(String text) {
        String lower = text == null ? "" : text.toLowerCase();
        return lower.contains("gstin")
                || lower.contains("gstin/uin")
                || lower.contains("gst in")
                || lower.contains("gst no")
                || lower.contains("gstin no")
                || lower.contains("unique id")
                || lower.contains("uin")
                || lower.contains("tin no")
                || lower.contains("partygst");
    }

    private boolean looksRepairableToken(String token) {
        String normalized = token == null ? "" : token.replaceAll("[^A-Za-z0-9]", "").toUpperCase();
        if (normalized.length() != 15) {
            return false;
        }
        if (!normalized.substring(0, 2).matches("[0-9OILDQZSBTG]{2}")) {
            return false;
        }
        int letterLike = 0;
        int digitLike = 0;
        for (int index = 2; index <= 6; index++) {
            char value = normalized.charAt(index);
            if (Character.isLetter(value) || "01256789".indexOf(value) >= 0) {
                letterLike++;
            }
        }
        for (int index = 7; index <= 10; index++) {
            char value = normalized.charAt(index);
            if (Character.isDigit(value) || "OQDILZSBTG".indexOf(value) >= 0) {
                digitLike++;
            }
        }
        char panTail = normalized.charAt(11);
        char entity = normalized.charAt(12);
        char separator = normalized.charAt(13);
        return letterLike >= 4
                && digitLike >= 3
                && (Character.isLetter(panTail) || "01256789".indexOf(panTail) >= 0)
                && Character.isLetterOrDigit(entity)
                && (separator == 'Z' || separator == '2');
    }

    private boolean needsRepair(String gstin) {
        String normalized = gstin == null ? "" : gstin.replaceAll("[^A-Za-z0-9]", "").toUpperCase();
        if (normalized.length() != 15) {
            return false;
        }
        int[] digitLikePositions = {0, 1, 7, 8, 9, 10};
        for (int position : digitLikePositions) {
            char value = normalized.charAt(position);
            if (!Character.isDigit(value)) {
                return true;
            }
        }
        int[] letterLikePositions = {2, 3, 4, 5, 6, 11};
        for (int position : letterLikePositions) {
            if (!Character.isLetter(normalized.charAt(position))) {
                return true;
            }
        }
        char entityCode = normalized.charAt(12);
        if (!Character.isLetterOrDigit(entityCode)
                || entityCode == '0'
                || "ILOQDSZBTG".indexOf(entityCode) >= 0) {
            return true;
        }
        return normalized.charAt(13) != 'Z';
    }

    private boolean isTransportLike(String text) {
        String lower = text == null ? "" : text.toLowerCase();
        return lower.contains("transport") || lower.contains("dispatch") || lower.contains("vehicle")
                || lower.contains("buyer's order") || lower.contains("purchase order")
                || OcrLayoutUtil.isLogisticsLike(lower);
    }

    private boolean isDisallowedBuyerLine(String text) {
        String lower = text == null ? "" : text.toLowerCase();
        if (lower.contains("transport") || lower.contains("dispatch") || lower.contains("vehicle")
                || lower.contains("buyer's order") || lower.contains("buyers order")
                || lower.contains("purchase order")) {
            return true;
        }
        return OcrLayoutUtil.isLogisticsLike(lower) && !hasGstinLikeSignal(text);
    }

    /**
     * Detect GSTINs belonging to known government entities (DAE, NFC, ECIL, etc.)
     */
    private boolean isGovernmentEntityGstin(String gstin) {
        if (gstin == null || gstin.length() < 15) {
            return false;
        }
        String pan = gstin.substring(2, 12);
        return Set.of("AAAGN1030Q", "AAAGD0290L", "AAAGE0014G", "AAAGB0282M").contains(pan);
    }

    private List<SectionRange> buyerSections(LineIndexingService.Zones zones) {
        List<SectionRange> sections = new ArrayList<>();
        int tableStart = zones.getTableHeaderLine() == null ? Integer.MAX_VALUE : zones.getTableHeaderLine().getLineNumber();
        for (int i = 0; i < zones.allLines.size(); i++) {
            String lower = zones.allLines.get(i).getText().toLowerCase();
            if (!OcrLayoutUtil.isBuyerSectionHeader(lower)) {
                continue;
            }
            int startLine = zones.allLines.get(i).getLineNumber();
            int endLine = Math.min(tableStart, startLine + 18);
            for (int j = i + 1; j < zones.allLines.size(); j++) {
                int lineNumber = zones.allLines.get(j).getLineNumber();
                if (lineNumber > endLine) {
                    break;
                }
                String current = zones.allLines.get(j).getText().toLowerCase();
                if (lineNumber > startLine && (OcrLayoutUtil.isBuyerSectionHeader(current) || OcrLayoutUtil.looksLikeTableHeader(current) || OcrLayoutUtil.isItemStopLine(current))) {
                    endLine = lineNumber - 1;
                    break;
                }
            }
            sections.add(new SectionRange(startLine, Math.max(startLine, endLine), headerPriority(lower)));
        }
        sections.sort(Comparator
                .comparingInt(SectionRange::getPriority)
                .thenComparingInt(SectionRange::getStartLineNumber));
        return sections;
    }

    private boolean isLineInSections(int lineNumber, List<SectionRange> sections) {
        for (SectionRange section : sections) {
            if (lineNumber >= section.startLineNumber && lineNumber <= section.endLineNumber) {
                return true;
            }
        }
        return false;
    }

    private int headerPriority(String lower) {
        if (lower.contains("bill to") || lower.contains("billed to") || lower.contains("buyer")
                || lower.contains("recipient") || lower.contains("receiver")) {
            return 0;
        }
        if (lower.contains("consignee") || lower.contains("ship to")) {
            return 1;
        }
        return 2;
    }

    private static class ScoredGstinCandidate {
        private final String value;
        private final Integer lineNumber;
        private final String zone;
        private final boolean labeled;
        private final int labelStrength;
        private final boolean buyerSection;
        private final int sectionDistance;
        private final boolean transportLike;
        private final boolean disallowedBuyerLine;
        private final boolean governmentContext;
        private int occurrences;

        private ScoredGstinCandidate(String value,
                                     Integer lineNumber,
                                     String zone,
                                     boolean labeled,
                                     int labelStrength,
                                     boolean buyerSection,
                                     int sectionDistance,
                                     boolean transportLike,
                                     boolean disallowedBuyerLine,
                                     boolean governmentContext) {
            this.value = value;
            this.lineNumber = lineNumber;
            this.zone = zone;
            this.labeled = labeled;
            this.labelStrength = labelStrength;
            this.buyerSection = buyerSection;
            this.sectionDistance = sectionDistance;
            this.transportLike = transportLike;
            this.disallowedBuyerLine = disallowedBuyerLine;
            this.governmentContext = governmentContext;
        }

        private String method() {
            return labeled || labelStrength > 0 || buyerSection ? "keyword" : "regex";
        }
    }

    private static class SectionRange {
        private final int startLineNumber;
        private final int endLineNumber;
        private final int priority;

        private SectionRange(int startLineNumber, int endLineNumber, int priority) {
            this.startLineNumber = startLineNumber;
            this.endLineNumber = endLineNumber;
            this.priority = priority;
        }

        private int getStartLineNumber() {
            return startLineNumber;
        }

        private int getPriority() {
            return priority;
        }
    }
}
