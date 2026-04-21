package com.invoice.extractor.util;

import com.invoice.extractor.model.InvoiceData;

import java.util.Map;

public class ConfidenceCalculator {
    public static double calculate(InvoiceData data, Map<String, String> extractionMethod) {
        double score = 0;
        score += weightedMethodScore(extractionMethod.get("vendor"), 0.16);
        score += weightedMethodScore(extractionMethod.get("gstin"), 0.12);
        score += weightedMethodScore(extractionMethod.get("buyer"), 0.08);
        score += weightedMethodScore(extractionMethod.get("buyerGstin"), 0.08);
        score += weightedMethodScore(extractionMethod.get("invoiceNumber"), 0.12);
        score += weightedMethodScore(extractionMethod.get("invoiceDate"), 0.08);
        score += weightedMethodScore(extractionMethod.get("total"), 0.16);
        score += weightedMethodScore(extractionMethod.get("subtotal"), 0.06);
        score += weightedMethodScore(extractionMethod.get("tax"), 0.06);
        score += weightedMethodScore(extractionMethod.get("lineItems"), 0.08);

        score += formatQualityScore(data);
        score += mathQualityScore(data);
        score -= conflictPenalty(data);
        score = Math.min(score, qualityCap(data));

        return clamp(score, 0.0, 1.0);
    }

    private static double weightedMethodScore(String method, double weight) {
        return getMethodScore(method) * weight;
    }

    private static double getMethodScore(String method) {
        if (method == null) {
            return 0.0;
        }
        if (method.contains("template")) {
            return 1.0;
        }
        double score = 0.0;
        if (method.contains("priority")) {
            score = 0.92;
        } else if (method.contains("keyword")) {
            score = 0.84;
        } else if (method.contains("regex")) {
            score = 0.66;
        } else if (method.contains("fallback")) {
            score = 0.34;
        }
        if (method.contains("zone")) {
            score += 0.10;
        }
        return Math.min(1.0, score);
    }

    private static double formatQualityScore(InvoiceData data) {
        double score = 0.0;
        if (looksValidInvoiceNumber(data.getInvoiceNumber())) {
            score += 0.05;
        }
        if (DateUtil.isValidInvoiceDate(data.getInvoiceDate())) {
            score += 0.04;
        }
        if (RegexUtil.isValidGstin(data.getVendorGstin())) {
            score += 0.05;
        }
        if (RegexUtil.isValidGstin(data.getBuyerGstin())) {
            score += 0.04;
        }
        if (looksMeaningfulName(data.getVendorName(), true)) {
            score += 0.05;
        }
        if (looksMeaningfulName(data.getBuyerName(), false)) {
            score += 0.03;
        }
        if (hasUsefulAddress(data.getBuyerAddress())) {
            score += 0.02;
        }
        if (AmountUtil.parseAmount(data.getTotalAmount()) != null) {
            score += 0.04;
        }
        if (AmountUtil.parseAmount(data.getSubTotal()) != null) {
            score += 0.02;
        }
        if (AmountUtil.parseAmount(data.getTaxAmount()) != null) {
            score += 0.02;
        }
        if (data.getLineItems() != null && !data.getLineItems().isEmpty()) {
            score += 0.03;
        }
        if (!sameValue(data.getVendorAddress(), data.getBuyerAddress()) && hasUsefulAddress(data.getVendorAddress())) {
            score += 0.02;
        }
        return score;
    }

    private static double mathQualityScore(InvoiceData data) {
        Double subtotal = AmountUtil.parseAmount(data.getSubTotal());
        Double tax = AmountUtil.parseAmount(data.getTaxAmount());
        Double total = AmountUtil.parseAmount(data.getTotalAmount());
        if (subtotal != null && tax != null && total != null && AmountUtil.approximatelyEquals(subtotal + tax, total)) {
            return 0.12;
        }
        if (total != null && subtotal != null && subtotal < total) {
            return 0.03;
        }
        return 0.0;
    }

    private static double conflictPenalty(InvoiceData data) {
        double penalty = 0.0;
        if (!looksMeaningfulName(data.getVendorName(), true)) {
            penalty += data.getVendorName() == null ? 0.16 : 0.12;
        }
        if (!RegexUtil.isValidGstin(data.getVendorGstin())) {
            penalty += data.getVendorGstin() == null ? 0.14 : 0.10;
        }
        if (!looksMeaningfulName(data.getBuyerName(), false)) {
            penalty += data.getBuyerName() == null ? 0.20 : 0.16;
            if (!hasUsefulAddress(data.getBuyerAddress())) {
                penalty += 0.10;
            }
        }
        if (!RegexUtil.isValidGstin(data.getBuyerGstin())) {
            penalty += data.getBuyerGstin() == null ? 0.10 : 0.06;
        }
        if (data.getBuyerAddress() != null && data.getBuyerAddress().matches(".*\\b(?:po no|purchase order|invoice no|gstin)\\b.*")) {
            penalty += 0.12;
        }
        if (data.getVendorAddress() != null && data.getVendorAddress().matches(".*\\b(?:po no|purchase order|invoice no|gstin)\\b.*")) {
            penalty += 0.12;
        }
        if (AmountUtil.parseAmount(data.getTotalAmount()) == null) {
            penalty += 0.16;
        }
        if (!looksValidInvoiceNumber(data.getInvoiceNumber())) {
            penalty += data.getInvoiceNumber() == null ? 0.12 : 0.10;
        }
        if (sameValue(data.getVendorGstin(), data.getBuyerGstin())) {
            penalty += 0.12;
        }
        if (sameValue(data.getVendorName(), data.getBuyerName())) {
            penalty += 0.08;
        }
        if (sameValue(data.getVendorAddress(), data.getBuyerAddress())) {
            penalty += 0.14;
        }
        if (AmountUtil.parseAmount(data.getSubTotal()) == null && AmountUtil.parseAmount(data.getTaxAmount()) != null) {
            penalty += 0.05;
        }
        Double subtotal = AmountUtil.parseAmount(data.getSubTotal());
        Double tax = AmountUtil.parseAmount(data.getTaxAmount());
        Double total = AmountUtil.parseAmount(data.getTotalAmount());
        if (subtotal != null && tax != null && total != null && !AmountUtil.approximatelyEquals(subtotal + tax, total)) {
            penalty += 0.16;
        }
        if (total != null && tax != null && total <= tax) {
            penalty += 0.18;
        }
        return penalty;
    }

    private static double qualityCap(InvoiceData data) {
        double cap = 1.0;
        Double subtotal = AmountUtil.parseAmount(data.getSubTotal());
        Double tax = AmountUtil.parseAmount(data.getTaxAmount());
        Double total = AmountUtil.parseAmount(data.getTotalAmount());

        if (!looksValidInvoiceNumber(data.getInvoiceNumber()) || !DateUtil.isValidInvoiceDate(data.getInvoiceDate())) {
            cap = Math.min(cap, 0.82);
        }
        if (!looksMeaningfulName(data.getVendorName(), true) || !RegexUtil.isValidGstin(data.getVendorGstin())) {
            cap = Math.min(cap, 0.78);
        }
        if (!looksMeaningfulName(data.getBuyerName(), false) && !hasUsefulAddress(data.getBuyerAddress())) {
            cap = Math.min(cap, 0.72);
        }
        if (sameValue(data.getVendorAddress(), data.getBuyerAddress())) {
            cap = Math.min(cap, 0.70);
        }
        if (total == null) {
            cap = Math.min(cap, 0.60);
        }
        if (total != null && ((tax != null && total <= tax) || (subtotal != null && total <= subtotal))) {
            cap = Math.min(cap, 0.65);
        }
        if (subtotal != null && tax != null && total != null && !AmountUtil.approximatelyEquals(subtotal + tax, total)) {
            cap = Math.min(cap, 0.74);
        }
        if ((data.getBuyerAddress() != null && data.getBuyerAddress().matches(".*\\b(?:po no|purchase order|invoice no|gstin)\\b.*"))
                || (data.getVendorAddress() != null && data.getVendorAddress().matches(".*\\b(?:po no|purchase order|invoice no|gstin)\\b.*"))) {
            cap = Math.min(cap, 0.68);
        }
        return cap;
    }

    private static boolean looksValidInvoiceNumber(String value) {
        if (value == null) {
            return false;
        }
        String normalized = RegexUtil.repairInvoiceNumberCandidate(value);
        if (!RegexUtil.INVOICE_NUMBER_TOKEN_PATTERN.matcher(normalized).matches()
                || DateUtil.isValidInvoiceDate(normalized)) {
            return false;
        }
        return normalized.contains("/")
                || normalized.contains("-")
                || normalized.matches("^\\d{3,12}$")
                || normalized.matches("^[A-Z]{1,4}\\d{2,10}[A-Z]?$");
    }

    private static boolean looksMeaningfulName(String value, boolean vendor) {
        if (value == null || !value.matches(".*[A-Za-z].*") || value.matches("^\\d+$")) {
            return false;
        }
        String lower = value.toLowerCase();
        if (OcrLayoutUtil.isHeaderNoise(lower) || OcrLayoutUtil.isLogisticsLike(lower)) {
            return false;
        }
        if (vendor && OcrLayoutUtil.isGovernmentLike(lower)) {
            return false;
        }
        if (lower.contains("gstin") || lower.contains("invoice no") || lower.contains("dated")) {
            return false;
        }
        return true;
    }

    private static boolean sameValue(String left, String right) {
        return left != null && right != null && RegexUtil.normalizeForComparison(left).equals(RegexUtil.normalizeForComparison(right));
    }

    private static boolean hasUsefulAddress(String value) {
        if (value == null || !value.matches(".*[A-Za-z].*")) {
            return false;
        }
        String lower = value.toLowerCase();
        return OcrLayoutUtil.isAddressLike(lower) || lower.contains("hyderabad") || lower.contains("road") || lower.matches(".*\\b\\d{6}\\b.*");
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
