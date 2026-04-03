package com.invoice.extractor.extractor;

import com.invoice.extractor.service.impl.LineIndexingService;
import com.invoice.extractor.util.OcrLayoutUtil;
import com.invoice.extractor.util.RegexUtil;

import java.util.ArrayList;
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
        int buyerStart = buyerSections.isEmpty() ? Integer.MAX_VALUE : buyerSections.get(0).startLineNumber;
        List<LineIndexingService.IndexedLine> candidates = new ArrayList<>();
        for (LineIndexingService.IndexedLine line : zones.allLines) {
            if (line.getLineNumber() < buyerStart || zones.topZone.stream().anyMatch(top -> top.getLineNumber() == line.getLineNumber())) {
                candidates.add(line);
            }
        }
        FieldExtractionResult<String> labeled = firstLabeledDistinct(candidates, null, false);
        if (labeled.getValue() != null) {
            return labeled;
        }
        return firstDistinct(candidates, null, false);
    }

    private FieldExtractionResult<String> extractBuyerGstin(LineIndexingService.Zones zones,
                                                            List<SectionRange> buyerSections,
                                                            String excluded) {
        for (SectionRange section : buyerSections) {
            List<LineIndexingService.IndexedLine> sectionLines = linesInSection(zones.allLines, section);
            FieldExtractionResult<String> labeled = firstLabeledDistinct(sectionLines, excluded, true);
            if (labeled.getValue() != null) {
                return labeled;
            }
            FieldExtractionResult<String> generic = firstDistinct(sectionLines, excluded, true);
            if (generic.getValue() != null) {
                return generic;
            }
        }

        FieldExtractionResult<String> labeledMiddle = firstLabeledDistinct(zones.middleZone, excluded, true);
        if (labeledMiddle.getValue() != null) {
            return labeledMiddle;
        }
        FieldExtractionResult<String> fallback = firstDistinct(zones.middleZone, excluded, true);
        if (fallback.getValue() == null) {
            System.out.println("DEBUG buyer sections:");
            for (SectionRange section : buyerSections) {
                System.out.println("section " + section.startLineNumber + "-" + section.endLineNumber);
                for (LineIndexingService.IndexedLine line : linesInSection(zones.allLines, section)) {
                    System.out.println("  " + line.getLineNumber() + ": " + line.getText());
                }
            }
            System.out.println("DEBUG middle zone:");
            for (LineIndexingService.IndexedLine line : zones.middleZone) {
                System.out.println("  " + line.getLineNumber() + ": " + line.getText());
            }
        }
        return fallback;
    }

    private FieldExtractionResult<String> firstLabeledDistinct(List<LineIndexingService.IndexedLine> lines,
                                                               String excluded,
                                                               boolean buyerSection) {
        for (int i = 0; i < lines.size(); i++) {
            if (!hasGstinLabel(lines, i)) {
                continue;
            }
            if (buyerSection && isTransportLike(lines.get(i).getText())) {
                continue;
            }
            for (String match : extractMatches(lines.get(i))) {
                if (isAllowed(match, excluded)) {
                    return new FieldExtractionResult<>(match, "keyword", lines.get(i).getLineNumber());
                }
            }
            if (i + 1 < lines.size()) {
                for (String match : extractMatches(lines.get(i + 1))) {
                    if (isAllowed(match, excluded) && !isTransportLike(lines.get(i + 1).getText())) {
                        return new FieldExtractionResult<>(match, "keyword", lines.get(i + 1).getLineNumber());
                    }
                }
            }
        }
        return new FieldExtractionResult<>(null, "fallback", null);
    }

    private FieldExtractionResult<String> firstDistinct(List<LineIndexingService.IndexedLine> lines,
                                                        String excluded,
                                                        boolean buyerSection) {
        for (LineIndexingService.IndexedLine line : lines) {
            if (buyerSection && isTransportLike(line.getText())) {
                continue;
            }
            for (String match : extractMatches(line)) {
                if (isAllowed(match, excluded)) {
                    return new FieldExtractionResult<>(match, "regex", line.getLineNumber());
                }
            }
        }
        return new FieldExtractionResult<>(null, "fallback", null);
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
                    || lower.contains("gst no") || lower.contains("gst in") || lower.contains("uin")) {
                return true;
            }
        }
        return false;
    }

    private List<String> extractMatches(LineIndexingService.IndexedLine line) {
        Set<String> matches = new LinkedHashSet<>();
        for (String fragment : OcrLayoutUtil.fragments(line.getText())) {
            String compact = fragment.replaceAll("\\s+", "");
            Matcher directMatcher = RegexUtil.GSTIN_PATTERN.matcher(compact);
            while (directMatcher.find()) {
                String gstin = directMatcher.group().toUpperCase();
                if (RegexUtil.isValidGstin(gstin)) {
                    matches.add(gstin);
                }
            }
            Matcher tokenMatcher = RegexUtil.GSTIN_TOKEN_PATTERN.matcher(fragment);
            while (tokenMatcher.find()) {
                String token = tokenMatcher.group();
                if (RegexUtil.isValidGstin(token)) {
                    matches.add(token.toUpperCase());
                    continue;
                }
                String repaired = RegexUtil.repairGstinCandidate(token);
                if (RegexUtil.isValidGstin(repaired)) {
                    matches.add(repaired);
                }
            }
        }
        return new ArrayList<>(matches);
    }

    private boolean isTransportLike(String text) {
        String lower = text == null ? "" : text.toLowerCase();
        return lower.contains("transport") || lower.contains("dispatch") || lower.contains("vehicle")
                || lower.contains("buyer's order") || lower.contains("purchase order")
                || OcrLayoutUtil.isLogisticsLike(lower);
    }

    private List<LineIndexingService.IndexedLine> linesInSection(List<LineIndexingService.IndexedLine> lines, SectionRange section) {
        List<LineIndexingService.IndexedLine> sectionLines = new ArrayList<>();
        for (LineIndexingService.IndexedLine line : lines) {
            if (line.getLineNumber() >= section.startLineNumber && line.getLineNumber() <= section.endLineNumber) {
                sectionLines.add(line);
            }
        }
        return sectionLines;
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
            int endLine = Math.min(tableStart - 1, startLine + 18);
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
            sections.add(new SectionRange(startLine, Math.max(startLine, endLine)));
        }
        return sections;
    }

    private static class SectionRange {
        private final int startLineNumber;
        private final int endLineNumber;

        private SectionRange(int startLineNumber, int endLineNumber) {
            this.startLineNumber = startLineNumber;
            this.endLineNumber = endLineNumber;
        }
    }
}
