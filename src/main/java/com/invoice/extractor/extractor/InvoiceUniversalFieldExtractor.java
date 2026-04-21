package com.invoice.extractor.extractor;

import com.invoice.extractor.model.InvoiceData;
import com.invoice.extractor.model.InvoiceOcrDocument;
import com.invoice.extractor.util.AmountUtil;
import com.invoice.extractor.util.DateUtil;
import com.invoice.extractor.util.OcrLayoutUtil;
import com.invoice.extractor.util.RegexUtil;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class InvoiceUniversalFieldExtractor {
    private static final Pattern EMAIL_PATTERN = Pattern.compile("(?i)\\b[\\w.%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}\\b");
    private static final Pattern WEBSITE_PATTERN = Pattern.compile("(?i)\\b(?:https?://|www\\.)?[A-Z0-9.-]+\\.(?:com|in|org|net|co\\.in|biz|info)\\b");
    private static final Pattern PHONE_PATTERN = Pattern.compile("(?<!\\d)(?:\\+?91[-\\s]?)?(?:[6-9]\\d{9}|\\d{3,5}[-\\s]?\\d{6,8})(?!\\d)");
    private static final Pattern PAN_PATTERN = Pattern.compile("\\b[A-Z]{5}\\d{4}[A-Z]\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern CIN_PATTERN = Pattern.compile("\\b[LU]\\d{5}[A-Z]{2}\\d{4}[A-Z]{3}\\d{6}\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern MSME_PATTERN = Pattern.compile("(?i)\\b(?:UDYAM|MSME|UAM)\\s*(?:NO|NUMBER)?\\s*[:=-]?\\s*([A-Z0-9-]{6,30})");
    private static final Pattern IFSC_PATTERN = Pattern.compile("\\b[A-Z]{4}0[A-Z0-9]{6}\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern ACCOUNT_PATTERN = Pattern.compile("\\b\\d[\\d\\-\\s]{6,22}\\d\\b");
    private static final Pattern PINCODE_PATTERN = Pattern.compile("\\b\\d{6}\\b");
    private static final Pattern STATE_PATTERN = Pattern.compile("(?i)\\bstate(?:\\s*name)?\\s*[:=-]?\\s*([A-Z][A-Z .,&/-]{2,})");
    private static final Pattern STATE_CODE_PATTERN = Pattern.compile("(?i)\\b(?:state\\s*code|code)\\s*[:=-]?\\s*(\\d{1,2})\\b");
    private static final Pattern LABELED_ACCOUNT_PATTERN = Pattern.compile("(?i)(?:a/c(?:\\s*no)?|account(?:\\s*(?:no|number))?)\\s*[:=-]?\\s*([0-9A-Z\\-\\s]{6,30})");
    private static final Pattern LABELED_IFSC_PATTERN = Pattern.compile("(?i)(?:ifsc(?:\\s*code)?|ifs\\s*code|branch\\s*&\\s*ifs\\s*code)\\s*[:=-]?\\s*([A-Z0-9]{8,20})");
    private static final Set<String> TRANSPORT_MODE_KEYWORDS = Set.of(
            "road", "by road", "canter", "truck", "lorry", "air", "sea", "rail", "courier", "express"
    );
    private static final Set<String> PUBLIC_EMAIL_HOSTS = Set.of(
            "gmail.com", "rediffmail.com", "yahoo.com", "yahoo.co.in", "hotmail.com", "outlook.com"
    );

    private static final List<String> KNOWN_LABELS = List.of(
            "place of supply", "transporter name", "dispatched through", "dispatch through", "terms of payment",
            "payment terms", "delivery note", "vehicle number", "vehicle no", "purchase order", "po date",
            "order reference", "other references", "invoice no", "invoice date", "bank details", "bank name",
            "account number", "account no", "ifsc", "branch", "round off", "grand total", "amount payable",
            "invoice value", "inv value", "inv value (in fig)", "invoice amt", "total amount after tax",
            "mode/terms", "mode / terms", "transportation mode", "mode", "terms of delivery", "phone", "tel",
            "party e-mail id", "party mobile no", "email", "website",
            "taxable value", "taxable amt", "cgst", "sgst", "igst", "irn", "ack no", "e-way bill", "eway bill",
            "msme", "udyam", "cin", "pan", "gstin", "state name", "state code", "state", "pincode", "pin code",
            "destination", "customer po no", "customer po", "bill to", "billed to", "buyer", "ship to",
            "shipped to", "consignee", "transporter nm", "gr/rr no", "gr.no", "station",
            "description", "hsn", "qty", "quantity", "rate", "amount"
    );

    public Result extract(InvoiceOcrDocument document, InvoiceData context) {
        List<String> allLines = toLines(document == null ? "" : document.getCombinedText());
        List<String> firstLines = toLines(document == null ? "" : document.getFirstPageText());
        List<String> lastLines = toLines(document == null ? "" : document.getLastPageText());
        List<String> financeLines = lastLines.isEmpty() ? allLines : lastLines;
        if (financeLines.size() < 10 && !lastLines.isEmpty()) {
            financeLines = allLines;
        }
        List<String> vendorLines = extractVendorHeaderLines(firstLines);

        Result result = new Result();
        result.poDate = extractDateValue(allLines, List.of("purchase order dated", "po date", "order date"));
        result.orderReference = firstNonBlank(
                extractValue(allLines, List.of("order reference", "other reference", "other references")),
                extractValue(allLines, List.of("reference code", "reference"))
        );
        result.deliveryNote = extractValue(allLines, List.of("delivery note"));
        result.dispatchThrough = extractDispatchThrough(allLines);
        result.transporterName = firstNonBlank(extractTransporterName(allLines), normalizeTransport(result.dispatchThrough));
        result.transportDetails = firstNonBlank(
                extractTransportDetails(allLines),
                result.transporterName,
                result.dispatchThrough
        );
        result.destination = extractValue(allLines, List.of("destination"));
        result.placeOfSupply = extractValue(allLines, List.of("place of supply"));

        String bankChunk = firstNonBlank(extractBankChunk(financeLines), extractBankChunk(allLines));
        result.bankName = extractBankName(bankChunk, allLines);
        result.accountNumber = extractAccountNumber(allLines, bankChunk);
        result.ifscCode = extractIfscCode(allLines, bankChunk);
        result.branch = extractValue(allLines, List.of("branch"));

        result.vendorPhone = joinMatches(vendorLines, PHONE_PATTERN);
        result.vendorEmail = firstMatch(vendorLines, EMAIL_PATTERN);
        result.vendorWebsite = firstWebsite(vendorLines);
        result.vendorAddress = extractVendorAddress(firstLines, context);
        result.buyerAddress = extractBuyerAddress(firstLines, context);
        result.vendorPAN = extractVendorPan(vendorLines, context);
        result.vendorCIN = firstMatch(vendorLines, CIN_PATTERN);
        result.msmeNumber = firstNonBlank(extractPatternGroup(vendorLines, MSME_PATTERN), extractPatternGroup(allLines, MSME_PATTERN));
        result.state = extractState(allLines);
        result.stateCode = extractStateCode(allLines, context);
        result.pincode = firstNonBlank(extractByPattern(result.buyerAddress, PINCODE_PATTERN), extractByPattern(result.vendorAddress, PINCODE_PATTERN), extractByPattern(String.join(" ", allLines), PINCODE_PATTERN));
        if (result.placeOfSupply == null) {
            result.placeOfSupply = result.state;
        }

        result.taxableValue = firstNonBlank(
                extractAmount(financeLines, List.of("taxable value", "taxable amt", "sale amt", "amount chargeable"), true),
                context == null ? null : context.getSubTotal()
        );
        result.cgst = extractTaxComponentAmount(financeLines, List.of("cgst"));
        result.sgst = extractTaxComponentAmount(financeLines, List.of("sgst"));
        result.igst = extractTaxComponentAmount(financeLines, List.of("igst", "integrated tax"));
        result.roundOff = extractAmount(financeLines, List.of("round off", "roundoff", "rounding off"), false);
        return result;
    }

    private List<String> toLines(String text) {
        List<String> lines = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return lines;
        }
        for (String raw : text.split("\\n")) {
            String normalized = normalize(raw);
            if (!normalized.isBlank()) {
                lines.add(normalized);
            }
        }
        return lines;
    }

    private String extractDateValue(List<String> lines, List<String> labels) {
        String value = extractValue(lines, labels);
        if (value != null) {
            for (String candidate : DateUtil.findCandidateDates(value)) {
                if (DateUtil.isValidInvoiceDate(candidate)) {
                    return candidate;
                }
            }
        }
        return null;
    }

    private String extractValue(List<String> lines, List<String> labels) {
        String best = null;
        int bestScore = Integer.MIN_VALUE;
        for (int index = 0; index < lines.size(); index++) {
            String line = lines.get(index);
            String lower = line.toLowerCase(Locale.ROOT);
            for (String label : labels) {
                String key = label.toLowerCase(Locale.ROOT);
                if (!lower.contains(key)) {
                    continue;
                }
                String sameLine = line.replaceFirst("(?i).*?" + Pattern.quote(key) + "\\s*(?:no|number|date|name|dated|details)?\\s*(?:[:=#>|-]+\\s*)*", "");
                StringBuilder builder = new StringBuilder(trimNextLabel(normalize(sameLine), labels));
                for (int i = index + 1; i < lines.size() && i <= index + 3; i++) {
                    if (isBoundary(lines.get(i), labels)) {
                        break;
                    }
                    if (builder.length() > 0) {
                        builder.append(' ');
                    }
                    builder.append(trimNextLabel(lines.get(i), labels));
                }
                String candidate = normalize(builder.toString());
                if (candidate.isBlank() || looksLikeLabeledNoise(candidate, labels)) {
                    continue;
                }
                candidate = trimBusinessBoundary(candidate);
                if (candidate.isBlank() || looksLikeLabeledNoise(candidate, labels)) {
                    continue;
                }
                int score = scoreExtractedValue(candidate, lower, key);
                if (score > bestScore) {
                    best = candidate;
                    bestScore = score;
                }
            }
        }
        return best;
    }

    private boolean isBoundary(String line, List<String> currentLabels) {
        String lower = line.toLowerCase(Locale.ROOT);
        if (lower.contains("description") && (lower.contains("qty") || lower.contains("amount"))) {
            return true;
        }
        if (lower.matches("^\\d+$") || lower.matches(".*\\b\\d{1,2}[-/]\\d{1,2}[-/]\\d{2,4}\\b.*")) {
            return true;
        }
        for (String label : KNOWN_LABELS) {
            if (currentLabels.contains(label)) {
                continue;
            }
            if (lower.contains(label)) {
                return true;
            }
        }
        return false;
    }

    private String trimNextLabel(String value, List<String> currentLabels) {
        String lower = value.toLowerCase(Locale.ROOT);
        int cut = lower.length();
        for (String label : KNOWN_LABELS) {
            if (currentLabels.contains(label)) {
                continue;
            }
            int index = lower.indexOf(label);
            if (index > 0 && index < cut) {
                cut = index;
            }
        }
        return normalize(cut < lower.length() ? value.substring(0, cut) : value);
    }

    private String extractBankChunk(List<String> lines) {
        for (int i = 0; i < lines.size(); i++) {
            if (!looksLikeBankSectionStart(lines.get(i))) {
                continue;
            }
            StringBuilder builder = new StringBuilder(lines.get(i));
            for (int j = i + 1; j < lines.size() && j <= i + 7; j++) {
                if (isBankSectionStop(lines.get(j))) {
                    break;
                }
                builder.append(' ').append(lines.get(j));
            }
            return normalize(builder.toString());
        }
        return null;
    }

    private String extractBankName(String bankChunk, List<String> lines) {
        String byLine = extractBankNameFromLines(lines);
        if (byLine != null) {
            return byLine;
        }
        String labeled = normalizeBankNameCandidate(extractLabeledOrFollowingValue(lines, List.of("bank name", "bank name.", "bank tame")));
        if (labeled != null) {
            return labeled;
        }
        if (bankChunk == null) {
            return null;
        }
        return normalizeBankNameCandidate(bankChunk);
    }

    private String extractAccountNumber(List<String> lines, String bankChunk) {
        String best = null;
        int bestScore = -1;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            String candidate = extractAccountFromLine(line);
            if (candidate != null) {
                int score = scoreAccountCandidate(candidate, line);
                if (score > bestScore) {
                    bestScore = score;
                    best = candidate;
                }
            }
            String lower = line.toLowerCase(Locale.ROOT);
            if (lower.contains("account") || lower.contains("a/c")) {
                for (int j = i + 1; j < lines.size() && j <= i + 5; j++) {
                    if (isBankSectionStop(lines.get(j))) {
                        break;
                    }
                    candidate = extractAccountFromLine(lines.get(j));
                    if (candidate != null) {
                        int score = scoreAccountCandidate(candidate, lines.get(j)) + 10;
                        if (score > bestScore) {
                            bestScore = score;
                            best = candidate;
                        }
                    }
                }
            }
        }
        if (bestScore >= 30) {
            return best;
        }
        String chunkCandidate = extractByPattern(bankChunk, ACCOUNT_PATTERN);
        if (chunkCandidate != null) {
            int score = scoreAccountCandidate(chunkCandidate, bankChunk) + 20;
            if (score > bestScore) {
                best = chunkCandidate;
            }
        }
        return best;
    }

    private int scoreAccountCandidate(String value, String context) {
        if (value == null) return 0;
        int score = 0;
        String numeric = cleanNumeric(value);
        int len = numeric.length();
        if (len >= 11 && len <= 16) {
            score += 40;
        } else if (len >= 8 && len <= 10) {
            score += 15;
        } else if (len > 16) {
            score += 25;
        }
        if (context != null) {
            String lower = context.toLowerCase(Locale.ROOT);
            if (lower.contains("account") || lower.contains("a/c") || lower.contains("acc no")) {
                score += 50;
            }
        }
        return score;
    }

    private String cleanNumeric(String value) {
        if (value == null) {
            return "";
        }
        return value.replaceAll("[^0-9]", "");
    }

    private String extractIfscCode(List<String> lines, String bankChunk) {
        for (int i = 0; i < lines.size(); i++) {
            String candidate = extractIfscFromLine(lines.get(i));
            if (candidate != null) {
                return candidate;
            }
            String lower = lines.get(i).toLowerCase(Locale.ROOT);
            if (lower.contains("ifsc") || lower.contains("ifs code")) {
                for (int j = i + 1; j < lines.size() && j <= i + 5; j++) {
                    if (isBankSectionStop(lines.get(j))) {
                        break;
                    }
                    candidate = extractIfscFromLine(lines.get(j));
                    if (candidate != null) {
                        return candidate;
                    }
                }
            }
        }
        return extractIfscFromLine(bankChunk);
    }

    private String extractLabeledOrFollowingValue(List<String> lines, List<String> labels) {
        String sameLine = extractValue(lines, labels);
        if (sameLine != null) {
            return sameLine;
        }
        for (int i = 0; i < lines.size(); i++) {
            String lower = lines.get(i).toLowerCase(Locale.ROOT);
            for (String label : labels) {
                if (!lower.contains(label.toLowerCase(Locale.ROOT))) {
                    continue;
                }
                for (int j = i + 1; j < lines.size() && j <= i + 5; j++) {
                    if (isBankSectionStop(lines.get(j))) {
                        break;
                    }
                    String candidate = trimNextLabel(lines.get(j), labels);
                    if (!candidate.isBlank()) {
                        return candidate;
                    }
                }
            }
        }
        return null;
    }

    private List<String> extractVendorHeaderLines(List<String> firstLines) {
        List<String> vendorLines = new ArrayList<>();
        for (String line : firstLines) {
            String lower = line.toLowerCase(Locale.ROOT);
            if (lower.contains("bill to")
                    || lower.contains("billed to")
                    || lower.contains("buyer")
                    || lower.contains("ship to")
                    || lower.contains("shipped to")
                    || lower.contains("consignee")
                    || lower.contains("description")) {
                break;
            }
            vendorLines.add(line);
        }
        return vendorLines;
    }

    private String extractVendorAddress(List<String> firstLines, InvoiceData context) {
        int start = findLine(firstLines, context == null ? null : context.getVendorName());
        if (start < 0) {
            start = 0;
        }
        return collectAddress(firstLines, start + 1, List.of("gstin", "invoice no", "tax invoice", "bill to", "buyer", "ship to", "shipped to", "consignee"));
    }

    private String extractBuyerAddress(List<String> firstLines, InvoiceData context) {
        int start = findAny(firstLines, List.of("bill to", "billed to", "buyer (bill to)", "ship to", "shipped to", "consignee"));
        if (start < 0) {
            return null;
        }
        String buyerName = context == null ? "" : normalize(context.getBuyerName());
        List<String> collected = new ArrayList<>();
        String inline = extractBuyerInlineValue(firstLines.get(start));
        if (looksLikeBuyerText(inline, buyerName)) {
            collected.add(stripContactNoise(inline));
        }
        for (int i = start + 1; i < firstLines.size() && collected.size() < 8; i++) {
            String rawLine = firstLines.get(i);
            if (hasLargeSpacingBreak(rawLine)) {
                break;
            }
            String line = buyerSideOfLine(rawLine);
            String lower = line.toLowerCase(Locale.ROOT);
            if (line.isBlank()) {
                if (isBuyerSectionStop(rawLine.toLowerCase(Locale.ROOT)) && !collected.isEmpty()) {
                    break;
                }
                continue;
            }
            if (isBuyerSectionStop(lower)) {
                break;
            }
            if (isInvalidAddressPayload(line)) {
                if (!collected.isEmpty()) {
                    break;
                }
                continue;
            }
            if (!buyerName.isBlank() && RegexUtil.normalizeForComparison(line).equals(RegexUtil.normalizeForComparison(buyerName))) {
                continue;
            }
            boolean organizationLine = isLikelyOrganizationLine(line);
            boolean addressLine = isLikelyAddressLine(line) || looksLikeBuyerText(line, buyerName);
            if (!organizationLine && !addressLine && !collected.isEmpty()) {
                break;
            }
            if (organizationLine || addressLine) {
                String cleaned = stripContactNoise(trimBusinessBoundary(line));
                if (!cleaned.isBlank()) {
                    collected.add(cleaned);
                }
                if (containsPincode(cleaned)) {
                    break;
                }
            }
        }
        return normalize(String.join(", ", collected));
    }

    private String collectAddress(List<String> lines, int startIndex, List<String> stopLabels) {
        List<String> collected = new ArrayList<>();
        for (int i = Math.max(0, startIndex); i < lines.size() && collected.size() < 5; i++) {
            String rawLine = lines.get(i);
            if (hasLargeSpacingBreak(rawLine)) {
                break;
            }
            String lower = rawLine.toLowerCase(Locale.ROOT);
            boolean stop = false;
            for (String label : stopLabels) {
                if (lower.contains(label)) {
                    stop = true;
                    break;
                }
            }
            if (stop) {
                break;
            }
            if (isAddressBoundaryLine(lower)) {
                break;
            }
            if (isInvalidAddressPayload(rawLine)) {
                if (!collected.isEmpty()) {
                    break;
                }
                continue;
            }
            if (!isLikelyAddressLine(rawLine) && !collected.isEmpty()) {
                break;
            }
            if (isLikelyAddressLine(rawLine)) {
                String cleaned = stripContactNoise(trimBusinessBoundary(rawLine));
                if (!cleaned.isBlank()) {
                    collected.add(cleaned);
                }
                if (containsPincode(cleaned)) {
                    break;
                }
            }
        }
        return normalize(String.join(", ", collected));
    }

    private boolean isBuyerSectionStop(String lower) {
        return lower.contains("gstin")
                || lower.contains("description")
                || lower.contains("qty")
                || lower.contains("hsn")
                || lower.contains("amount")
                || lower.contains("dispatch")
                || lower.contains("delivery note")
                || lower.contains("purchase order")
                || lower.contains("po no")
                || lower.contains("invoice no")
                || lower.contains("bank details")
                || lower.contains("bank name")
                || lower.contains("grand total")
                || lower.contains("taxable value");
    }

    private boolean isLikelyOrganizationLine(String line) {
        if (line == null || line.isBlank()) {
            return false;
        }
        String lower = line.toLowerCase(Locale.ROOT);
        if (lower.contains("invoice no") || lower.contains("invoice date") || lower.contains("gstin") || lower.contains("bank")) {
            return false;
        }
        return lower.startsWith("m/s")
                || lower.contains("department")
                || lower.contains("directorate")
                || lower.contains("stores")
                || lower.contains("officer")
                || lower.contains("manager")
                || lower.contains("materials")
                || lower.contains("nuclear")
                || lower.contains("fuel")
                || lower.contains("complex")
                || lower.contains("unit")
                || lower.contains("plant");
    }

    private String extractVendorPan(List<String> firstLines, InvoiceData context) {
        String labeled = extractValue(firstLines, List.of("pan"));
        String panSearchSpace = firstNonBlank(labeled, String.join(" ", firstLines));
        String byPattern = panSearchSpace == null ? null : firstMatch(List.of(panSearchSpace), PAN_PATTERN);
        if (byPattern != null) {
            return byPattern;
        }
        String vendorGstin = context == null ? null : context.getVendorGstin();
        if (vendorGstin != null && RegexUtil.isValidGstin(vendorGstin)) {
            return vendorGstin.substring(2, 12);
        }
        return null;
    }

    private String extractState(List<String> lines) {
        for (String line : lines) {
            Matcher matcher = STATE_PATTERN.matcher(line);
            if (matcher.find()) {
                String raw = matcher.group(1);
                // Strip trailing label text like "Place of Supply", "Code", etc.
                raw = raw.replaceAll("(?i)\\b(?:code|place\\s+of\\s+supply|state\\s*code|pin\\s*code)\\b.*$", "");
                // Strip trailing punctuation and digits that look like state codes
                raw = raw.replaceAll("\\s*[-:,;.]+\\s*\\d{0,2}\\s*$", "");
                String cleaned = normalize(raw);
                return cleaned.isBlank() ? null : cleaned;
            }
        }
        return null;
    }

    private String extractStateCode(List<String> lines, InvoiceData context) {
        for (String line : lines) {
            Matcher matcher = STATE_CODE_PATTERN.matcher(line);
            if (matcher.find()) {
                return String.format("%02d", Integer.parseInt(matcher.group(1)));
            }
        }
        String buyerGstin = context == null ? null : context.getBuyerGstin();
        if (buyerGstin != null && RegexUtil.isValidGstin(buyerGstin)) {
            return buyerGstin.substring(0, 2);
        }
        return null;
    }

    private String extractAmount(List<String> lines, List<String> labels, boolean preferLargest) {
        Double best = null;
        for (String line : lines) {
            String lower = line.toLowerCase(Locale.ROOT);
            boolean matches = false;
            String relevantLine = line;
            for (String label : labels) {
                int index = lower.indexOf(label.toLowerCase(Locale.ROOT));
                if (index >= 0) {
                    matches = true;
                    relevantLine = line.substring(index);
                    break;
                }
            }
            if (!matches) {
                continue;
            }
            List<String> rawTokens = AmountUtil.extractRawNumericTokens(relevantLine);
            boolean lineHasCurrencyToken = rawTokens.stream().anyMatch(AmountUtil::looksLikeCurrencyToken);
            for (String token : rawTokens) {
                Double value = AmountUtil.parseAmount(token);
                if (value == null) {
                    continue;
                }
                if (lineHasCurrencyToken && !AmountUtil.looksLikeCurrencyToken(token)) {
                    continue;
                }
                if (relevantLine.contains("%") && value <= 100.0) {
                    continue;
                }
                if (best == null || (preferLargest && value > best) || (!preferLargest && Math.abs(value) < Math.abs(best))) {
                    best = value;
                }
            }
        }
        return best == null ? null : AmountUtil.formatAmount(best);
    }

    private String extractTaxComponentAmount(List<String> lines, List<String> labels) {
        Double best = null;
        int bestScore = Integer.MIN_VALUE;
        for (String line : lines) {
            String lower = line.toLowerCase(Locale.ROOT);
            int labelIndex = -1;
            for (String label : labels) {
                int index = lower.indexOf(label.toLowerCase(Locale.ROOT));
                if (index >= 0) {
                    labelIndex = index;
                    break;
                }
            }
            if (labelIndex < 0) {
                continue;
            }
            String relevantLine = line.substring(labelIndex);
            Double candidate = null;
            for (String token : AmountUtil.extractRawNumericTokens(relevantLine)) {
                Double value = AmountUtil.parseAmount(token);
                if (value == null) {
                    continue;
                }
                if (relevantLine.contains("%") && value <= 100.0) {
                    continue;
                }
                candidate = value;
            }
            if (candidate == null) {
                continue;
            }
            int score = 0;
            score += labelIndex == 0 ? 20 : 0;
            score += lower.contains("taxable") || lower.contains("grand total") || lower.contains("amount payable") ? -40 : 0;
            score += relevantLine.contains("%") ? 10 : 0;
            if (score > bestScore) {
                best = candidate;
                bestScore = score;
            }
        }
        return best == null ? null : AmountUtil.formatAmount(best);
    }

    private String joinMatches(List<String> lines, Pattern pattern) {
        Set<String> values = new LinkedHashSet<>();
        for (String line : lines) {
            Matcher matcher = pattern.matcher(line);
            while (matcher.find()) {
                values.add(normalize(matcher.group()));
            }
        }
        return values.isEmpty() ? null : String.join(", ", values);
    }

    private String firstMatch(List<String> lines, Pattern pattern) {
        for (String line : lines) {
            if (line == null) {
                continue;
            }
            Matcher matcher = pattern.matcher(line);
            if (matcher.find()) {
                return normalize(matcher.group());
            }
        }
        return null;
    }

    private String firstWebsite(List<String> lines) {
        for (String line : lines) {
            String normalizedLine = normalize(line);
            int wwwIndex = normalizedLine.toLowerCase(Locale.ROOT).indexOf("www.");
            if (wwwIndex >= 0) {
                Matcher wwwMatcher = WEBSITE_PATTERN.matcher(normalizedLine.substring(wwwIndex));
                if (wwwMatcher.find()) {
                    String candidate = normalize(wwwMatcher.group());
                    if (isPlausibleWebsite(candidate)) {
                        return candidate;
                    }
                }
            }
            Matcher matcher = WEBSITE_PATTERN.matcher(line);
            while (matcher.find()) {
                String value = matcher.group();
                if (!value.contains("@") && !PUBLIC_EMAIL_HOSTS.contains(value.toLowerCase(Locale.ROOT))
                        && isPlausibleWebsite(value)) {
                    return normalize(value);
                }
            }
        }
        return null;
    }

    /**
     * Reject garbled OCR text that happens to match the website pattern.
     * A plausible website should have segments of vowel-consonant text,
     * not long stretches of random characters.
     */
    private boolean isPlausibleWebsite(String website) {
        if (website == null || website.isBlank()) {
            return false;
        }
        // Extract the domain part (before .com/.in etc.)
        String domain = website.toLowerCase(Locale.ROOT)
                .replaceFirst("(?i)^(?:https?://|www\\.)", "")
                .replaceFirst("\\.[a-z]{2,4}(\\.[a-z]{2,3})?$", "");
        if (domain.isBlank()) {
            return false;
        }
        // Count consecutive consonants — garbled text has long consonant chains
        int maxConsonantRun = 0;
        int currentRun = 0;
        for (char ch : domain.toCharArray()) {
            if (Character.isLetter(ch) && "aeiou".indexOf(ch) < 0) {
                currentRun++;
                maxConsonantRun = Math.max(maxConsonantRun, currentRun);
            } else {
                currentRun = 0;
            }
        }
        // Garbled domains like "hpsersaedoticeDmysoreammoana" have very long stretches
        return maxConsonantRun <= 5 && domain.length() <= 40;
    }

    private String extractPatternGroup(List<String> lines, Pattern pattern) {
        for (String line : lines) {
            Matcher matcher = pattern.matcher(line);
            if (matcher.find()) {
                return normalize(matcher.group(1));
            }
        }
        return null;
    }

    private String extractByPattern(String text, Pattern pattern) {
        if (text == null) {
            return null;
        }
        Matcher matcher = pattern.matcher(text.toUpperCase(Locale.ROOT));
        return matcher.find() ? normalize(matcher.group()) : null;
    }

    private int findLine(List<String> lines, String value) {
        if (value == null || value.isBlank()) {
            return -1;
        }
        String target = RegexUtil.normalizeForComparison(value);
        for (int i = 0; i < lines.size(); i++) {
            if (RegexUtil.normalizeForComparison(lines.get(i)).contains(target)) {
                return i;
            }
        }
        return -1;
    }

    private int findAny(List<String> lines, List<String> labels) {
        for (int i = 0; i < lines.size(); i++) {
            String lower = lines.get(i).toLowerCase(Locale.ROOT);
            for (String label : labels) {
                if (lower.contains(label)) {
                    return i;
                }
            }
        }
        return -1;
    }

    private String normalizeTransport(String value) {
        if (value == null) {
            return null;
        }
        String lower = value.toLowerCase(Locale.ROOT);
        return (lower.contains("transport") || lower.contains("logistics") || lower.contains("roadlines")
                || lower.contains("roadways") || lower.contains("courier") || lower.contains("cargo")
                || lower.contains("travels") || TRANSPORT_MODE_KEYWORDS.contains(lower)) ? value : null;
    }

    private String extractDispatchThrough(List<String> lines) {
        return firstNonBlank(
                normalizeTransportCandidate(extractValue(lines, List.of("dispatched through", "dispatch through")), false),
                extractTransportMode(lines)
        );
    }

    private String extractTransportMode(List<String> lines) {
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            String lower = line.toLowerCase(Locale.ROOT);
            if (lower.contains("mode/terms")) {
                continue;
            }
            if (!(lower.contains("transportation mode") || lower.matches(".*\\bmode\\b.*"))) {
                continue;
            }
            if (!looksLikeTransportModeContext(lines, i)) {
                continue;
            }
            String sameLine = line.replaceFirst("(?i).*?\\b(?:transportation\\s+mode|mode)\\b\\s*(?:[:=#>|-]+\\s*)*", "");
            String candidate = normalizeTransportCandidate(sameLine, false);
            if (candidate != null) {
                return candidate;
            }
            for (int j = i + 1; j < lines.size() && j <= i + 2; j++) {
                candidate = normalizeTransportCandidate(lines.get(j), false);
                if (candidate != null) {
                    return candidate;
                }
            }
        }
        return null;
    }

    private boolean looksLikeTransportModeContext(List<String> lines, int index) {
        for (int i = Math.max(0, index - 1); i <= Math.min(lines.size() - 1, index + 1); i++) {
            String lower = lines.get(i).toLowerCase(Locale.ROOT);
            if (lower.contains("vehicle")
                    || lower.contains("transporter")
                    || lower.contains("transport")
                    || lower.contains("dispatch")
                    || lower.contains("gr.")
                    || lower.contains("gr no")
                    || lower.contains("gr/no")
                    || lower.contains("lr-rr")) {
                return true;
            }
        }
        return false;
    }

    private String extractTransporterName(List<String> lines) {
        return normalizeTransportCandidate(extractValue(lines, List.of("transporter name", "transporter nm", "transporter")), true);
    }

    private String extractTransportDetails(List<String> lines) {
        String candidate = firstNonBlank(
                extractValue(lines, List.of("transport details")),
                extractValue(lines, List.of("transporter name", "transporter nm", "transporter")),
                extractValue(lines, List.of("transport")),
                extractTransportMode(lines)
        );
        return normalizeTransportCandidate(candidate, false);
    }

    private String normalizeTransportCandidate(String value, boolean requireTransportSignal) {
        if (value == null) {
            return null;
        }
        String cleaned = normalize(value)
                .replaceFirst("(?i)^(?:transporter\\s*name|transport(?: details)?|dispatch(?:ed)?(?: through)?|er\\s*nm\\.?)\\s*[:=-]?\\s*", "")
                .replaceAll("(?i)\\b(?:inv(?:oice)?\\s*value|grand total|amount payable|place of supply|destination|vehicle(?: no| number)?)\\b.*$", "")
                .trim();
        if (cleaned.isBlank()) {
            return null;
        }
        String lower = cleaned.toLowerCase(Locale.ROOT);
        boolean transportSignal = lower.contains("transport")
                || lower.contains("logistics")
                || lower.contains("roadline")
                || lower.contains("roadway")
                || lower.contains("courier")
                || lower.contains("cargo")
                || lower.contains("travels")
                || TRANSPORT_MODE_KEYWORDS.contains(lower);
        if (requireTransportSignal && !transportSignal) {
            return null;
        }
        if (looksLikeLabeledNoise(cleaned, List.of("transporter name", "transport", "dispatch"))) {
            return null;
        }
        return cleaned;
    }

    private boolean looksLikeLabeledNoise(String value, List<String> currentLabels) {
        String lower = value.toLowerCase(Locale.ROOT);
        if (lower.matches("(?i).*re[mn][o0qd][vlu]e.*")) {
            return true;
        }
        if (EMAIL_PATTERN.matcher(value).find() || PHONE_PATTERN.matcher(value).find()) {
            return true;
        }
        for (String label : KNOWN_LABELS) {
            if (currentLabels.contains(label)) {
                continue;
            }
            if (lower.startsWith(label)) {
                return true;
            }
        }
        return false;
    }

    private int scoreExtractedValue(String candidate, String sourceLine, String label) {
        int score = candidate.length() + (sourceLine.startsWith(label) ? 20 : 0);
        String lower = candidate.toLowerCase(Locale.ROOT);
        if (OcrLayoutUtil.isAddressLike(lower)) {
            score -= 12;
        }
        if (EMAIL_PATTERN.matcher(candidate).find() || PHONE_PATTERN.matcher(candidate).find()) {
            score -= 50;
        }
        if (looksLikeLabeledNoise(candidate, List.of(label))) {
            score -= 80;
        }
        return score;
    }

    private String extractBankNameFromLines(List<String> lines) {
        for (String line : lines) {
            if (!looksLikeBankSectionStart(line)) {
                continue;
            }
            String candidate = normalizeBankNameCandidate(line);
            if (candidate != null) {
                return candidate;
            }
        }
        return null;
    }

    private String extractAccountFromLine(String line) {
        if (line == null) {
            return null;
        }
        Matcher labeled = LABELED_ACCOUNT_PATTERN.matcher(line);
        if (labeled.find()) {
            return extractByPattern(labeled.group(1), ACCOUNT_PATTERN);
        }
        String lower = line.toLowerCase(Locale.ROOT);
        if (lower.contains("account") || lower.contains("a/c")) {
            String candidate = extractByPattern(line, ACCOUNT_PATTERN);
            if (candidate != null) {
                return candidate;
            }
        }
        String trimmed = line.trim();
        if (trimmed.matches("^\\d{8,18}$")) {
            return normalize(line);
        }
        return null;
    }

    private String extractIfscFromLine(String line) {
        if (line == null) {
            return null;
        }
        Matcher labeled = LABELED_IFSC_PATTERN.matcher(line);
        if (labeled.find()) {
            String normalized = normalizeIfscCandidate(labeled.group(1));
            if (normalized != null) {
                return normalized;
            }
        }
        Matcher tokenMatcher = Pattern.compile("(?i)\\b[A-Z0-9]{11}\\b").matcher(line);
        while (tokenMatcher.find()) {
            String normalized = normalizeIfscCandidate(tokenMatcher.group());
            if (normalized != null) {
                return normalized;
            }
        }
        return null;
    }

    private String normalizeIfscCandidate(String value) {
        if (value == null) {
            return null;
        }
        String cleaned = value.replaceAll("[^A-Za-z0-9]", "").toUpperCase(Locale.ROOT);
        if (cleaned.length() != 11) {
            return null;
        }
        // First try: direct positional repair (digits→letters for prefix, letters→digits for suffix)
        String prefix = cleaned.substring(0, 4)
                .replace('0', 'O')
                .replace('1', 'I')
                .replace('5', 'S')
                .replace('8', 'B')
                .replace('2', 'Z')
                .replace('6', 'G');
        String suffix = cleaned.substring(4)
                .replace('O', '0')
                .replace('Q', '0')
                .replace('D', '0');
        String normalized = prefix + suffix;
        if (IFSC_PATTERN.matcher(normalized).matches()) {
            return normalized;
        }
        // Second try: OCR-specific repair for known bank code confusions
        // e.g. HOFC → HDFC (O misread for D), ICIC → ICICI (dropped letter)
        String repairedPrefix = repairKnownBankPrefix(prefix);
        if (!repairedPrefix.equals(prefix)) {
            normalized = repairedPrefix + suffix;
            if (IFSC_PATTERN.matcher(normalized).matches()) {
                return normalized;
            }
        }
        return null;
    }

    private String repairKnownBankPrefix(String prefix) {
        // Map of common OCR misreads in bank IFSC prefixes
        return switch (prefix) {
            case "HOFC" -> "HDFC";  // O misread for D
            case "HOBI" -> "HDFC";  // Multiple misreads
            case "SBII" -> "SBIN";  // I misread for N
            case "SBIN" -> "SBIN";
            case "UTIB" -> "UTIB";
            case "ICIC" -> "ICIC";
            case "KKBK" -> "KKBK";
            case "BKIO" -> "BKID";  // O misread for D (Bank of India)
            case "IOBA" -> "IOBA";
            case "PUNB" -> "PUNB";
            default -> prefix;
        };
    }

    private String normalizeBankNameCandidate(String value) {
        if (value == null) {
            return null;
        }
        String cleaned = normalize(value)
                .replaceFirst("(?i)^(?:bank details|bank name)\\s*[:=-]?\\s*", "")
                .replaceAll("(?i)\\b(?:a/c type|a/c|account(?: no| number)?|ifsc|branch|grand total|amount payable|taxable value|inv(?:oice)?\\s*value|total amount after tax|tel\\.?|phone|email|website)\\b.*$", "")
                .replaceAll("\\s*[-,:;]+\\s*\\d{4,}.*$", "")
                .trim();
        String lower = cleaned.toLowerCase(Locale.ROOT);
        if (cleaned.isBlank()
                || lower.equals("our bank")
                || lower.equals("bank details")
                || lower.contains("details of receiver")
                || lower.contains("details of consignee")
                || lower.contains("original for buyer")) {
            return null;
        }
        for (String namedBank : List.of("State Bank of India", "Punjab National Bank", "HDFC Bank Limited",
                "HDFC Bank", "ICICI Bank", "Uco Bank", "Canara Bank", "Axis Bank", "Indian Bank")) {
            Matcher namedMatcher = Pattern.compile("(?i)\\b" + Pattern.quote(namedBank) + "\\b").matcher(cleaned);
            if (namedMatcher.find()) {
                return normalize(namedMatcher.group());
            }
        }
        Matcher matcher = Pattern.compile("(?i)\\b([A-Z][A-Z.& ]*BANK(?:\\s+LIMITED|\\s+LTD|\\s+OF\\s+[A-Z][A-Z ]+)?)\\b").matcher(cleaned);
        if (matcher.find()) {
            return normalize(matcher.group(1));
        }
        return cleaned.toLowerCase(Locale.ROOT).contains("bank") ? cleaned : null;
    }

    private boolean looksLikeBankSectionStart(String line) {
        if (line == null) {
            return false;
        }
        String lower = line.toLowerCase(Locale.ROOT);
        if (lower.contains("@")) {
            return false;
        }
        return lower.matches(".*\\bbank(?: details| name| tame)?\\b.*")
                || lower.matches(".*\\baccount(?: no| number)?\\b.*")
                || lower.matches(".*\\ba/c(?: no)?\\b.*")
                || lower.matches(".*\\bifsc(?: code)?\\b.*")
                || lower.matches(".*\\bbranch\\s*&\\s*ifs\\s*code\\b.*");
    }

    private boolean isBankSectionStop(String line) {
        String lower = line.toLowerCase(Locale.ROOT);
        return lower.contains("description")
                || lower.contains("qty")
                || lower.contains("taxable value")
                || lower.contains("grand total")
                || lower.contains("terms & conditions")
                || lower.contains("declaration")
                || lower.contains("buyer")
                || lower.contains("bill to")
                || lower.contains("ship to")
                || lower.contains("consignee");
    }

    private boolean isLikelyAddressLine(String line) {
        if (line == null || line.isBlank()) {
            return false;
        }
        String lower = line.toLowerCase(Locale.ROOT);
        if (lower.contains("invoice no") || lower.contains("delivery note") || lower.contains("dispatch")
                || lower.contains("description") || lower.contains("qty")) {
            return false;
        }
        if (isInvalidAddressPayload(line)) {
            return false;
        }
        return OcrLayoutUtil.isAddressLike(lower)
                || lower.matches(".*\\b\\d{5,6}\\b.*")
                || lower.contains("hyderabad")
                || lower.contains("telangana")
                || lower.contains("road")
                || lower.contains("floor")
                || lower.contains("post");
    }

    private boolean isAddressBoundaryLine(String lower) {
        return lower.contains("po no")
                || lower.contains("purchase order")
                || lower.contains("buyers order")
                || lower.contains("buyer's order")
                || lower.contains("invoice no")
                || lower.contains("invoice date")
                || lower.contains("gstin")
                || OcrLayoutUtil.looksLikeTableHeader(lower)
                || OcrLayoutUtil.isItemStopLine(lower)
                || lower.contains("vehicle")
                || lower.contains("transport")
                || lower.contains("bank details")
                || lower.matches("^\\d+$");
    }

    private boolean hasLargeSpacingBreak(String line) {
        return line != null && line.matches(".*\\s{4,}.*");
    }

    private boolean isInvalidAddressPayload(String line) {
        if (line == null || line.isBlank()) {
            return true;
        }
        String normalized = normalize(line);
        String lower = normalized.toLowerCase(Locale.ROOT);
        if (normalized.matches("^\\d+$")) {
            return true;
        }
        if (!DateUtil.findCandidateDates(normalized).isEmpty() && normalized.replaceAll("[^0-9A-Za-z]", "").length() <= 12) {
            return true;
        }
        if (RegexUtil.GSTIN_PATTERN.matcher(normalized.replaceAll("\\s+", "")).find()) {
            return true;
        }
        if (lower.matches(".*\\b(?:po\\s*no|purchase\\s*order|invoice\\s*no|invoice\\s*date|gstin|grand\\s*total|taxable\\s*value)\\b.*")) {
            return true;
        }
        int symbols = 0;
        for (char ch : normalized.toCharArray()) {
            if (!Character.isLetterOrDigit(ch) && !Character.isWhitespace(ch) && "/-.,&():".indexOf(ch) < 0) {
                symbols++;
            }
        }
        return symbols > Math.max(3, normalized.length() / 8);
    }

    private boolean containsPincode(String value) {
        return value != null && PINCODE_PATTERN.matcher(value).find();
    }

    private String trimBusinessBoundary(String value) {
        String normalized = normalize(value);
        if (normalized.isBlank()) {
            return normalized;
        }
        return normalized
                .replaceAll("(?i)\\b(?:purchase order(?: no)?|po(?: no| number)?|buyers order|buyer's order|invoice no|invoice date|dated|gstin(?:/uin)?|vehicle(?: no| number)?|transport(?:er)?|dispatch(?:ed)?(?: through)?|bank details|bank name|account(?: no| number)?|ifsc|grand total|amount payable|taxable value)\\b.*$", "")
                .trim();
    }

    private String extractBuyerInlineValue(String line) {
        if (line == null) {
            return null;
        }
        String normalizedLine = normalize(line.replace('\'', ' '));
        Matcher matcher = Pattern.compile("(?i)\\b(?:bill(?:ed)? to|buyer(?:\\s*\\(bill to\\))?|ship(?:ped)? to|consignee)\\b\\s*[:=-]*\\s*(.+)").matcher(normalizedLine);
        if (!matcher.find()) {
            return null;
        }
        String tail = matcher.group(1);
        if (tail == null) {
            return null;
        }
        for (String part : tail.split("\\|")) {
            String candidate = normalize(part);
            if (candidate.isBlank()) {
                continue;
            }
            String lower = candidate.toLowerCase(Locale.ROOT);
            if (lower.contains("bill to") || lower.contains("ship to") || lower.contains("consignee") || lower.contains("buyer")) {
                continue;
            }
            if (lower.contains("bank") || lower.contains("gstin") || lower.contains("invoice")) {
                continue;
            }
            return candidate;
        }
        return null;
    }

    private String buyerSideOfLine(String line) {
        if (line == null) {
            return "";
        }
        String inline = extractBuyerInlineValue(line);
        if (inline != null) {
            return inline;
        }
        if (line.contains("|")) {
            for (String rawPart : line.split("\\|")) {
                String candidate = normalize(rawPart);
                if (candidate.isBlank()) {
                    continue;
                }
                String lower = candidate.toLowerCase(Locale.ROOT);
                if (lower.contains("bill to") || lower.contains("ship to") || lower.contains("consignee") || lower.contains("buyer")) {
                    continue;
                }
                if (lower.contains("bank") || lower.contains("account") || lower.contains("ifsc") || lower.contains("branch")) {
                    if (!candidate.matches(".*\\d{5,6}.*")) {
                        continue;
                    }
                }
                return candidate;
            }
        }
        String lower = line.toLowerCase(Locale.ROOT);
        if (lower.contains("bank") || lower.contains("account") || lower.contains("ifsc") || lower.contains("branch")) {
            return "";
        }
        return normalize(line);
    }

    private boolean looksLikeBuyerText(String line, String buyerName) {
        if (line == null || line.isBlank()) {
            return false;
        }
        String normalized = normalize(line);
        if (!buyerName.isBlank() && RegexUtil.normalizeForComparison(normalized).equals(RegexUtil.normalizeForComparison(buyerName))) {
            return false;
        }
        String lower = normalized.toLowerCase(Locale.ROOT);
        return lower.contains("department")
                || lower.contains("atomic energy")
                || lower.contains("nuclear")
                || lower.contains("stores")
                || lower.contains("officer")
                || lower.contains("complex")
                || lower.contains("ecil")
                || lower.matches(".*\\b\\d{6}\\b.*");
    }

    private String stripContactNoise(String line) {
        return normalize(line)
                .replaceAll("(?i)\\b(?:www\\.|https?://)\\S+.*$", "")
                .replaceAll("(?i)\\bemail\\b.*$", "")
                .replaceAll("(?i)\\btel\\b.*$", "")
                .replaceAll("(?i)\\bphone\\b.*$", "")
                .trim();
    }

    private String normalize(String value) {
        if (value == null) {
            return "";
        }
        return value.replace('|', ' ')
                .replace('_', ' ')
                .replaceAll("\\s+", " ")
                .replaceAll("^[\\s:;,#>.\\-]+|[\\s:;,#>.\\-]+$", "")
                .trim();
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    public static class Result {
        private String poDate;
        private String orderReference;
        private String deliveryNote;
        private String dispatchThrough;
        private String transporterName;
        private String transportDetails;
        private String destination;
        private String placeOfSupply;
        private String bankName;
        private String accountNumber;
        private String ifscCode;
        private String branch;
        private String vendorPhone;
        private String vendorEmail;
        private String vendorWebsite;
        private String vendorAddress;
        private String buyerAddress;
        private String vendorPAN;
        private String vendorCIN;
        private String msmeNumber;
        private String state;
        private String stateCode;
        private String pincode;
        private String taxableValue;
        private String cgst;
        private String sgst;
        private String igst;
        private String roundOff;

        public String getPoDate() { return poDate; }
        public String getOrderReference() { return orderReference; }
        public String getDeliveryNote() { return deliveryNote; }
        public String getDispatchThrough() { return dispatchThrough; }
        public String getTransporterName() { return transporterName; }
        public String getTransportDetails() { return transportDetails; }
        public String getDestination() { return destination; }
        public String getPlaceOfSupply() { return placeOfSupply; }
        public String getBankName() { return bankName; }
        public String getAccountNumber() { return accountNumber; }
        public String getIfscCode() { return ifscCode; }
        public String getBranch() { return branch; }
        public String getVendorPhone() { return vendorPhone; }
        public String getVendorEmail() { return vendorEmail; }
        public String getVendorWebsite() { return vendorWebsite; }
        public String getVendorAddress() { return vendorAddress; }
        public String getBuyerAddress() { return buyerAddress; }
        public String getVendorPAN() { return vendorPAN; }
        public String getVendorCIN() { return vendorCIN; }
        public String getMsmeNumber() { return msmeNumber; }
        public String getState() { return state; }
        public String getStateCode() { return stateCode; }
        public String getPincode() { return pincode; }
        public String getTaxableValue() { return taxableValue; }
        public String getCgst() { return cgst; }
        public String getSgst() { return sgst; }
        public String getIgst() { return igst; }
        public String getRoundOff() { return roundOff; }
    }
}
