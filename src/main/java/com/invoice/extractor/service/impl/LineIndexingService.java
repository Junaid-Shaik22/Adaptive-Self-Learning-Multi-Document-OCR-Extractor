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
        // Top Zone: first 15 lines
        for (int i = 0; i < Math.min(15, indexed.size()); i++) {
            zones.topZone.add(indexed.get(i));
        }
        // Bottom Zone: last 30 lines to tolerate OCR line over-segmentation near totals and summary boxes.
        for (int i = Math.max(0, indexed.size() - 30); i < indexed.size(); i++) {
            zones.bottomZone.add(indexed.get(i));
        }

        int tableHeaderIdx = findTableHeaderIndex(indexed);
        int totalIdx = findTotalLineIndex(indexed, tableHeaderIdx);
        int taxIdx = findTaxLineIndex(indexed);

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
            int tableEnd = totalIdx != -1 && totalIdx > tableHeaderIdx ? totalIdx : indexed.size();
            for (int i = tableHeaderIdx + 1; i < tableEnd; i++) {
                zones.tableZone.add(indexed.get(i));
            }
        }

        buildMiddleZone(indexed, zones, tableHeaderIdx, totalIdx);
        return zones;
    }

    private static void buildMiddleZone(List<IndexedLine> indexed, Zones zones, int tableHeaderIdx, int totalIdx) {
        Set<Integer> middleIndexes = new LinkedHashSet<>();
        int upperLimit = tableHeaderIdx >= 0 ? tableHeaderIdx : (totalIdx >= 0 ? totalIdx : indexed.size());

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
            int start = Math.min(15, indexed.size());
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
                    "total amount", "total amount after tax", "net amount", "subtotal", "taxable value", "value (figure)"
            ))) {
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
