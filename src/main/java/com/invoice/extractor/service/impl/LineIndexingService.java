package com.invoice.extractor.service.impl;

import com.invoice.extractor.util.OcrLayoutUtil;
import com.invoice.extractor.util.RegexUtil;

import java.util.*;
public class LineIndexingService {
    public static class IndexedLine {
        private final int lineNumber;
        private final String text;

        public IndexedLine(int lineNumber, String text) {
            this.lineNumber = lineNumber;
            this.text = text;
        }

        public int getLineNumber() { return lineNumber; }
        public String getText() { return text; }
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
        String[] lines = ocrText.split("\\n");
        List<IndexedLine> indexed = new ArrayList<>();
        for (int i = 0; i < lines.length; i++) {
            String normalized = RegexUtil.normalizeLine(lines[i]);
            if (!normalized.isEmpty()) {
                indexed.add(new IndexedLine(indexed.size() + 1, normalized));
            }
        }
        Zones zones = new Zones();
        zones.allLines.addAll(indexed);
        int tableHeaderIdx = findTableHeaderIndex(indexed);
        int totalIdx = findTotalLineIndex(indexed, tableHeaderIdx);
        int taxIdx = findTaxLineIndex(indexed);
        int headerSize = computeHeaderSize(indexed.size(), tableHeaderIdx);
        int footerStartIdx = computeFooterStartIndex(indexed.size(), totalIdx);

        for (int i = 0; i < Math.min(headerSize, indexed.size()); i++) {
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
            for (int i = tableHeaderIdx + 1; i < tableEnd; i++) {
                zones.tableZone.add(indexed.get(i));
            }
        }

        buildMiddleZone(indexed, zones, tableHeaderIdx, totalIdx, footerStartIdx);
        return zones;
    }

    private static void buildMiddleZone(List<IndexedLine> indexed, Zones zones, int tableHeaderIdx, int totalIdx, int footerStartIdx) {
        Set<Integer> middleIndexes = new LinkedHashSet<>();
        int upperLimit = tableHeaderIdx >= 0 ? tableHeaderIdx : (totalIdx >= 0 ? totalIdx : footerStartIdx);

        for (int i = 0; i < indexed.size(); i++) {
            String lower = indexed.get(i).getText().toLowerCase(Locale.ROOT);
            if (isBuyerAnchor(lower)) {
                for (int j = i; j < Math.min(upperLimit, i + 18); j++) {
                    String current = indexed.get(j).getText().toLowerCase(Locale.ROOT);
                    if (j > i && (matchesTableHeader(current) || OcrLayoutUtil.isItemStopLine(current))) {
                        break;
                    }
                    middleIndexes.add(j);
                }
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
                    if (matchesTableHeader(current)) {
                        return i;
                    }
                    if (matchesTableHeader(next)) {
                        return i + 1;
                    }
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
}
