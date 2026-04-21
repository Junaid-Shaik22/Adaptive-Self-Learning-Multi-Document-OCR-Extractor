package com.invoice.extractor.service.impl;

import com.invoice.extractor.util.OcrLayoutUtil;
import com.invoice.extractor.util.RegexUtil;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class LineIndexingService {
    private static final Pattern COLUMN_BLOCK_PATTERN = Pattern.compile("\\S(?:.*?\\S)?(?=\\s{3,}|$)");
    private static final int LINE_HEIGHT = 18;
    private static final int LINE_GAP = 22;
    private static final int PAGE_Y_OFFSET = 10_000;

    public enum Column {
        LEFT_COLUMN,
        RIGHT_COLUMN,
        FULL_WIDTH
    }

    public static class IndexedLine {
        private final int lineNumber;
        private final String text;
        private final String originalText;
        private final int x;
        private final int y;
        private final int width;
        private final int height;
        private final int pageNumber;
        private final Column column;

        public IndexedLine(int lineNumber, String text) {
            this(lineNumber, text, text, 0, Math.max(0, lineNumber - 1) * LINE_GAP,
                    text == null ? 0 : Math.max(24, text.length() * 8), LINE_HEIGHT, 1, Column.FULL_WIDTH);
        }

        public IndexedLine(int lineNumber, String text, int x, int y, int width, int height, int pageNumber) {
            this(lineNumber, text, text, x, y, width, height, pageNumber, Column.FULL_WIDTH);
        }

        public IndexedLine(int lineNumber,
                           String text,
                           String originalText,
                           int x,
                           int y,
                           int width,
                           int height,
                           int pageNumber,
                           Column column) {
            this.lineNumber = lineNumber;
            this.text = text == null ? "" : text;
            this.originalText = originalText == null ? this.text : originalText;
            this.x = Math.max(0, x);
            this.y = Math.max(0, y);
            this.width = Math.max(0, width);
            this.height = Math.max(1, height);
            this.pageNumber = Math.max(1, pageNumber);
            this.column = column == null ? Column.FULL_WIDTH : column;
        }

        public int getLineNumber() {
            return lineNumber;
        }

        public String getText() {
            return text;
        }

        public String getOriginalText() {
            return originalText;
        }

        public int getX() {
            return x;
        }

        public int getY() {
            return y;
        }

        public int getWidth() {
            return width;
        }

        public int getHeight() {
            return height;
        }

        public int getPageNumber() {
            return pageNumber;
        }

        public Column getColumn() {
            return column;
        }
    }

    public static class Zones {
        public final List<IndexedLine> allLines = new ArrayList<>();
        public final List<IndexedLine> topZone = new ArrayList<>();
        public final List<IndexedLine> middleZone = new ArrayList<>();
        public final List<IndexedLine> buyerZone = middleZone;
        public final List<IndexedLine> tableZone = new ArrayList<>();
        public final List<IndexedLine> bottomZone = new ArrayList<>();
        private IndexedLine tableHeaderLine;
        private IndexedLine totalLine;
        private IndexedLine taxLine;
        private int headerEndLine;
        private int footerStartLine;

        public List<IndexedLine> getZone(String zoneName) {
            if (zoneName == null) {
                return allLines;
            }
            return switch (zoneName.toUpperCase(Locale.ROOT)) {
                case "TOP" -> topZone;
                case "MIDDLE" -> middleZone;
                case "TABLE" -> tableZone;
                case "BOTTOM" -> bottomZone;
                default -> allLines;
            };
        }

        public IndexedLine getTableHeaderLine() {
            return tableHeaderLine;
        }

        public void setTableHeaderLine(IndexedLine tableHeaderLine) {
            this.tableHeaderLine = tableHeaderLine;
        }

        public IndexedLine getTotalLine() {
            return totalLine;
        }

        public void setTotalLine(IndexedLine totalLine) {
            this.totalLine = totalLine;
        }

        public IndexedLine getTaxLine() {
            return taxLine;
        }

        public void setTaxLine(IndexedLine taxLine) {
            this.taxLine = taxLine;
        }

        public int getHeaderEndLine() {
            return headerEndLine;
        }

        public void setHeaderEndLine(int headerEndLine) {
            this.headerEndLine = headerEndLine;
        }

        public int getFooterStartLine() {
            return footerStartLine;
        }

        public void setFooterStartLine(int footerStartLine) {
            this.footerStartLine = footerStartLine;
        }

        public String zoneForLineNumber(Integer lineNumber) {
            if (lineNumber == null || allLines.isEmpty()) {
                return "UNKNOWN";
            }
            if (lineNumber <= headerEndLine) {
                return "TOP";
            }
            if (lineNumber >= footerStartLine) {
                return "BOTTOM";
            }
            if (tableHeaderLine != null && lineNumber >= tableHeaderLine.getLineNumber()
                    && (totalLine == null || lineNumber < totalLine.getLineNumber())) {
                return "TABLE";
            }
            return "MIDDLE";
        }

        public int totalLines() {
            return allLines.size();
        }
    }

    public static Zones indexLinesAndZones(String ocrText) {
        return indexLinesAndZones(buildIndexedLines(ocrText));
    }

    public static Zones indexLinesAndZones(List<IndexedLine> indexedLines) {
        List<IndexedLine> indexed = indexedLines == null ? List.of() : indexedLines;
        Zones zones = new Zones();
        zones.allLines.addAll(indexed);
        if (indexed.isEmpty()) {
            zones.setHeaderEndLine(0);
            zones.setFooterStartLine(1);
            return zones;
        }

        int tableHeaderIdx = findTableHeaderIndex(indexed);
        int totalIdx = findTotalLineIndex(indexed, tableHeaderIdx);
        int taxIdx = findTaxLineIndex(indexed);
        int headerEndIdx = determineHeaderEndIndex(indexed, tableHeaderIdx);
        int footerStartIdx = determineFooterStartIndex(indexed, totalIdx);

        for (int i = 0; i <= headerEndIdx && i < indexed.size(); i++) {
            zones.topZone.add(indexed.get(i));
        }
        for (int i = Math.max(0, footerStartIdx); i < indexed.size(); i++) {
            zones.bottomZone.add(indexed.get(i));
        }

        zones.setHeaderEndLine(zones.topZone.isEmpty() ? 0 : zones.topZone.get(zones.topZone.size() - 1).getLineNumber());
        zones.setFooterStartLine(zones.bottomZone.isEmpty() ? indexed.size() + 1 : zones.bottomZone.get(0).getLineNumber());

        if (tableHeaderIdx >= 0) {
            zones.setTableHeaderLine(indexed.get(tableHeaderIdx));
        }
        if (totalIdx >= 0) {
            zones.setTotalLine(indexed.get(totalIdx));
        }
        if (taxIdx >= 0) {
            zones.setTaxLine(indexed.get(taxIdx));
        }

        if (tableHeaderIdx != -1) {
            int tableEnd = totalIdx != -1 && totalIdx > tableHeaderIdx ? totalIdx : footerStartIdx;
            for (int i = tableHeaderIdx + 1; i < tableEnd && i < indexed.size(); i++) {
                zones.tableZone.add(indexed.get(i));
            }
        }

        buildMiddleZone(indexed, zones, tableHeaderIdx, totalIdx, footerStartIdx);
        return zones;
    }

    private static List<IndexedLine> buildIndexedLines(String ocrText) {
        if (ocrText == null || ocrText.isBlank()) {
            return List.of();
        }
        List<IndexedLine> indexed = new ArrayList<>();
        String normalizedText = ocrText.replace("\r\n", "\n").replace('\r', '\n');
        String[] pages = normalizedText.split("\f", -1);
        int visibleLine = 0;

        for (int pageIndex = 0; pageIndex < pages.length; pageIndex++) {
            String page = pages[pageIndex];
            String[] rows = page.split("\n", -1);
            int visibleRow = 0;
            for (String row : rows) {
                String rawLine = row == null ? "" : row.replace('\t', ' ');
                if (rawLine.trim().isEmpty()) {
                    continue;
                }
                visibleRow++;
                List<Block> blocks = extractBlocks(rawLine);
                if (blocks.isEmpty()) {
                    continue;
                }
                Column defaultColumn = blocks.size() > 1 ? Column.LEFT_COLUMN : Column.FULL_WIDTH;
                for (int blockIndex = 0; blockIndex < blocks.size(); blockIndex++) {
                    Block block = blocks.get(blockIndex);
                    visibleLine++;
                    Column column = blocks.size() == 1
                            ? Column.FULL_WIDTH
                            : (blockIndex == 0 ? Column.LEFT_COLUMN : Column.RIGHT_COLUMN);
                    indexed.add(new IndexedLine(
                            visibleLine,
                            block.normalizedText(),
                            block.normalizedText(),
                            Math.max(0, block.start() * 8),
                            ((pageIndex * PAGE_Y_OFFSET) + (visibleRow - 1) * LINE_GAP),
                            Math.max(24, Math.max(block.normalizedText().length() * 8, (block.end() - block.start()) * 8)),
                            LINE_HEIGHT,
                            pageIndex + 1,
                            column == null ? defaultColumn : column
                    ));
                }
            }
        }
        return indexed;
    }

    private static List<Block> extractBlocks(String rawLine) {
        List<Block> blocks = new ArrayList<>();
        Matcher matcher = COLUMN_BLOCK_PATTERN.matcher(rawLine);
        while (matcher.find()) {
            String segment = matcher.group();
            String normalized = RegexUtil.normalizeLine(segment);
            if (!normalized.isEmpty()) {
                blocks.add(new Block(normalized, matcher.start(), matcher.end()));
            }
        }
        return blocks;
    }

    private static void buildMiddleZone(List<IndexedLine> indexed,
                                        Zones zones,
                                        int tableHeaderIdx,
                                        int totalIdx,
                                        int footerStartIdx) {
        Set<Integer> middleIndexes = new LinkedHashSet<>();
        int upperLimit = tableHeaderIdx >= 0 ? tableHeaderIdx : (totalIdx >= 0 ? totalIdx : footerStartIdx);

        for (int i = 0; i < indexed.size(); i++) {
            IndexedLine anchor = indexed.get(i);
            String lower = anchor.getText().toLowerCase(Locale.ROOT);
            if (!isBuyerAnchor(lower)) {
                continue;
            }
            for (int j = i; j < Math.min(upperLimit, i + 18); j++) {
                IndexedLine current = indexed.get(j);
                String currentLower = current.getText().toLowerCase(Locale.ROOT);
                if (j > i && (matchesTableHeader(currentLower) || OcrLayoutUtil.isItemStopLine(currentLower))) {
                    break;
                }
                if (j > i && Math.abs(current.getY() - anchor.getY()) > 220 && current.getColumn() != anchor.getColumn()) {
                    break;
                }
                middleIndexes.add(j);
            }
        }

        if (middleIndexes.isEmpty()) {
            int start = Math.min(Math.max(0, zones.topZone.size()), indexed.size());
            int end = Math.max(start, upperLimit);
            for (int i = start; i < end; i++) {
                middleIndexes.add(i);
            }
        }

        for (Integer index : middleIndexes) {
            zones.middleZone.add(indexed.get(index));
        }
    }

    private static boolean isBuyerAnchor(String text) {
        return OcrLayoutUtil.isBuyerSectionHeader(text);
    }

    private static int determineHeaderEndIndex(List<IndexedLine> indexed, int tableHeaderIdx) {
        int computed = computeHeaderSize(indexed.size(), tableHeaderIdx) - 1;
        for (int i = 1; i < indexed.size(); i++) {
            String lower = indexed.get(i).getText().toLowerCase(Locale.ROOT);
            if (OcrLayoutUtil.isBuyerSectionHeader(lower)) {
                computed = Math.min(computed, i);
                break;
            }
            if (matchesTableHeader(lower)) {
                computed = Math.min(computed, i);
                break;
            }
        }
        return Math.max(0, Math.min(computed, indexed.size() - 1));
    }

    private static int determineFooterStartIndex(List<IndexedLine> indexed, int totalIdx) {
        int start = computeFooterStartIndex(indexed.size(), totalIdx);
        for (int i = Math.max(0, indexed.size() - 25); i < indexed.size(); i++) {
            String lower = indexed.get(i).getText().toLowerCase(Locale.ROOT);
            if (RegexUtil.containsAnyKeyword(lower, List.of(
                    "grand total", "invoice value", "amount payable", "net amount",
                    "taxable value", "total tax amount"
            ))) {
                start = Math.min(start, i);
                break;
            }
        }
        return Math.max(0, Math.min(start, indexed.size()));
    }

    private static int computeHeaderSize(int totalLines, int tableHeaderIdx) {
        int computed = Math.max(12, (int) Math.ceil(totalLines * 0.25));
        computed = Math.min(25, computed);
        if (tableHeaderIdx > 0) {
            computed = Math.min(computed, tableHeaderIdx + 1);
        }
        return Math.max(1, Math.min(computed, totalLines));
    }

    private static int computeFooterStartIndex(int totalLines, int totalIdx) {
        int footerSize = Math.max(15, (int) Math.ceil(totalLines * 0.30));
        footerSize = Math.min(40, footerSize);
        int start = Math.max(0, totalLines - footerSize);
        if (totalIdx >= 0) {
            start = Math.min(start, Math.max(0, totalIdx - 2));
        }
        return Math.max(0, Math.min(start, totalLines));
    }

    private static int findTableHeaderIndex(List<IndexedLine> indexed) {
        for (int i = 0; i < indexed.size(); i++) {
            String current = indexed.get(i).getText().toLowerCase(Locale.ROOT);
            if (matchesTableHeader(current)) {
                return i;
            }
            if (i + 1 < indexed.size()) {
                String next = indexed.get(i + 1).getText().toLowerCase(Locale.ROOT);
                String combined = current + " " + next;
                if (matchesTableHeader(combined)) {
                    return matchesTableHeader(current) ? i : i + 1;
                }
            }
            if (i + 2 < indexed.size()) {
                String next = indexed.get(i + 1).getText().toLowerCase(Locale.ROOT);
                String third = indexed.get(i + 2).getText().toLowerCase(Locale.ROOT);
                String combined = current + " " + next + " " + third;
                if (matchesTableHeader(combined)) {
                    if (matchesTableHeader(current)) {
                        return i;
                    }
                    if (matchesTableHeader(next)) {
                        return i + 1;
                    }
                    if (matchesTableHeader(third)) {
                        return i + 2;
                    }
                }
            }
        }
        return -1;
    }

    private static boolean matchesTableHeader(String text) {
        return OcrLayoutUtil.looksLikeTableHeader(text);
    }

    private static int findTotalLineIndex(List<IndexedLine> indexed, int tableHeaderIdx) {
        int start = tableHeaderIdx >= 0 ? tableHeaderIdx + 1 : 0;
        for (int i = start; i < indexed.size(); i++) {
            String lower = indexed.get(i).getText().toLowerCase(Locale.ROOT);
            if (RegexUtil.containsAnyKeyword(lower, List.of(
                    "grand total", "invoice value", "total invoice value", "amount payable",
                    "total amount", "total amount after tax", "total to be taxed", "net amount",
                    "subtotal", "taxable value", "value (figure)", "total after tax",
                    "invoice amt", "inv value", "invoice amount"
            ))) {
                return i;
            }
            if (lower.matches("^total\\b.*") && !lower.contains("to be taxed")) {
                return i;
            }
        }
        return -1;
    }

    private static int findTaxLineIndex(List<IndexedLine> indexed) {
        for (int i = Math.max(0, indexed.size() - 30); i < indexed.size(); i++) {
            String lower = indexed.get(i).getText().toLowerCase(Locale.ROOT);
            if (lower.contains("igst") || lower.contains("cgst") || lower.contains("sgst") || lower.contains("tax")) {
                return i;
            }
        }
        return -1;
    }

    private record Block(String normalizedText, int start, int end) {
    }
}
