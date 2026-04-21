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
    private static final List<String> UNIT_KEYWORDS = List.of(
            "nos", "no", "pcs", "pc", "set", "sets", "kg", "kgs", "mt", "uom", "unit", "units",
            "box", "boxes", "bag", "bags", "pair", "pairs", "lot", "lots", "job", "jobs"
    );

    @Override
    public List<LineItem> extract(String[] lines, int[] zones) {
        if (lines == null || lines.length == 0) {
            return List.of();
        }
        return extract(LineIndexingService.indexLinesAndZones(String.join(System.lineSeparator(), lines)));
    }

    public List<LineItem> extract(LineIndexingService.Zones zones) {
        if (zones == null) {
            return List.of();
        }

        List<List<LineIndexingService.IndexedLine>> primarySections = resolvePrimarySections(zones);
        List<LineItem> bestItems = findBestItems(primarySections, zones);
        if (!bestItems.isEmpty()) {
            return bestItems;
        }
        return findBestItems(discoverFallbackSections(zones.allLines), zones);
    }

    private List<LineItem> extractFromLines(List<LineIndexingService.IndexedLine> lines, TableSchema schema) {
        List<LineItem> items = new ArrayList<>();
        List<String> pendingDescription = new ArrayList<>();
        for (LineIndexingService.IndexedLine line : lines) {
            String text = line.getText();
            String lower = text.toLowerCase();
            if (shouldStop(lower)) {
                break;
            }
            if (isHeaderLike(text, schema) || OcrLayoutUtil.isNonItemLine(lower)) {
                continue;
            }
            List<NumericToken> numericTokens = extractNumericTokens(text);
            LineItem structuredSingleAmountItem = buildStructuredSingleAmountItem(text, numericTokens, pendingDescription);
            if (structuredSingleAmountItem != null && isValidItem(structuredSingleAmountItem)) {
                items.add(structuredSingleAmountItem);
                pendingDescription.clear();
                continue;
            }
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

    private List<List<LineIndexingService.IndexedLine>> resolvePrimarySections(LineIndexingService.Zones zones) {
        List<List<LineIndexingService.IndexedLine>> sections = new ArrayList<>();
        addSection(sections, zones.tableZone);
        if (zones.getTableHeaderLine() != null) {
            addSection(sections, collectSectionAfterHeader(zones.allLines, zones.getTableHeaderLine().getLineNumber()));
        }
        return sections;
    }

    private List<LineItem> findBestItems(List<List<LineIndexingService.IndexedLine>> sections,
                                         LineIndexingService.Zones zones) {
        List<LineItem> bestItems = List.of();
        int bestScore = Integer.MIN_VALUE;
        for (List<LineIndexingService.IndexedLine> section : sections) {
            List<LineItem> items = extractFromLines(section, detectSchema(zones, section));
            int score = scoreItems(items);
            if (score > bestScore) {
                bestItems = items;
                bestScore = score;
            }
        }
        return bestItems;
    }

    private void addSection(List<List<LineIndexingService.IndexedLine>> sections,
                            List<LineIndexingService.IndexedLine> candidate) {
        if (candidate == null || candidate.isEmpty()) {
            return;
        }
        int startLine = candidate.get(0).getLineNumber();
        int endLine = candidate.get(candidate.size() - 1).getLineNumber();
        for (List<LineIndexingService.IndexedLine> existing : sections) {
            if (!existing.isEmpty()
                    && existing.get(0).getLineNumber() == startLine
                    && existing.get(existing.size() - 1).getLineNumber() == endLine) {
                return;
            }
        }
        sections.add(new ArrayList<>(candidate));
    }

    private List<LineIndexingService.IndexedLine> collectSectionAfterHeader(List<LineIndexingService.IndexedLine> allLines,
                                                                            int headerLineNumber) {
        List<LineIndexingService.IndexedLine> section = new ArrayList<>();
        boolean headerSeen = false;
        for (LineIndexingService.IndexedLine line : allLines) {
            if (!headerSeen) {
                headerSeen = line.getLineNumber() == headerLineNumber;
                continue;
            }
            String lower = line.getText().toLowerCase();
            if (shouldStop(lower)) {
                break;
            }
            section.add(line);
        }
        return section;
    }

    private List<List<LineIndexingService.IndexedLine>> discoverFallbackSections(List<LineIndexingService.IndexedLine> allLines) {
        List<List<LineIndexingService.IndexedLine>> sections = new ArrayList<>();
        for (int i = 0; i < allLines.size(); i++) {
            String text = allLines.get(i).getText();
            String lower = text == null ? "" : text.toLowerCase();
            if (looksLikeSectionHeader(lower)) {
                addSection(sections, collectCandidateSection(allLines, i + 1));
                continue;
            }
            if (looksLikeStandaloneItemCluster(allLines, i)) {
                addSection(sections, collectCandidateSection(allLines, i));
            }
        }
        return sections;
    }

    private boolean looksLikeSectionHeader(String lower) {
        if (OcrLayoutUtil.looksLikeTableHeader(lower)) {
            return true;
        }
        return OcrLayoutUtil.countKeywordHits(lower, OcrLayoutUtil.TABLE_HEADER_KEYWORDS) >= 3
                && OcrLayoutUtil.countKeywordHits(lower, List.of("description", "item", "qty", "quantity", "rate", "amount", "hsn", "sac")) >= 2;
    }

    private boolean looksLikeStandaloneItemCluster(List<LineIndexingService.IndexedLine> allLines, int index) {
        if (index < 0 || index >= allLines.size() || !isPotentialItemRow(allLines.get(index).getText())) {
            return false;
        }
        int nextIndex = index + 1;
        while (nextIndex < allLines.size() && allLines.get(nextIndex).getText().isBlank()) {
            nextIndex++;
        }
        if (nextIndex < allLines.size() && isPotentialItemRow(allLines.get(nextIndex).getText())) {
            return true;
        }
        // Also consider if the current line has enough signals
        String text = allLines.get(index).getText();
        List<NumericToken> tokens = extractNumericTokens(text);
        return hasDistinctAmountToken(text, tokens) && tokens.size() >= 1;
    }

    private List<LineIndexingService.IndexedLine> collectCandidateSection(List<LineIndexingService.IndexedLine> allLines, int startIndex) {
        List<LineIndexingService.IndexedLine> section = new ArrayList<>();
        for (int i = startIndex; i < allLines.size(); i++) {
            String text = allLines.get(i).getText();
            if (text == null || text.isBlank()) {
                if (!section.isEmpty()) {
                    break;
                }
                continue;
            }
            String lower = text.toLowerCase();
            if (shouldStop(lower) || OcrLayoutUtil.isItemStopLine(lower)) {
                if (!section.isEmpty()) {
                    break;
                }
                continue;
            }
            if (isHeaderLike(text, null)) {
                continue;
            }
            if (section.isEmpty() && !isPotentialItemRow(text) && !looksLikePotentialDescriptionFragment(text)) {
                continue;
            }
            if (OcrLayoutUtil.isNonItemLine(lower) && !isPotentialItemRow(text)) {
                if (!section.isEmpty()) {
                    break;
                }
                continue;
            }
            section.add(allLines.get(i));
            if (section.size() >= 40) {
                break;
            }
        }
        return section;
    }

    private boolean looksLikePotentialDescriptionFragment(String text) {
        String fragment = extractDescriptionFragment(text);
        if (fragment.isBlank()) {
            return false;
        }
        String lower = fragment.toLowerCase();
        return !OcrLayoutUtil.isNonItemLine(lower) && !shouldStop(lower);
    }

    private boolean isPotentialItemRow(String text) {
        if (text == null || !text.matches(".*[A-Za-z].*")) {
            return false;
        }
        String lower = text.toLowerCase();
        if (shouldStop(lower) || OcrLayoutUtil.isNonItemLine(lower)) {
            return false;
        }
        if (lower.contains("gstin") || lower.contains("gst no") || lower.contains("invoice no")
                || lower.contains("invoice date")) {
            return false;
        }
        if (RegexUtil.containsAnyKeyword(lower, OcrLayoutUtil.HEADER_METADATA_KEYWORDS)
                || RegexUtil.containsAnyKeyword(lower, OcrLayoutUtil.BUYER_STOP_KEYWORDS)
                || RegexUtil.containsAnyKeyword(lower, OcrLayoutUtil.LOGISTICS_KEYWORDS)) {
            return false;
        }
        List<NumericToken> numericTokens = extractNumericTokens(text);
        if (numericTokens.size() < 2 || countSignificantTokens(numericTokens) > 8) {
            return false;
        }
        boolean hasCurrencyLikeAmount = hasDistinctAmountToken(text, numericTokens);
        String hsn = findHsn(text);
        return hasCurrencyLikeAmount || (hsn != null && numericTokens.size() >= 1);
    }

    private int scoreItems(List<LineItem> items) {
        if (items == null || items.isEmpty()) {
            return Integer.MIN_VALUE;
        }
        int score = items.size() * 100;
        for (LineItem item : items) {
            if (item.getQuantity() != null) {
                score += 10;
            }
            if (item.getUnitPrice() != null) {
                score += 10;
            }
            if (item.getHsn() != null) {
                score += 5;
            }
        }
        return score;
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
        boolean hasDistinctAmount = hasDistinctAmountToken(text, numericTokens);
        if (numericTokens.size() >= 2 && hasDistinctAmount) {
            return true;
        }
        if (!pendingDescription.isEmpty() && hasDistinctAmount) {
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
        if (quantityValue != null && rateValue != null && !AmountUtil.approximatelyEquals(quantityValue * rateValue, inference.amount)) {
            Double inferredQuantity = inferQuantity(inference.amount, rateValue);
            if (inferredQuantity != null) {
                quantityValue = inferredQuantity;
            } else if (quantityValue > 0) {
                rateValue = inference.amount / quantityValue;
            }
        }
        // If quantity is still null and rate is set, try to infer quantity
        if (quantityValue == null && rateValue != null && rateValue > 0 && inference.amount > 0) {
            Double inferredQ = inferQuantity(inference.amount, rateValue);
            if (inferredQ != null && inferredQ <= 100000) { // reasonable quantity limit
                quantityValue = inferredQ;
            }
        }
        // If rate is null and quantity is set, calculate rate
        if (rateValue == null && quantityValue != null && quantityValue > 0) {
            rateValue = inference.amount / quantityValue;
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

    private LineItem buildStructuredSingleAmountItem(String text,
                                                     List<NumericToken> numericTokens,
                                                     List<String> pendingDescription) {
        if (text == null || !text.contains("|") || numericTokens == null || numericTokens.size() < 1) {
            return null;
        }
        String hsn = findHsn(text);
        String description = extractDescriptionFromStructuredRow(text, hsn);
        if (description.isBlank()) {
            return null;
        }
        NumericToken amountToken = null;
        for (int i = numericTokens.size() - 1; i >= 0; i--) {
            NumericToken token = numericTokens.get(i);
            String digits = token.token.replaceAll("[^0-9]", "");
            if (token.percentToken || digits.equals(hsn) || !AmountUtil.looksLikeCurrencyToken(token.token)) {
                continue;
            }
            amountToken = token;
            break;
        }
        if (amountToken == null || amountToken.value <= 0) {
            return null;
        }
        List<String> parts = new ArrayList<>(pendingDescription);
        parts.add(description);

        LineItem item = new LineItem();
        item.setDescription(cleanDescriptionText(String.join(" ", parts)));
        item.setHsn(hsn);
        item.setAmount(AmountUtil.formatAmount(amountToken.value));
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
        String normalized = cleanDescriptionText(text).replaceFirst("^\\s*\\d+[\\].)]?\\s*", "");
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
        String structuredDescription = extractDescriptionFromStructuredRow(text, hsn);
        if (!structuredDescription.isBlank()) {
            return structuredDescription;
        }
        String cleaned = text;
        cleaned = cleaned.replaceFirst("^\\s*\\d+\\s+", " ");
        if (hsn != null) {
            cleaned = cleaned.replaceFirst("\\b" + Pattern.quote(hsn) + "\\b", " ");
        }
        for (NumericToken token : numericTokens) {
            cleaned = cleaned.replaceFirst(Pattern.quote(token.token), " ");
        }
        return cleanDescriptionText(cleaned);
    }

    private boolean hasDistinctAmountToken(String text, List<NumericToken> numericTokens) {
        if (numericTokens == null || numericTokens.isEmpty()) {
            return false;
        }
        boolean currencyLike = numericTokens.stream()
                .anyMatch(token -> !token.percentToken && AmountUtil.looksLikeCurrencyToken(token.token));
        if (currencyLike) {
            return true;
        }
        String hsn = findHsn(text);
        if (hsn != null && numericTokens.size() >= 4) {
            NumericToken trailing = numericTokens.get(numericTokens.size() - 1);
            if (!trailing.percentToken && trailing.value > 0) {
                String digits = trailing.token.replaceAll("[^0-9]", "");
                if (!hsn.equals(digits)) {
                    return true;
                }
            }
        }
        for (int i = Math.max(0, numericTokens.size() - 2); i < numericTokens.size(); i++) {
            NumericToken token = numericTokens.get(i);
            if (token.percentToken || token.value < AmountUtil.MIN_SIGNIFICANT_AMOUNT) {
                continue;
            }
            String digits = token.token.replaceAll("[^0-9]", "");
            if (hsn != null && hsn.equals(digits)) {
                continue;
            }
            return true;
        }
        return false;
    }

    private String extractDescriptionFromStructuredRow(String text, String hsn) {
        List<String> cells = splitStructuredCells(text);
        if (cells.isEmpty()) {
            return "";
        }
        List<String> descriptionParts = new ArrayList<>();
        boolean started = false;
        for (String cell : cells) {
            String normalized = cleanDescriptionText(cell);
            if (normalized.isBlank()) {
                continue;
            }
            if (normalized.matches("^\\d{1,3}$")) {
                if (!started) {
                    continue;
                }
                break;
            }
            String lower = normalized.toLowerCase();
            if (isHeaderLike(normalized, null) || shouldStop(lower) || OcrLayoutUtil.isNonItemLine(lower)) {
                break;
            }
            if (hsn != null && normalized.replaceAll("[^0-9]", "").equals(hsn)) {
                break;
            }
            if (isQuantityOrUnitCell(lower) || isNumericLikeCell(normalized)) {
                if (started) {
                    break;
                }
                continue;
            }
            descriptionParts.add(normalized);
            started = true;
        }
        return cleanDescriptionText(String.join(" ", descriptionParts));
    }

    private List<String> splitStructuredCells(String text) {
        List<String> cells = new ArrayList<>();
        if (text == null || !text.contains("|")) {
            return cells;
        }
        for (String raw : text.split("\\|+")) {
            String normalized = RegexUtil.normalizeLine(raw);
            if (!normalized.isBlank()) {
                cells.add(normalized);
            }
        }
        return cells;
    }

    private boolean isNumericLikeCell(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        String compact = value.replaceAll("[^0-9.,%]", "");
        if (compact.isBlank()) {
            return false;
        }
        return compact.length() >= Math.max(1, value.length() - 2);
    }

    private boolean isQuantityOrUnitCell(String lower) {
        if (lower == null || lower.isBlank()) {
            return false;
        }
        if (UNIT_KEYWORDS.contains(lower)) {
            return true;
        }
        return lower.matches("(?:x\\s*)?\\d+(?:\\.\\d+)?\\s*(?:pcs|pc|nos|no|set|sets|kg|kgs|mt|unit|units)?");
    }

    private String cleanDescriptionText(String text) {
        if (text == null) {
            return "";
        }
        String cleaned = text.replace('|', ' ').replace('_', ' ');
        cleaned = cleaned.replaceAll("\\d+X\\d+", " "); // remove quantity like 5X500
        cleaned = cleaned.replaceAll("(?i)\\b(?:hsn|sac|qty|quantity|rate|amount|uom|unit price|taxable value)\\b", " ");
        cleaned = cleaned.replaceAll("\\s{2,}", " ").trim();
        cleaned = RegexUtil.normalizeLine(cleaned);
        cleaned = cleaned.replaceFirst("^[,./:&()\\-]+", "").replaceFirst("[,./:&()\\-]+$", "").trim();
        return cleaned;
    }


    private TableSchema detectSchema(LineIndexingService.Zones zones, List<LineIndexingService.IndexedLine> section) {
        StringBuilder header = new StringBuilder();
        if (zones.getTableHeaderLine() != null) {
            header.append(zones.getTableHeaderLine().getText()).append(' ');
        }
        List<LineIndexingService.IndexedLine> sample = section == null ? List.of() : section;
        for (int i = 0; i < Math.min(2, sample.size()); i++) {
            String lower = sample.get(i).getText().toLowerCase();
            if (OcrLayoutUtil.looksLikeTableHeader(lower) || lower.contains("amount") || lower.contains("qty") || lower.contains("hsn")) {
                header.append(sample.get(i).getText()).append(' ');
            }
        }
        String combined = header.toString().toLowerCase();
        TableSchema schema = new TableSchema();
        schema.hasTaxableValue = combined.contains("taxable");
        return schema;
    }

    private boolean isValidItem(LineItem item) {
        Double amount = AmountUtil.parseAmount(item.getAmount());
        if (amount == null || amount <= 0) {
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
        if (!hasStructuredLineItemSignal(item)) {
            return false;
        }
        return item.getDescription().split("\\s+").length >= 2;
    }

    private boolean hasStructuredLineItemSignal(LineItem item) {
        Double quantity = AmountUtil.parseAmount(item.getQuantity());
        Double unitPrice = AmountUtil.parseAmount(item.getUnitPrice());
        String description = item.getDescription() == null ? "" : RegexUtil.normalizeLine(item.getDescription());
        boolean descriptionStartsWithLetter = description.matches("[A-Za-z].*");
        return unitPrice != null && unitPrice > 0
                || item.getHsn() != null
                || quantity != null && quantity > 0 && descriptionStartsWithLetter;
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
