package com.invoice.extractor.util;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class OcrLayoutUtil {
    public static final List<String> BUSINESS_KEYWORDS = List.of(
            "ltd", "limited", "pvt", "private", "llp", "corporation", "industries", "enterprises",
            "solutions", "engineering", "chemicals", "electronics", "systems", "products", "company",
            "agency", "traders", "brothers", "associates", "logistics", "services", "co"
    );

    public static final List<String> GOVERNMENT_KEYWORDS = List.of(
            "department", "directorate", "ministry", "government", "govt", "atomic energy",
            "stores officer", "purchase unit", "regional stores unit", "fuel complex", "plant site"
    );

    public static final List<String> HEADER_NOISE_KEYWORDS = List.of(
            "invoice", "tax invoice", "e-invoice", "voucher", "statement", "receipt", "challan",
            "delivery note", "dispatch doc", "reference no", "mode/terms", "original for", "duplicate for",
            "triplicate for", "recipient", "supplier", "copy", "ack no", "ack date", "irn"
    );

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
            "buyer", "bill to", "billed to", "consignee", "ship to", "shipped to", "details of recipient",
            "details of consignee", "details of receiver", "details of buyer", "details of purchaser",
            "details of recipient (billed to)",
            "details of consignee (shipped to)", "details of receiver (billed to)"
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
            "invoice", "terms of delivery", "mode/terms", "branch", "company", "output-", "seal nos",
            "remarks", "gross", "tare", "nett", "credit period", "whether the tax", "invoice amt", "inv value"
    );

    public static final List<String> ITEM_STOP_KEYWORDS = List.of(
            "subtotal", "sub total", "tax", "taxable value", "grand total", "amount payable",
            "invoice value", "net amount", "bank details", "declaration", "total tax amount",
            "amount chargeable", "tax amount (in words)", "seal nos", "remarks", "whether the tax",
            "invoice amt", "inv value"
    );

    public static final List<String> TABLE_HEADER_KEYWORDS = List.of(
            "description", "item", "particular", "goods", "goods/services", "qty", "quantity",
            "rate", "amount", "hsn", "sac", "uom", "unit", "discount", "taxable", "value", "code"
    );
    private static final List<String> STRONG_TABLE_HEADER_KEYWORDS = List.of(
            "description", "item", "particular", "goods", "goods/services", "qty", "quantity",
            "rate", "amount", "hsn", "sac", "uom", "discount", "taxable"
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
        String lower = text == null ? "" : text.toLowerCase(Locale.ROOT);
        int hits = 0;
        for (String keyword : ADDRESS_KEYWORDS) {
            if (lower.contains(keyword)) {
                hits++;
            }
        }
        if (lower.matches(".*\\b\\d{5,6}\\b.*")) {
            return true;
        }
        if (lower.contains("@") || lower.contains("www.") || lower.contains("http")) {
            return true;
        }
        if (hits >= 2) {
            return true;
        }
        return hits == 1 && (lower.contains(",") || lower.matches(".*\\d.*"));
    }

    public static boolean isLogisticsLike(String text) {
        return RegexUtil.containsAnyKeyword(text, BUYER_STOP_KEYWORDS) || RegexUtil.containsAnyKeyword(text, LOGISTICS_KEYWORDS);
    }

    public static boolean isGovernmentLike(String text) {
        return RegexUtil.containsAnyKeyword(text, GOVERNMENT_KEYWORDS);
    }

    public static boolean hasBusinessSignal(String text) {
        return RegexUtil.containsAnyKeyword(text, BUSINESS_KEYWORDS);
    }

    public static boolean isHeaderNoise(String text) {
        return RegexUtil.containsAnyKeyword(text, HEADER_NOISE_KEYWORDS);
    }

    public static boolean isVoucherLike(String text) {
        String lower = text == null ? "" : text.toLowerCase(Locale.ROOT);
        return lower.contains("voucher") && !lower.contains("invoice");
    }

    public static boolean looksLikeMeaningfulUppercaseLine(String text) {
        String normalized = RegexUtil.normalizeLine(text);
        if (normalized.isBlank() || normalized.length() < 5 || !normalized.matches(".*[A-Za-z].*")) {
            return false;
        }
        if (isAddressLike(normalized) || isLogisticsLike(normalized) || isHeaderNoise(normalized)) {
            return false;
        }
        int letters = 0;
        int uppercase = 0;
        for (char ch : normalized.toCharArray()) {
            if (Character.isLetter(ch)) {
                letters++;
                if (Character.isUpperCase(ch)) {
                    uppercase++;
                }
            }
        }
        return letters >= 6 && uppercase >= Math.max(4, (letters * 3) / 5);
    }

    public static int countKeywordHits(String text, List<String> keywords) {
        String lower = text == null ? "" : text.toLowerCase(Locale.ROOT);
        int hits = 0;
        for (String keyword : keywords) {
            if (lower.contains(keyword.toLowerCase(Locale.ROOT))) {
                hits++;
            }
        }
        return hits;
    }

    public static boolean isNonItemLine(String text) {
        return RegexUtil.containsAnyKeyword(text, NON_ITEM_KEYWORDS);
    }

    public static boolean isItemStopLine(String text) {
        return RegexUtil.containsAnyKeyword(text, ITEM_STOP_KEYWORDS);
    }

    public static boolean isBuyerSectionHeader(String text) {
        String lower = text == null ? "" : text.toLowerCase(Locale.ROOT);
        if (lower.contains("original for") || lower.contains("duplicate for")
                || lower.contains("triplicate for") || lower.contains("supplier")) {
            return false;
        }
        for (String fragment : fragments(text)) {
            String fragmentLower = fragment.toLowerCase(Locale.ROOT);
            boolean buyerKeyword = RegexUtil.containsAnyKeyword(fragmentLower, BUYER_SECTION_KEYWORDS)
                    || isStandaloneToHeader(fragment);
            if (buyerKeyword && !isOrderOrReferenceHeader(fragmentLower)) {
                return true;
            }
        }
        return false;
    }

    public static boolean looksLikeTableHeader(String text) {
        String lower = text == null ? "" : text.toLowerCase(Locale.ROOT);
        int hits = 0;
        int strongHits = 0;
        for (String keyword : TABLE_HEADER_KEYWORDS) {
            if (lower.contains(keyword)) {
                hits++;
            }
        }
        for (String keyword : STRONG_TABLE_HEADER_KEYWORDS) {
            if (lower.contains(keyword)) {
                strongHits++;
            }
        }
        return hits >= 2 && strongHits >= 2;
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

    private static boolean isStandaloneToHeader(String text) {
        for (String fragment : fragments(text)) {
            String normalized = RegexUtil.normalizeLine(fragment).toLowerCase(Locale.ROOT);
            if (normalized.matches("^to[,:.]?$") || normalized.matches("^invoice to[,:.]?$")) {
                return true;
            }
        }
        return false;
    }

    private static boolean isOrderOrReferenceHeader(String lower) {
        return lower.contains("buyer's order")
                || lower.contains("buyers order")
                || lower.contains("buyer order")
                || lower.contains("purchase order")
                || lower.contains("order no")
                || lower.contains("supplier's ref")
                || lower.contains("supplier ref")
                || lower.contains("dispatch doc")
                || lower.contains("delivery note");
    }
}
