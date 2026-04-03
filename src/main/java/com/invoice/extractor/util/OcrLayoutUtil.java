package com.invoice.extractor.util;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class OcrLayoutUtil {
    public static final List<String> HEADER_METADATA_KEYWORDS = List.of(
            "invoice no", "invoice number", "invoice #", "e-way", "dated", "delivery note",
            "mode/terms", "mode / terms", "reference no", "buyer's order", "purchase order",
            "purchase order no", "purchase order date", "customer po date", "dispatch doc",
            "delivery note date", "dispatched through", "destination", "bill of lading",
            "lr-rr", "motor vehicle", "terms of delivery", "transportation mode", "vehicle no"
    );

    public static final List<String> BUYER_STOP_KEYWORDS = List.of(
            "dispatched through", "destination", "delivery note", "bill of lading", "lr-rr",
            "motor vehicle", "terms of delivery", "mode/terms", "reference no", "buyer's order",
            "purchase order", "dispatch doc", "state name", "code :", "place of supply",
            "date of receiver", "delivery note date", "vehicle no", "transportation mode"
    );

    public static final List<String> BUYER_SECTION_KEYWORDS = List.of(
            "buyer", "bill to", "billed to", "consignee", "ship to", "details of recipient",
            "details of consignee", "details of recipient (billed to)", "details of consignee (shipped to)"
    );

    public static final List<String> LOGISTICS_KEYWORDS = List.of(
            "dispatch", "delivery note", "mode/terms", "bill of lading", "lr-rr", "motor vehicle",
            "transporter", "transport", "destination", "buyer's order", "reference no", "e-way"
    );

    public static final List<String> ADDRESS_KEYWORDS = List.of(
            "plot", "road", "rd", "near", "mob", "email", "state name", "code", "india",
            "via", "branch", "ifsc", "complex", "street", "lane", "gali", "po ", "po,",
            "post", "district", "pin code", "telangana", "rajasthan", "gujarat", "hyderabad"
    );

    public static final List<String> NON_ITEM_KEYWORDS = List.of(
            "total", "grand total", "amount payable", "taxable value", "tax amount", "igst", "cgst",
            "sgst", "bank", "ifsc", "declaration", "jurisdiction", "authorised", "pan", "subject",
            "invoice", "terms of delivery", "mode/terms", "branch", "company", "output-"
    );

    public static final List<String> ITEM_STOP_KEYWORDS = List.of(
            "subtotal", "sub total", "tax", "taxable value", "grand total", "amount payable",
            "invoice value", "net amount", "bank details", "declaration", "total tax amount"
    );

    public static final List<String> TABLE_HEADER_KEYWORDS = List.of(
            "description", "item", "particular", "goods", "goods/services", "qty", "quantity",
            "rate", "amount", "hsn", "sac", "uom", "unit", "discount", "taxable", "value", "code"
    );

    private OcrLayoutUtil() {
    }

    public static List<String> fragments(String text) {
        String normalized = RegexUtil.normalizeLine(text);
        if (normalized.isEmpty()) {
            return List.of();
        }
        Set<String> parts = new LinkedHashSet<>();
        parts.add(normalized);
        for (String pipePart : normalized.split("\\|+")) {
            addIfMeaningful(parts, pipePart);
            splitBeforeMetadata(parts, pipePart, HEADER_METADATA_KEYWORDS);
            splitBeforeMetadata(parts, pipePart, BUYER_STOP_KEYWORDS);
        }
        List<String> fragments = new ArrayList<>(parts);
        fragments.sort((left, right) -> Integer.compare(left.length(), right.length()));
        return fragments;
    }

    public static String truncateAtKeyword(String text, List<String> keywords) {
        String normalized = RegexUtil.normalizeLine(text);
        int cutIndex = earliestKeywordIndex(normalized, keywords);
        if (cutIndex <= 0) {
            return normalized;
        }
        return normalized.substring(0, cutIndex).trim();
    }

    public static boolean isAddressLike(String text) {
        return RegexUtil.containsAnyKeyword(text, ADDRESS_KEYWORDS);
    }

    public static boolean isLogisticsLike(String text) {
        return RegexUtil.containsAnyKeyword(text, BUYER_STOP_KEYWORDS) || RegexUtil.containsAnyKeyword(text, LOGISTICS_KEYWORDS);
    }

    public static boolean isNonItemLine(String text) {
        return RegexUtil.containsAnyKeyword(text, NON_ITEM_KEYWORDS);
    }

    public static boolean isItemStopLine(String text) {
        return RegexUtil.containsAnyKeyword(text, ITEM_STOP_KEYWORDS);
    }

    public static boolean isBuyerSectionHeader(String text) {
        String lower = text == null ? "" : text.toLowerCase(Locale.ROOT);
        return RegexUtil.containsAnyKeyword(lower, BUYER_SECTION_KEYWORDS)
                && !lower.contains("buyer's order")
                && !lower.contains("purchase order");
    }

    public static boolean looksLikeTableHeader(String text) {
        String lower = text == null ? "" : text.toLowerCase(Locale.ROOT);
        int hits = 0;
        for (String keyword : TABLE_HEADER_KEYWORDS) {
            if (lower.contains(keyword)) {
                hits++;
            }
        }
        return hits >= 2;
    }

    private static void splitBeforeMetadata(Set<String> parts, String text, List<String> keywords) {
        String normalized = RegexUtil.normalizeLine(text);
        int cutIndex = earliestKeywordIndex(normalized, keywords);
        if (cutIndex > 0) {
            addIfMeaningful(parts, normalized.substring(0, cutIndex));
            addIfMeaningful(parts, normalized.substring(cutIndex));
        }
    }

    private static int earliestKeywordIndex(String text, List<String> keywords) {
        String lower = text.toLowerCase(Locale.ROOT);
        int bestIndex = -1;
        for (String keyword : keywords) {
            int index = lower.indexOf(keyword.toLowerCase(Locale.ROOT));
            if (index > 0 && (bestIndex == -1 || index < bestIndex)) {
                bestIndex = index;
            }
        }
        return bestIndex;
    }

    private static void addIfMeaningful(Set<String> parts, String value) {
        String normalized = RegexUtil.normalizeLine(value);
        if (!normalized.isEmpty()) {
            parts.add(normalized);
        }
    }
}
