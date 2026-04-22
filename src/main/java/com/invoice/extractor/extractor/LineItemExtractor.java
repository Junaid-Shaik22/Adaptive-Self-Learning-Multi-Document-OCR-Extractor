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
        for (ItemRowCandidate candidate : collectItemRowCandidates(lines, schema)) {
            LineItem item = buildItemFromCandidate(candidate, schema);
            if (item != null && isValidItem(item)) {
                items.add(item);
            }
        }
        return items;
    }

    private List<ItemRowCandidate> collectItemRowCandidates(List<LineIndexingService.IndexedLine> lines,
                                                            TableSchema schema) {
        List<ItemRowCandidate> candidates = new ArrayList<>();
        List<String> pendingDescription = new ArrayList<>();
        ItemRowCandidate current = null;
        for (LineIndexingService.IndexedLine line : lines) {
            String text = line.getText();
            String lower = text == null ? "" : text.toLowerCase();
            if (text == null || text.isBlank()) {
                continue;
            }
            if (shouldStop(lower)) {
                if (current != null) {
                    candidates.add(current);
                }
                break;
            }
            if (isHeaderLike(text, schema)) {
                continue;
            }
            boolean anchorRow = looksLikeAnchorRow(text, pendingDescription);
            if (current != null) {
                if (anchorRow && !isSupplementalContinuationLine(text)) {
                    candidates.add(current);
                    current = new ItemRowCandidate(pendingDescription, text);
                    pendingDescription = new ArrayList<>();
                    continue;
                }
                if (shouldAttachToCurrentRow(text, current)) {
                    current.addContinuation(text);
                    continue;
                }
                String fragment = extractDescriptionFragment(text);
                if (!fragment.isBlank() && isUsefulContinuationFragment(fragment)) {
                    current.addContinuation(fragment);
                    continue;
                }
                if (OcrLayoutUtil.isNonItemLine(lower)) {
                    candidates.add(current);
                    current = null;
                }
                continue;
            }
            if (anchorRow) {
                current = new ItemRowCandidate(pendingDescription, text);
                pendingDescription = new ArrayList<>();
                continue;
            }
            if (OcrLayoutUtil.isNonItemLine(lower)) {
                continue;
            }
            String fragment = extractDescriptionFragment(text);
            if (!fragment.isBlank()) {
                pendingDescription.add(fragment);
            }
        }
        if (current != null) {
            candidates.add(current);
        }
        return candidates;
    }

    private LineItem buildItemFromCandidate(ItemRowCandidate candidate, TableSchema schema) {
        if (candidate == null) {
            return null;
        }
        LineItem structuredItem = buildStructuredItem(candidate, schema);
        if (structuredItem != null && isValidItem(structuredItem)) {
            return structuredItem;
        }
        List<NumericToken> numericTokens = extractNumericTokens(candidate.primaryText());
        if (!looksLikeDataRow(candidate.primaryText(), numericTokens, candidate.descriptionParts)) {
            return null;
        }
        return buildItem(candidate.primaryText(), numericTokens, candidate.descriptionParts, schema);
    }

    private LineItem buildStructuredItem(ItemRowCandidate candidate, TableSchema schema) {
        StructuredRowData structured = extractStructuredRowData(candidate);
        if (structured == null || structured.hsn == null || structured.numericText.isBlank()) {
            return null;
        }
        List<NumericToken> numericTokens = extractNumericTokens(structured.numericText);
        List<NumericToken> working = filterWorkingTokens(structured.numericText, numericTokens, structured.hsn);
        if (working.isEmpty()) {
            return null;
        }
        NumericInference inference = inferNumbers(working, schema, structured.numericText);
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
        if (quantityValue == null && rateValue != null && rateValue > 0 && inference.amount > 0) {
            Double inferredQuantity = inferQuantity(inference.amount, rateValue);
            if (inferredQuantity != null && inferredQuantity <= 100000) {
                quantityValue = inferredQuantity;
            }
        }
        if (rateValue == null && quantityValue != null && quantityValue > 0) {
            rateValue = inference.amount / quantityValue;
        }
        String description = cleanDescriptionText(String.join(" ", structured.descriptionParts));
        if (description.isBlank()) {
            return null;
        }
        LineItem item = new LineItem();
        item.setDescription(description);
        item.setHsn(structured.hsn);
        item.setQuantity(quantityValue == null ? null : AmountUtil.formatAmount(quantityValue));
        item.setUnitPrice(rateValue == null ? null : AmountUtil.formatAmount(rateValue));
        item.setAmount(AmountUtil.formatAmount(inference.amount));
        return item;
    }

    private StructuredRowData extractStructuredRowData(ItemRowCandidate candidate) {
        String hsn = null;
        List<String> descriptionParts = new ArrayList<>(candidate.descriptionParts);
        List<String> numericChunks = new ArrayList<>();
        boolean numericRegion = false;
        for (String line : candidate.allTexts()) {
            if (line == null || line.isBlank()) {
                continue;
            }
            List<String> cells = splitStructuredCells(line);
            if (cells.isEmpty()) {
                if (numericRegion && isSupplementalContinuationLine(line)) {
                    numericChunks.add(line);
                } else if (looksLikePotentialDescriptionFragment(line)) {
                    descriptionParts.add(line);
                }
                continue;
            }
            int hsnIndex = findHsnCellIndex(cells);
            if (hsnIndex >= 0) {
                hsn = extractHsnFromCell(cells.get(hsnIndex));
                numericRegion = true;
                for (int i = 0; i < hsnIndex; i++) {
                    String cell = cleanDescriptionText(cells.get(i));
                    if (cell.isBlank() || cell.matches("^\\d{1,3}$") || !isUsefulContinuationFragment(cell)) {
                        continue;
                    }
                    descriptionParts.add(cell);
                }
                for (int i = hsnIndex + 1; i < cells.size(); i++) {
                    collectStructuredCell(cells.get(i), descriptionParts, numericChunks);
                }
                continue;
            }
            if (numericRegion) {
                for (String cell : cells) {
                    collectStructuredCell(cell, descriptionParts, numericChunks);
                }
            } else {
                for (String cell : cells) {
                    String fragment = cleanDescriptionText(cell);
                    if (!fragment.isBlank()) {
                        descriptionParts.add(fragment);
                    }
                }
            }
        }
        if (hsn == null) {
            return null;
        }
        return new StructuredRowData(hsn, dedupeDescriptionParts(descriptionParts), String.join(" ", numericChunks));
    }

    private void collectStructuredCell(String cell,
                                       List<String> descriptionParts,
                                       List<String> numericChunks) {
        if (cell == null || cell.isBlank()) {
            return;
        }
        if (isPackingCell(cell)) {
            String fragment = cleanDescriptionText(cell);
            if (!fragment.isBlank()) {
                descriptionParts.add(fragment);
            }
            return;
        }
        List<NumericToken> cellTokens = extractNumericTokens(cell);
        if (!cellTokens.isEmpty()) {
            numericChunks.add(cell);
            return;
        }
        String fragment = cleanDescriptionText(cell);
        if (!fragment.isBlank() && !isQuantityOrUnitCell(fragment.toLowerCase())) {
            descriptionParts.add(fragment);
        }
    }

    private List<String> dedupeDescriptionParts(List<String> parts) {
        List<String> deduped = new ArrayList<>();
        for (String part : parts) {
            String cleaned = cleanDescriptionText(part);
            if (cleaned.isBlank()) {
                continue;
            }
            if (deduped.isEmpty() || !deduped.get(deduped.size() - 1).equalsIgnoreCase(cleaned)) {
                deduped.add(cleaned);
            }
        }
        return deduped;
    }

    private int findHsnCellIndex(List<String> cells) {
        for (int i = 0; i < cells.size(); i++) {
            if (extractHsnFromCell(cells.get(i)) != null) {
                return i;
            }
        }
        return -1;
    }

    private String extractHsnFromCell(String cell) {
        return findHsn(cell);
    }

    private boolean isPackingCell(String cell) {
        String lower = cell == null ? "" : cell.toLowerCase();
        return lower.matches(".*\\d+\\s*[xX]\\s*\\d+.*(?:kg|kgs|pcs|nos|set|sets|box|bags?).*")
                || lower.matches(".*\\d+\\s*[xX]\\s*\\d+(?:\\.\\d+)?\\s*[xX].*");
    }

    private boolean looksLikeAnchorRow(String text, List<String> pendingDescription) {
        if (text == null) {
            return false;
        }
        String lower = text.toLowerCase();
        if (shouldStop(lower) || isHeaderLike(text, null) || OcrLayoutUtil.isNonItemLine(lower)) {
            return false;
        }
        List<NumericToken> numericTokens = extractNumericTokens(text);
        String hsn = findHsn(text);
        boolean hasLetters = text.matches(".*[A-Za-z].*");
        boolean hasCurrencyLike = numericTokens.stream()
                .anyMatch(token -> !token.percentToken && AmountUtil.looksLikeCurrencyToken(token.token));
        int significantTokens = countSignificantTokens(numericTokens);
        if (hsn != null) {
            return hasLetters || !pendingDescription.isEmpty() || significantTokens >= 2;
        }
        if (hasCurrencyLike && significantTokens >= 2) {
            return hasLetters || !pendingDescription.isEmpty();
        }
        return !pendingDescription.isEmpty() && significantTokens >= 4 && hasDistinctAmountToken(text, numericTokens);
    }

    private boolean shouldAttachToCurrentRow(String text, ItemRowCandidate current) {
        if (text == null || current == null) {
            return false;
        }
        String lower = text.toLowerCase();
        if (shouldStop(lower) || isHeaderLike(text, null) || OcrLayoutUtil.isNonItemLine(lower)) {
            return false;
        }
        if (isSupplementalContinuationLine(text)) {
            return true;
        }
        if (findHsn(text) != null) {
            return false;
        }
        return looksLikePotentialDescriptionFragment(text);
    }

    private boolean isSupplementalContinuationLine(String text) {
        if (text == null) {
            return false;
        }
        String lower = text.toLowerCase();
        if (shouldStop(lower) || isHeaderLike(text, null) || OcrLayoutUtil.isNonItemLine(lower)) {
            return false;
        }
        if (findHsn(text) != null) {
            return false;
        }
        List<NumericToken> numericTokens = extractNumericTokens(text);
        if (numericTokens.isEmpty()) {
            return false;
        }
        if (numericTokens.size() > 3) {
            return false;
        }
        if (text.matches(".*[A-Za-z].*")) {
            return isQuantityOrUnitCell(lower)
                    || lower.contains("pcs")
                    || lower.contains("nos")
                    || lower.contains("set")
                    || lower.contains("kgs");
        }
        return true;
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
                || lower.contains("amount chargeable") || lower.contains("credit period")
                || lower.contains("tax rate") || lower.contains("taxableamt")
                || lower.contains("taxable amt") || lower.contains("bank details")
                || lower.contains("company's bank details") || lower.contains("our bank details")
                || (lower.contains("rupees") && lower.contains("only"));
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
        NumericInference inference = inferNumbers(working, schema, text);
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

    private boolean isUsefulContinuationFragment(String text) {
        String fragment = cleanDescriptionText(text);
        if (fragment.isBlank() || !fragment.matches(".*[A-Za-z].*")) {
            return false;
        }
        String lower = fragment.toLowerCase();
        if (shouldStop(lower)
                || OcrLayoutUtil.isNonItemLine(lower)
                || OcrLayoutUtil.isAddressLike(lower)
                || OcrLayoutUtil.isLogisticsLike(lower)
                || lower.contains("gstin")
                || lower.contains("invoice")) {
            return false;
        }
        if (badSymbolCount(fragment) > Math.max(2, fragment.length() / 8)) {
            return false;
        }
        int alphaWords = alphaWordCount(fragment);
        int longestAlphaWord = longestAlphaWord(fragment);
        boolean hasDigitOrModelSignal = fragment.matches(".*\\d.*") || fragment.contains("-") || fragment.contains("/");
        return hasDigitOrModelSignal
                || longestAlphaWord >= 6
                || (alphaWords >= 2 && longestAlphaWord >= 5)
                || OcrLayoutUtil.looksLikeMeaningfulUppercaseLine(fragment);
    }

    private int alphaWordCount(String value) {
        int count = 0;
        for (String word : value.split("\\s+")) {
            if (word.replaceAll("[^A-Za-z]", "").length() >= 2) {
                count++;
            }
        }
        return count;
    }

    private int longestAlphaWord(String value) {
        int longest = 0;
        for (String word : value.split("\\s+")) {
            longest = Math.max(longest, word.replaceAll("[^A-Za-z]", "").length());
        }
        return longest;
    }

    private int badSymbolCount(String value) {
        int bad = 0;
        for (char ch : value.toCharArray()) {
            if (!Character.isLetterOrDigit(ch) && !Character.isWhitespace(ch) && "'/-.,:&()".indexOf(ch) < 0) {
                bad++;
            }
        }
        return bad;
    }

    private String cleanDescriptionText(String text) {
        if (text == null) {
            return "";
        }
        String cleaned = text.replace('|', ' ').replace('_', ' ');
        cleaned = cleaned.replaceAll("[;!?\\[\\]{}=]+", " ");
        cleaned = cleaned.replaceAll("\\d+X\\d+", " "); // remove quantity like 5X500
        cleaned = cleaned.replaceFirst("^\\s*\\d+[\\].):,-]*\\s*", "");
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

    private NumericInference inferNumbers(List<NumericToken> working, TableSchema schema, String sourceText) {
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

        NumericInference repeatedAmountInference = inferRepeatedAmountPattern(working);
        if (repeatedAmountInference.amount != null) {
            return repeatedAmountInference;
        }

        NumericInference calculated = inferCalculatedAmount(working, sourceText);
        if (calculated.amount != null) {
            return calculated;
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

    private NumericInference inferRepeatedAmountPattern(List<NumericToken> working) {
        NumericInference inference = new NumericInference();
        List<NumericToken> usable = new ArrayList<>();
        for (NumericToken token : working) {
            if (!token.percentToken && token.value > 0) {
                usable.add(token);
            }
        }
        if (usable.size() < 3) {
            return inference;
        }
        NumericToken first = usable.get(0);
        NumericToken penultimate = usable.get(usable.size() - 2);
        NumericToken last = usable.get(usable.size() - 1);
        if (!AmountUtil.looksLikeCurrencyToken(first.token)
                || !AmountUtil.approximatelyEquals(penultimate.value, last.value)
                || first.value >= last.value) {
            return inference;
        }
        inference.rateToken = first;
        inference.amount = last.value;
        return inference;
    }

    private NumericInference inferCalculatedAmount(List<NumericToken> working, String sourceText) {
        NumericInference inference = new NumericInference();
        NumericToken quantityToken = chooseQuantityToken(working, sourceText);
        if (quantityToken == null) {
            return inference;
        }
        NumericToken rateToken = chooseRateToken(working, quantityToken);
        if (rateToken == null || rateToken.value <= 0) {
            return inference;
        }
        double computedAmount = quantityToken.value * rateToken.value;
        if (computedAmount <= 0) {
            return inference;
        }
        NumericToken approximateAmount = findApproximateAmountToken(working, rateToken, computedAmount);
        if (approximateAmount != null || hasTaxCompanion(working, rateToken, computedAmount)) {
            inference.quantityToken = quantityToken;
            inference.rateToken = rateToken;
            inference.amount = computedAmount;
        }
        return inference;
    }

    private NumericToken chooseQuantityToken(List<NumericToken> working, String sourceText) {
        NumericToken best = null;
        int bestScore = Integer.MIN_VALUE;
        for (int i = 0; i < working.size(); i++) {
            NumericToken token = working.get(i);
            if (token.percentToken || token.value <= 0 || token.value > 100000) {
                continue;
            }
            int score = 0;
            if (isLikelySerialToken(token, sourceText, i)) {
                score -= 50;
            }
            if (hasUnitKeywordNearToken(sourceText, token)) {
                score += 45;
            }
            if (!AmountUtil.looksLikeCurrencyToken(token.token)) {
                score += 18;
            }
            if (Math.rint(token.value) == token.value) {
                score += 8;
            }
            if (token.value >= 1 && token.value <= 10000) {
                score += 12;
            }
            if (i + 1 < working.size() && working.get(i + 1).value > token.value) {
                score += 10;
            }
            if (score > bestScore) {
                bestScore = score;
                best = token;
            }
        }
        return bestScore >= 10 ? best : null;
    }

    private NumericToken chooseRateToken(List<NumericToken> working, NumericToken quantityToken) {
        if (quantityToken == null) {
            return null;
        }
        NumericToken best = null;
        int bestScore = Integer.MIN_VALUE;
        boolean afterQuantity = false;
        for (NumericToken token : working) {
            if (token == quantityToken) {
                afterQuantity = true;
                continue;
            }
            if (!afterQuantity || token.percentToken || token.value <= 0) {
                continue;
            }
            int score = 0;
            if (AmountUtil.looksLikeCurrencyToken(token.token)) {
                score += 24;
            }
            if (token.value > quantityToken.value) {
                score += 14;
            }
            if (token.value < quantityToken.value * 1000) {
                score += 8;
            }
            if (score > bestScore) {
                bestScore = score;
                best = token;
            }
        }
        return bestScore >= 12 ? best : null;
    }

    private NumericToken findApproximateAmountToken(List<NumericToken> working,
                                                    NumericToken rateToken,
                                                    double computedAmount) {
        boolean afterRate = false;
        for (NumericToken token : working) {
            if (token == rateToken) {
                afterRate = true;
                continue;
            }
            if (!afterRate || token.percentToken || token.value <= 0) {
                continue;
            }
            if (Math.abs(token.value - computedAmount) <= Math.max(1.0, computedAmount * 0.10)) {
                return token;
            }
        }
        return null;
    }

    private boolean hasTaxCompanion(List<NumericToken> working,
                                    NumericToken rateToken,
                                    double computedAmount) {
        List<Double> laterValues = new ArrayList<>();
        boolean afterRate = false;
        for (NumericToken token : working) {
            if (token == rateToken) {
                afterRate = true;
                continue;
            }
            if (!afterRate || token.percentToken || token.value <= 0) {
                continue;
            }
            laterValues.add(token.value);
        }
        for (int i = 0; i < laterValues.size(); i++) {
            for (int j = 0; j < laterValues.size(); j++) {
                if (i == j) {
                    continue;
                }
                if (AmountUtil.approximatelyEquals(computedAmount + laterValues.get(i), laterValues.get(j))) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean isLikelySerialToken(NumericToken token, String sourceText, int index) {
        if (token == null || sourceText == null) {
            return false;
        }
        return index == 0
                && token.value <= 10
                && token.token.replaceAll("[^0-9]", "").length() <= 2
                && sourceText.trim().startsWith(token.token);
    }

    private boolean hasUnitKeywordNearToken(String sourceText, NumericToken token) {
        if (sourceText == null || token == null) {
            return false;
        }
        int start = Math.max(0, token.end);
        int end = Math.min(sourceText.length(), token.end + 12);
        String suffix = sourceText.substring(start, end).toLowerCase();
        for (String keyword : UNIT_KEYWORDS) {
            if (suffix.contains(keyword)) {
                return true;
            }
        }
        return false;
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

    private static class ItemRowCandidate {
        private final List<String> descriptionParts;
        private final List<String> rowTexts = new ArrayList<>();

        private ItemRowCandidate(List<String> pendingDescription, String anchorText) {
            this.descriptionParts = new ArrayList<>(pendingDescription);
            this.rowTexts.add(anchorText);
        }

        private void addContinuation(String text) {
            this.rowTexts.add(text);
        }

        private String primaryText() {
            return String.join(" ", rowTexts);
        }

        private List<String> allTexts() {
            return rowTexts;
        }
    }

    private static class StructuredRowData {
        private final String hsn;
        private final List<String> descriptionParts;
        private final String numericText;

        private StructuredRowData(String hsn, List<String> descriptionParts, String numericText) {
            this.hsn = hsn;
            this.descriptionParts = descriptionParts;
            this.numericText = numericText == null ? "" : numericText;
        }
    }
}
