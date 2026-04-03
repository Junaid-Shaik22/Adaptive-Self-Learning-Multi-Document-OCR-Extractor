package com.invoice.extractor.util;

import com.invoice.extractor.model.InvoiceData;
import java.util.Map;

public class ConfidenceCalculator {
    public static double calculate(InvoiceData data, Map<String, String> extractionMethod) {
        double score = 0;
        score += getScore(extractionMethod.get("invoiceNumber")) * 0.12;
        score += getScore(extractionMethod.get("invoiceDate")) * 0.10;
        score += getScore(extractionMethod.get("gstin")) * 0.14;
        score += getScore(extractionMethod.get("buyerGstin")) * 0.14;
        score += getScore(extractionMethod.get("total")) * 0.14;
        score += getScore(extractionMethod.get("subtotal")) * 0.08;
        score += getScore(extractionMethod.get("tax")) * 0.08;
        score += getScore(extractionMethod.get("buyer")) * 0.08;
        score += getScore(extractionMethod.get("vendor")) * 0.08;
        score += getScore(extractionMethod.get("lineItems")) * 0.08;

        Double subtotal = AmountUtil.parseAmount(data.getSubTotal());
        Double tax = AmountUtil.parseAmount(data.getTaxAmount());
        Double total = AmountUtil.parseAmount(data.getTotalAmount());
        if (subtotal != null && tax != null && total != null && AmountUtil.approximatelyEquals(subtotal + tax, total)) {
            score += 0.06;
        }
        return Math.min(1.0, score);
    }

    private static double getScore(String method) {
        if (method == null) {
            return 0.0;
        }
        if (method.contains("template")) {
            return 1.0;
        }
        double score = 0.0;
        if (method.contains("keyword")) {
            score = 0.82;
        } else if (method.contains("regex")) {
            score = 0.64;
        } else if (method.contains("fallback")) {
            score = 0.35;
        }
        if (method.contains("zone")) {
            score += 0.08;
        }
        return Math.min(1.0, score);
    }
}
