package com.invoice.extractor.template;

import com.invoice.extractor.service.impl.LineIndexingService;
import com.invoice.extractor.util.DateUtil;
import com.invoice.extractor.util.OcrLayoutUtil;
import com.invoice.extractor.util.RegexUtil;

import java.security.MessageDigest;
import java.util.List;
import java.util.Locale;

public class TemplateSignatureGenerator {
    private static final List<String> SIGNATURE_KEYWORDS = List.of(
            "bill to", "ship to", "consignee", "buyer", "grand total", "amount payable", "invoice value"
    );

    public static String generateSignature(String rawText) {
        LineIndexingService.Zones zones = LineIndexingService.indexLinesAndZones(rawText == null ? "" : rawText);
        StringBuilder builder = new StringBuilder();
        appendTopLines(builder, zones.topZone, 6);
        appendKeywordLines(builder, zones.middleZone, List.of("bill to", "ship to", "consignee", "buyer"), 2);
        if (zones.getTableHeaderLine() != null) {
            builder.append("|TABLE=").append(normalizeForSignature(zones.getTableHeaderLine().getText()));
        }
        if (zones.getTotalLine() != null) {
            builder.append("|TOTAL=").append(normalizeForSignature(zones.getTotalLine().getText()));
        }
        if (zones.getTaxLine() != null) {
            builder.append("|TAX=").append(normalizeForSignature(zones.getTaxLine().getText()));
        }
        return hash(builder.toString());
    }

    public static String generateLayoutSignature(String rawText) {
        LineIndexingService.Zones zones = LineIndexingService.indexLinesAndZones(rawText == null ? "" : rawText);
        StringBuilder builder = new StringBuilder();
        appendBestTopStructure(builder, zones.topZone);
        appendKeywordLines(builder, zones.middleZone, OcrLayoutUtil.BUYER_SECTION_KEYWORDS, 2);
        if (zones.getTableHeaderLine() != null) {
            builder.append("|TABLE=").append(normalizeStructure(zones.getTableHeaderLine().getText()));
        }
        if (zones.getTotalLine() != null) {
            builder.append("|TOTAL=").append(normalizeStructure(zones.getTotalLine().getText()));
        }
        if (zones.getTaxLine() != null) {
            builder.append("|TAX=").append(normalizeStructure(zones.getTaxLine().getText()));
        }
        return hash(builder.toString());
    }

    public static String generateSignature(String vendorGstin, String vendorName, List<String> first5Lines, List<String> keywords, String tableHeader) {
        try {
            StringBuilder sb = new StringBuilder();
            sb.append(vendorGstin == null ? "" : vendorGstin.trim().toUpperCase());
            sb.append("|").append(vendorName == null ? "" : vendorName.trim().toUpperCase());
            for (String line : first5Lines) sb.append("|").append(line.trim().toUpperCase());
            for (String k : keywords) sb.append("|").append(k.trim().toUpperCase());
            sb.append("|").append(tableHeader == null ? "" : tableHeader.trim().toUpperCase());
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] hash = md.digest(sb.toString().getBytes());
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private static void appendTopLines(StringBuilder builder, List<LineIndexingService.IndexedLine> lines, int maxLines) {
        int count = 0;
        for (LineIndexingService.IndexedLine line : lines) {
            String lower = line.getText().toLowerCase(Locale.ROOT);
            if (RegexUtil.containsAnyKeyword(lower, List.of("bill to", "ship to", "consignee", "buyer"))) {
                break;
            }
            if (lower.contains("description") || lower.contains("qty") || lower.contains("quantity")) {
                break;
            }
            String normalized = normalizeForSignature(line.getText());
            if (normalized.isBlank()) {
                continue;
            }
            if (count++ >= maxLines) {
                break;
            }
            builder.append('|').append(normalized);
        }
    }


    private static void appendBestTopStructure(StringBuilder builder, List<LineIndexingService.IndexedLine> lines) {
        String bestVendorLine = null;
        for (LineIndexingService.IndexedLine line : lines) {
            String lower = line.getText().toLowerCase(Locale.ROOT);
            if (lower.matches(".*\\b(ltd|limited|pvt|llp|industries|corporation|systems|chemicals|solutions)\\b.*")) {
                bestVendorLine = normalizeStructure(line.getText());
                break;
            }
        }
        if (bestVendorLine != null) {
            builder.append("|VENDOR=").append(bestVendorLine);
        }
        appendFirstKeywordLine(builder, lines, List.of("invoice no", "invoice number", "bill no", "inv no"), "INV");
        appendFirstKeywordLine(builder, lines, List.of("gstin", "gstin/uin", "gst no"), "GST");
        appendFirstKeywordLine(builder, lines, List.of("tax invoice"), "TYPE");
    }

    private static void appendKeywordLines(StringBuilder builder,
                                           List<LineIndexingService.IndexedLine> lines,
                                           List<String> keywords,
                                           int maxLines) {
        int count = 0;
        for (LineIndexingService.IndexedLine line : lines) {
            String lower = line.getText().toLowerCase(Locale.ROOT);
            if (!RegexUtil.containsAnyKeyword(lower, keywords)) {
                continue;
            }
            String normalized = normalizeForSignature(line.getText());
            if (normalized.isBlank()) {
                continue;
            }
            if (count++ >= maxLines) {
                break;
            }
            builder.append('|').append(normalized);
        }
    }

    private static void appendFirstKeywordLine(StringBuilder builder,
                                               List<LineIndexingService.IndexedLine> lines,
                                               List<String> keywords,
                                               String label) {
        for (LineIndexingService.IndexedLine line : lines) {
            String lower = line.getText().toLowerCase(Locale.ROOT);
            if (!RegexUtil.containsAnyKeyword(lower, keywords)) {
                continue;
            }
            String normalized = normalizeStructure(line.getText());
            if (!normalized.isBlank()) {
                builder.append('|').append(label).append('=').append(normalized);
                return;
            }
        }
    }

    private static String normalizeForSignature(String text) {
        String normalized = RegexUtil.normalizeLine(text).toUpperCase(Locale.ROOT);
        normalized = RegexUtil.GSTIN_PATTERN.matcher(normalized).replaceAll("<GSTIN>");
        for (String candidate : DateUtil.findCandidateDates(normalized)) {
            normalized = normalized.replace(candidate.toUpperCase(Locale.ROOT), "<DATE>");
        }
        normalized = normalized.replaceAll("(?i)(INVOICE\\s*(?:NO|NUMBER|#)|INV\\s*NO|BILL\\s*(?:NO|#))\\s*[:#-]*\\s*[A-Z0-9/-]{3,12}", "$1 <INVNO>");
        normalized = normalized.replaceAll("\\b\\d[\\d,]*(?:\\.\\d{1,2})?\\b", "<NUM>");
        if (!RegexUtil.containsAnyKeyword(normalized.toLowerCase(Locale.ROOT), SIGNATURE_KEYWORDS)
                && normalized.startsWith("<NUM>")) {
            return "";
        }
        return normalized.replaceAll("\\s+", " ").trim();
    }

    private static String normalizeStructure(String text) {
        String normalized = normalizeForSignature(text);
        normalized = normalized.replaceAll("(?i)\\b(?:PLOT|ROAD|RD|MOB|PHONE|EMAIL|FAX|PIN|CODE|STATE|INDIA)\\b", "<ADDR>");
        normalized = normalized.replaceAll("(?i)\\b(?:DISPATCH|DELIVERY|TRANSPORT|DESTINATION|MODE/TERMS|MOTOR VEHICLE|REFERENCE)\\b", "<META>");
        return normalized.replaceAll("\\s+", " ").trim();
    }

    private static String hash(String value) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] hash = md.digest(value.getBytes());
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception e) {
            return "";
        }
    }
}
