package com.invoice.extractor.extractor;

import com.invoice.extractor.model.LineItem;
import com.invoice.extractor.service.impl.LineIndexingService;
import com.invoice.extractor.util.AmountUtil;
import com.invoice.extractor.util.OcrLayoutUtil;
import com.invoice.extractor.util.RegexUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class LineItemExtractor implements FieldExtractor<List<LineItem>> {
    private static final Pattern HSN_PATTERN = Pattern.compile("(?<![\\d.,])\\d{4,8}(?![\\d.,])");
    private static final Pattern NUMERIC_PATTERN = Pattern.compile("(?<![A-Za-z])(\\d{1,3}(?:,\\d{2,3})+(?:\\.\\d{1,4})?|\\d+(?:\\.\\d{1,4})?)(?![A-Za-z])");

    @Override
    public List<LineItem> extract(String[] lines, int[] zones) {
        return null;
    }

    public List<LineItem> extract(LineIndexingService.Zones zones) {
        TableSchema schema = detectSchema(zones);
        List<LineItem> items = new ArrayList<>();
        List<String> pendingDescription = new ArrayList<>();
        for (LineIndexingService.IndexedLine line : zones.tableZone) {
            String text = line.getText();
            String lower = text.toLowerCase();
            if (shouldStop(lower)) {
                break;
            }
            if (isHeaderLike(text, schema) || OcrLayoutUtil.isNonItemLine(lower)) {
                continue;
            }
            List<NumericToken> numericTokens = extractNumericTokens(text);
            if (!looksLikeDataRow(text, numericTokens, pendingDescription)) {
                String fragment = extractDescriptionFragment(text);
                if (!fragment.isBlank()) {
                    pendingDescription.add(fragment);
                }
                continue;
            }
            LineItem item = buildItem(text, numericTokens, pendingDescription, schema);
            if (item != null && isValidItem(item)) {
                items.add(item);
            }
            pendingDescription.clear();
        }
        return items;
    }

    private boolean shouldStop(String lower) {
        if (OcrLayoutUtil.isItemStopLine(lower)) {
            return true;
        }
        // Catch common total section labels that might be missed by generic stop lines
        return lower.contains("teal") || lower.contains("total tax amount") || lower.contains("grand total")
                || lower.equals("total") || lower.contains("seal nos") || lower.contains("remarks")
                || lower.contains("amount chargeable") || lower.contains("credit period");
    }

    private boolean isHeaderLike(String text, TableSchema schema) {
        String lower = text == null ? "" : text.toLowerCase();
        if (OcrLayoutUtil.looksLikeTableHeader(lower)) {
            return true;
        }
        return schema != null && (lower.contains("hsn") || lower.contains("qty") || lower.contains("rate") || lower.contains("amount")) && AmountUtil.extractRawNumericTokens(lower).isEmpty();
    }

    private boolean looksLikeDataRow(String text, List<NumericToken> numericTokens, List<String> pendingDescription) {
        String lower = text == null ? "" : text.toLowerCase();
        if (text == null || shouldStop(lower) || OcrLayoutUtil.isNonItemLine(lower)) {
            return false;
        }
        if (countSignificantTokens(numericTokens) > 8) {
            return false;
        }
        // Avoid selecting total/tax lines as data rows, even if they have numeric tokens
        if (lower.contains("total") || lower.contains("tax amount") || lower.contains("igst") || lower.contains("cgst") || lower.contains("sgst")) {
            return false;
        }
        boolean hasLikelyAmount = numericTokens.stream().anyMatch(this::isLikelyAmountToken);
        if (numericTokens.size() >= 2 && hasLikelyAmount) {
            return true;
        }
        if (!pendingDescription.isEmpty() && hasLikelyAmount) {
            return true;
        }
        return false;
    }

    private LineItem buildItem(String text,
                               List<NumericToken> numericTokens,
                               List<String> pendingDescription,
                               TableSchema schema) {
        if (numericTokens.isEmpty()) {
            return null;
        }
        String hsn = findHsn(text);
        List<NumericToken> working = filterWorkingTokens(text, numericTokens, hsn);
        if (working.isEmpty()) {
            return null;
        }
        NumericInference inference = inferNumbers(working, schema);
        if (inference.amount == null || inference.amount <= 0) {
            return null;
        }
        Double quantityValue = inference.quantityToken == null ? null : inference.quantityToken.value;
        Double rateValue = inference.rateToken == null ? null : inference.rateToken.value;
        if (quantityValue != null && quantityValue > inference.amount) {
            quantityValue = null;
        }
        if (rateValue != null && rateValue > inference.amount) {
            rateValue = null;
        }
        if (quantityValue == null && rateValue != null) {
            quantityValue = inferQuantity(inference.amount, rateValue);
        }
        if (rateValue == null && quantityValue != null && quantityValue > 0 && quantityValue <= inference.amount) {
            rateValue = inference.amount / quantityValue;
        }
        if (quantityValue != null && rateValue != null && !AmountUtil.approximatelyEquals(quantityValue * rateValue, inference.amount)) {
            Double inferredQuantity = inferQuantity(inference.amount, rateValue);
            if (inferredQuantity != null) {
                quantityValue = inferredQuantity;
            } else if (quantityValue > 0) {
                rateValue = inference.amount / quantityValue;
            }
        }
        String quantity = quantityValue == null ? null : AmountUtil.formatAmount(quantityValue);
        String rate = rateValue == null ? null : AmountUtil.formatAmount(rateValue);

        String inlineDescription = extractDescriptionFromRow(text, numericTokens, hsn);
        List<String> parts = new ArrayList<>(pendingDescription);
        if (!inlineDescription.isBlank()) {
            parts.add(inlineDescription);
        }
        String description = RegexUtil.normalizeLine(String.join(" ", parts));
        if (description.isBlank()) {
            return null;
        }

        LineItem item = new LineItem();
        item.setDescription(description);
        item.setHsn(hsn);
        item.setQuantity(quantity);
        item.setUnitPrice(rate);
        item.setAmount(AmountUtil.formatAmount(inference.amount));
        return item;
    }

    private List<NumericToken> extractNumericTokens(String text) {
        List<NumericToken> tokens = new ArrayList<>();
        if (text == null) {
            return tokens;
        }
        String normalizedText = text.replaceAll(",\\s*\\.", ".");
        Matcher matcher = NUMERIC_PATTERN.matcher(normalizedText);
        while (matcher.find()) {
            String token = matcher.group(1);
            Double value = AmountUtil.parseAmount(token);
            if (value != null) {
                tokens.add(new NumericToken(token, value, matcher.end(), isPercentToken(text, matcher.end())));
            }
        }
        return tokens;
    }

    private String findHsn(String text) {
        Matcher matcher = HSN_PATTERN.matcher(text);
        if (matcher.find()) {
            return matcher.group();
        }
        return null;
    }

    private String extractDescriptionFragment(String text) {
        String normalized = RegexUtil.normalizeLine(text).replaceFirst("^\\s*\\d+[\\].)]?\\s*", "");
        String lower = normalized.toLowerCase();
        if (normalized.isBlank() || OcrLayoutUtil.isNonItemLine(lower) || OcrLayoutUtil.isItemStopLine(lower)) {
            return "";
        }
        if (!normalized.matches(".*[A-Za-z].*")) {
            return "";
        }
        return normalized;
    }

    private String extractDescriptionFromRow(String text, List<NumericToken> numericTokens, String hsn) {
        String cleaned = text;
        cleaned = cleaned.replaceFirst("^\\s*\\d+\\s+", " ");
        if (hsn != null) {
            cleaned = cleaned.replaceFirst("\\b" + Pattern.quote(hsn) + "\\b", " ");
        }
        for (NumericToken token : numericTokens) {
            cleaned = cleaned.replaceFirst(Pattern.quote(token.token), " ");
        }
        return RegexUtil.normalizeLine(cleaned);
    }


    private TableSchema detectSchema(LineIndexingService.Zones zones) {
        StringBuilder header = new StringBuilder();
        if (zones.getTableHeaderLine() != null) {
            header.append(zones.getTableHeaderLine().getText()).append(' ');
        }
        for (int i = 0; i < Math.min(2, zones.tableZone.size()); i++) {
            String lower = zones.tableZone.get(i).getText().toLowerCase();
            if (OcrLayoutUtil.looksLikeTableHeader(lower) || lower.contains("amount") || lower.contains("qty") || lower.contains("hsn")) {
                header.append(zones.tableZone.get(i).getText()).append(' ');
            }
        }
        String combined = header.toString().toLowerCase();
        TableSchema schema = new TableSchema();
        schema.hasTaxableValue = combined.contains("taxable");
        return schema;
    }

    private boolean isValidItem(LineItem item) {
        Double amount = AmountUtil.parseAmount(item.getAmount());
        if (amount == null || amount < 100) {
            return false;
        }
        if (item.getDescription() == null || item.getDescription().isBlank() || !item.getDescription().matches(".*[A-Za-z].*")) {
            return false;
        }
        String lower = item.getDescription().toLowerCase();
        if (OcrLayoutUtil.isNonItemLine(lower) || OcrLayoutUtil.isItemStopLine(lower)) {
            return false;
        }
        if (lower.contains("pin code") || lower.contains("state code") || lower.contains("gstin")
                || lower.contains("place of supply") || lower.contains("hyderabad")
                || lower.contains("seal nos") || lower.contains("remarks")
                || lower.contains("credit period") || lower.contains("gross")
                || lower.contains("tare") || lower.contains("nett")) {
            return false;
        }
        if (item.getQuantity() == null && item.getUnitPrice() == null) {
            return false;
        }
        return item.getDescription().split("\\s+").length >= 1;
    }

    private List<NumericToken> filterWorkingTokens(String text, List<NumericToken> numericTokens, String hsn) {
        List<NumericToken> working = new ArrayList<>();
        for (int i = 0; i < numericTokens.size(); i++) {
            NumericToken token = numericTokens.get(i);
            String digits = token.token.replaceAll("[^0-9]", "");
            if (hsn != null && hsn.equals(digits)) {
                continue;
            }
            if (i == 0 && text.trim().startsWith(token.token) && digits.length() <= 2 && token.value <= 10) {
                continue;
            }
            working.add(token);
        }
        return working;
    }

    private NumericInference inferNumbers(List<NumericToken> working, TableSchema schema) {
        NumericInference inference = new NumericInference();
        double bestScore = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < working.size(); i++) {
            NumericToken quantity = working.get(i);
            if (quantity.percentToken || quantity.value <= 0) {
                continue;
            }
            for (int j = i + 1; j < working.size(); j++) {
                NumericToken rate = working.get(j);
                if (rate.percentToken || rate.value <= 0) {
                    continue;
                }
                for (int k = j + 1; k < working.size(); k++) {
                    NumericToken amount = working.get(k);
                    if (amount.percentToken || amount.value <= 0) {
                        continue;
                    }
                    if (!AmountUtil.approximatelyEquals(quantity.value * rate.value, amount.value)) {
                        continue;
                    }
                    double score = k * 10;
                    if (quantity.value <= rate.value) {
                        score += 10;
                    }
                    if (schema.hasTaxableValue && amount.value >= rate.value) {
                        score += 5;
                    }
                    if (score > bestScore) {
                        bestScore = score;
                        inference.quantityToken = quantity;
                        inference.rateToken = rate;
                        inference.amount = amount.value;
                    }
                }
            }
        }

        if (inference.amount != null) {
            return inference;
        }

        NumericToken amountToken = chooseFallbackAmount(working);
        if (amountToken == null) {
            return inference;
        }
        inference.amount = amountToken.value;
        List<NumericToken> others = new ArrayList<>();
        for (NumericToken token : working) {
            if (token != amountToken && !token.percentToken) {
                others.add(token);
            }
        }
        if (!others.isEmpty()) {
            inference.quantityToken = others.get(0);
        }
        if (others.size() > 1) {
            inference.rateToken = others.get(1);
        }
        return inference;
    }

    private NumericToken chooseFallbackAmount(List<NumericToken> working) {
        NumericToken bestCurrency = null;
        for (int i = working.size() - 1; i >= 0; i--) {
            NumericToken token = working.get(i);
            if (token.percentToken || token.value <= 0 || !AmountUtil.looksLikeCurrencyToken(token.token)) {
                continue;
            }
            if (bestCurrency == null || token.end > bestCurrency.end || token.value > bestCurrency.value) {
                bestCurrency = token;
            }
        }
        if (bestCurrency != null) {
            return bestCurrency;
        }
        NumericToken best = null;
        for (int i = working.size() - 1; i >= 0; i--) {
            NumericToken token = working.get(i);
            if (token.percentToken || token.value <= 0) {
                continue;
            }
            if (best == null || token.value > best.value || token.end > best.end) {
                best = token;
            }
        }
        return best;
    }

    private int countSignificantTokens(List<NumericToken> numericTokens) {
        int count = 0;
        for (NumericToken token : numericTokens) {
            if (token.percentToken) {
                continue;
            }
            if (isLikelyAmountToken(token) || token.token.replaceAll("[^0-9]", "").length() >= 4) {
                count++;
            }
        }
        return count;
    }

    private boolean isLikelyAmountToken(NumericToken token) {
        if (token == null || token.percentToken || token.value <= 0) {
            return false;
        }
        return AmountUtil.looksLikeCurrencyToken(token.token) || token.value >= AmountUtil.MIN_SIGNIFICANT_AMOUNT;
    }

    private Double inferQuantity(double amount, double rate) {
        if (rate <= 0 || rate > amount) {
            return null;
        }
        double quantity = amount / rate;
        double rounded = Math.rint(quantity);
        if (rounded <= 0 || rounded > 100000) {
            return null;
        }
        return Math.abs(quantity - rounded) <= 0.05 ? rounded : null;
    }

    private boolean isPercentToken(String text, int tokenEnd) {
        int index = tokenEnd;
        while (index < text.length() && Character.isWhitespace(text.charAt(index))) {
            index++;
        }
        return index < text.length() && text.charAt(index) == '%';
    }

    private static class NumericToken {
        private final String token;
        private final double value;
        private final int end;
        private final boolean percentToken;

        private NumericToken(String token, double value, int end, boolean percentToken) {
            this.token = token;
            this.value = value;
            this.end = end;
            this.percentToken = percentToken;
        }
    }

    private static class TableSchema {
        private boolean hasTaxableValue;
    }

    private static class NumericInference {
        private NumericToken quantityToken;
        private NumericToken rateToken;
        private Double amount;
    }
}
