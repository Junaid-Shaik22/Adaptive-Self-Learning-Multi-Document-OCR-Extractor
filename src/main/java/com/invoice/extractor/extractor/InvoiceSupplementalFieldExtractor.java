package com.invoice.extractor.extractor;

import com.invoice.extractor.model.InvoiceOcrDocument;
import com.invoice.extractor.util.RegexUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class InvoiceSupplementalFieldExtractor {
    private static final Pattern VEHICLE_PATTERN = Pattern.compile("(?i)\\b[A-Z]{2}\\s?\\d{1,2}\\s?[A-Z]{1,3}\\s?\\d{3,4}\\b");
    private static final Pattern IRN_PATTERN = Pattern.compile("(?i)\\birn\\b\\s*[:=-]?\\s*([A-Z0-9]{8,100})");
    private static final Pattern ACK_PATTERN = Pattern.compile("(?i)\\back(?:nowledgement)?\\s*no\\b\\s*[:=-]?\\s*([A-Z0-9/-]{4,100})");
    private static final Pattern EWAY_PATTERN = Pattern.compile("(?i)\\be[ -]?way\\s*bill(?:\\s*no)?\\b\\s*[:=-]?\\s*([A-Z0-9/-]{4,100})");
    private static final Pattern PO_INLINE_PATTERN = Pattern.compile("(?i)\\b(?:customer\\s*po|po|p\\.o\\.?|purchase\\s*order|buyer(?:'s)?\\s*order)\\s*(?:no|number)?(?:\\.?\\s*date(?:d)?)?\\b\\s*[:=#>|-]*\\s*([A-Z0-9][A-Z0-9./-]{2,40})");
    private static final Pattern BANK_IFSC_PATTERN = Pattern.compile("\\b[A-Z]{4}0[A-Z0-9]{6}\\b");
    private static final Pattern LONG_DIGIT_SEQUENCE = Pattern.compile("\\b\\d{8,18}\\b");
    private static final Pattern NOISE_PATTERN = Pattern.compile("[`~^<>]{1,}|\\?{2,}|\\*{2,}");
    private static final Pattern SECONDARY_LABEL_PATTERN = Pattern.compile(
            "(?i)\\b(destination|dispatch(?:ed)?(?: through)?|delivery note(?: date)?|bill of lading|lr[- /]?rr|motor vehicle(?: no)?|vehicle no|bank details|bank name|account(?: no| number)?|ifsc|invoice no|invoice date|challan(?: no(?: & date)?)?|gstin(?:/uin)?|bill to|ship to|consignee|buyer|amount payable|grand total|taxable value|cgst|sgst|igst|irn|ack(?:nowledgement)?(?: no)?|e[ -]?way bill(?: no)?)\\b"
    );
    private static final Set<String> PAYMENT_TERMS_KEYWORDS = Set.of(
            "day", "days", "credit", "advance", "immediate", "net", "due", "payment", "against delivery", "cod"
    );
    private static final Set<String> TRANSPORT_NAME_KEYWORDS = Set.of(
            "transport", "transporter", "logistics", "roadlines", "roadways", "courier", "cargo", "express", "travels"
    );
    private static final Set<String> TRANSPORT_MODE_KEYWORDS = Set.of(
            "road", "by road", "canter", "truck", "lorry", "air", "sea", "rail", "courier", "express"
    );
    private static final Set<String> GENERIC_VALUE_REJECTS = Set.of(
            "destination", "dispatch", "vehicle", "transport", "bank", "invoice", "date", "gstin", "bill to", "ship to"
    );

    public Result extract(InvoiceOcrDocument document) {
        List<String> lines = toLines(document == null ? "" : document.getCombinedText());
        Result result = new Result();
        result.poNumber = extractPurchaseOrder(lines);
        result.paymentTerms = extractBestLabeledValue(
                lines,
                List.of("terms of payment", "payment terms", "credit period", "terms of delivery"),
                this::cleanPaymentTerms,
                this::isValidPaymentTerms
        );
        result.transportDetails = extractBestLabeledValue(
                lines,
                List.of("transporter name", "transporter nm", "transporter nm.", "dispatched through",
                        "dispatch through", "transportation mode", "mode", "transport", "transporter"),
                this::cleanTransportDetails,
                this::isValidTransportDetails
        );
        result.vehicleNumber = extractVehicleNumber(lines);
        result.bankDetails = extractBankDetails(lines);
        result.irn = extractStrictPattern(lines, IRN_PATTERN, this::cleanIdentifierValue, this::isValidIdentifierValue);
        result.ackNumber = extractStrictPattern(lines, ACK_PATTERN, this::cleanIdentifierValue, this::isValidIdentifierValue);
        result.ewayBill = extractStrictPattern(lines, EWAY_PATTERN, this::cleanIdentifierValue, this::isValidIdentifierValue);
        return result;
    }

    private List<String> toLines(String rawText) {
        List<String> lines = new ArrayList<>();
        if (rawText == null || rawText.isBlank()) {
            return lines;
        }
        for (String rawLine : rawText.split("\\n")) {
            String normalized = RegexUtil.normalizeLine(rawLine);
            if (!normalized.isBlank()) {
                lines.add(normalized);
            }
        }
        return lines;
    }

    private String extractPurchaseOrder(List<String> lines) {
        String inline = extractStrictPattern(lines, PO_INLINE_PATTERN, this::cleanIdentifierValue, this::isValidPurchaseOrder);
        if (inline != null) {
            return inline;
        }
        return extractBestLabeledValue(
                lines,
                List.of("customer po no", "customer po", "purchase order no", "purchase order",
                        "buyer order no", "buyer order", "buyer's order", "buyers order",
                        "po number", "po no", "order no"),
                this::cleanIdentifierValue,
                this::isValidPurchaseOrder
        );
    }

    private String extractVehicleNumber(List<String> lines) {
        String labeled = extractBestLabeledValue(
                lines,
                List.of("vehicle number", "vehicle no", "motor vehicle no"),
                this::cleanVehicleNumber,
                this::isValidVehicleNumber
        );
        if (labeled != null) {
            return labeled;
        }
        for (String line : lines) {
            Matcher matcher = VEHICLE_PATTERN.matcher(line.toUpperCase(Locale.ROOT));
            if (matcher.find()) {
                String candidate = cleanVehicleNumber(matcher.group());
                if (isValidVehicleNumber(candidate)) {
                    return candidate;
                }
            }
        }
        return null;
    }

    private String extractBankDetails(List<String> lines) {
        String best = null;
        int bestScore = Integer.MIN_VALUE;
        for (int i = 0; i < lines.size(); i++) {
            if (!looksLikeBankSectionStart(lines.get(i))) {
                continue;
            }

            StringBuilder chunk = new StringBuilder();
            for (int j = i; j < Math.min(lines.size(), i + 8); j++) {
                String current = lines.get(j);
                if (j > i && looksLikeBankSectionStop(current)) {
                    break;
                }
                if (chunk.length() > 0) {
                    chunk.append(' ');
                }
                chunk.append(current);
            }

            String cleaned = cleanBankDetails(chunk.toString());
            if (!isValidBankDetails(cleaned)) {
                continue;
            }

            int score = qualityScore(cleaned);
            if (cleaned.toLowerCase(Locale.ROOT).contains("ifsc")) {
                score += 25;
            }
            if (LONG_DIGIT_SEQUENCE.matcher(cleaned).find()) {
                score += 15;
            }
            if (score > bestScore) {
                bestScore = score;
                best = cleaned;
            }
        }
        return best;
    }

    private boolean looksLikeBankSectionStart(String value) {
        String lower = value == null ? "" : value.toLowerCase(Locale.ROOT);
        if (lower.contains("@")) {
            return false;
        }
        return lower.matches(".*\\bbank(?: details| name| tame)?\\b.*")
                || lower.matches(".*\\baccount(?: no| number)?\\b.*")
                || lower.matches(".*\\ba/c(?: no)?\\b.*")
                || lower.matches(".*\\bifsc(?: code)?\\b.*");
    }

    private String extractStrictPattern(List<String> lines,
                                        Pattern pattern,
                                        UnaryOperator<String> cleaner,
                                        Predicate<String> validator) {
        String best = null;
        int bestScore = Integer.MIN_VALUE;
        for (String line : lines) {
            Matcher matcher = pattern.matcher(line);
            if (!matcher.find()) {
                continue;
            }
            String candidate = cleaner.apply(matcher.group(1));
            if (!validator.test(candidate)) {
                continue;
            }
            int score = qualityScore(candidate);
            if (score > bestScore) {
                bestScore = score;
                best = candidate;
            }
        }
        return best;
    }

    private String extractBestLabeledValue(List<String> lines,
                                           List<String> labels,
                                           UnaryOperator<String> cleaner,
                                           Predicate<String> validator) {
        String best = null;
        int bestScore = Integer.MIN_VALUE;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            String lower = line.toLowerCase(Locale.ROOT);
            for (String label : labels) {
                if (!lower.contains(label)) {
                    continue;
                }

                String sameLine = cleaner.apply(stripLabel(line, label));
                if (validator.test(sameLine)) {
                    int score = 90 + qualityScore(sameLine);
                    if (line.toLowerCase(Locale.ROOT).startsWith(label)) {
                        score += 10;
                    }
                    if (score > bestScore) {
                        bestScore = score;
                        best = sameLine;
                    }
                }

                for (int offset = 1; offset <= 2 && i + offset < lines.size(); offset++) {
                    String nextRaw = lines.get(i + offset);
                    if (offset > 1 && looksLikeUnrelatedField(nextRaw)) {
                        break;
                    }
                    String nextLine = cleaner.apply(nextRaw);
                    if (!validator.test(nextLine)) {
                        continue;
                    }
                    int score = 60 - (offset - 1) * 8 + qualityScore(nextLine);
                    if (score > bestScore) {
                        bestScore = score;
                        best = nextLine;
                    }
                }
            }
        }
        return best;
    }

    private String cleanIdentifierValue(String rawValue) {
        String cleaned = normalizeValue(rawValue);
        if (cleaned == null) {
            return null;
        }
        cleaned = cleaned.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9./-]", "");
        return cleaned.isBlank() ? null : cleaned;
    }

    private String cleanVehicleNumber(String rawValue) {
        String cleaned = cleanIdentifierValue(rawValue);
        return cleaned == null ? null : cleaned.replaceAll("[^A-Z0-9]", "");
    }

    private String cleanPaymentTerms(String rawValue) {
        String cleaned = normalizeValue(rawValue);
        if (cleaned == null) {
            return null;
        }
        cleaned = truncateAtSecondaryLabel(cleaned);
        cleaned = cleaned.replaceFirst("(?i)\\s*[-,:;]+\\s*$", "");
        cleaned = cleaned.replaceFirst("(?i)\\s+-\\s+(?=(destination|dispatch|vehicle|bank|invoice|gstin)\\b).*", "");
        cleaned = cleaned.replaceAll("(?i)\\b(?:destination|dispatch(?:ed)?|vehicle|bank details|invoice no|gstin)\\b.*$", "").trim();
        return cleaned;
    }

    private String cleanTransportDetails(String rawValue) {
        String cleaned = normalizeValue(rawValue);
        if (cleaned == null) {
            return null;
        }
        cleaned = truncateAtSecondaryLabel(cleaned);
        cleaned = cleaned.replaceFirst("(?i)^(?:transport(?:er)?(?:\\s*(?:name|nm\\.?))?|dispatched\\s+through|dispatch\\s+through|transportation\\s+mode|mode)\\s*[:=#>|-]*\\s*", "");
        cleaned = cleaned.replaceFirst("(?i)\\s+-\\s+(?=(destination|vehicle|delivery note)\\b).*", "");
        cleaned = cleaned.replaceAll("(?i)\\b(?:destination|vehicle number|vehicle no|delivery note)\\b.*$", "").trim();
        return cleaned;
    }

    private String cleanBankDetails(String rawValue) {
        String cleaned = normalizeValue(rawValue);
        if (cleaned == null) {
            return null;
        }
        cleaned = truncateAtSecondaryLabel(cleaned);
        cleaned = cleaned.replaceAll("(?i)\\b(?:grand total|amount payable|taxable value|cgst|sgst|igst)\\b.*$", "").trim();
        return cleaned;
    }

    private String normalizeValue(String rawValue) {
        if (rawValue == null) {
            return null;
        }
        String cleaned = rawValue
                .replace('|', ' ')
                .replace('_', ' ')
                .replaceAll("\\s+", " ")
                .trim();
        cleaned = cleaned.replaceAll("^[^A-Za-z0-9]+|[^A-Za-z0-9]+$", "").trim();
        if (cleaned.isBlank()) {
            return null;
        }
        return cleaned;
    }

    private String truncateAtSecondaryLabel(String value) {
        Matcher matcher = SECONDARY_LABEL_PATTERN.matcher(value);
        if (matcher.find() && matcher.start() > 0) {
            return value.substring(0, matcher.start()).trim();
        }
        return value;
    }

    private String stripLabel(String line, String label) {
        return line.replaceFirst("(?i).*?" + Pattern.quote(label) + "\\s*(?:no|number|dated|date)?\\s*(?:[:=#>|-]+\\s*)*", "");
    }

    private boolean isValidPurchaseOrder(String value) {
        return isValidIdentifierValue(value)
                && value.length() <= 30
                && value.matches(".*\\d.*")
                && value.matches(".*[A-Z].*");
    }

    private boolean isValidPaymentTerms(String value) {
        if (!isCleanBusinessValue(value, 3, 40)) {
            return false;
        }
        String lower = value.toLowerCase(Locale.ROOT);
        if (GENERIC_VALUE_REJECTS.contains(lower)) {
            return false;
        }
        return PAYMENT_TERMS_KEYWORDS.stream().anyMatch(lower::contains) || lower.matches(".*\\d+\\s*day.*");
    }

    private boolean isValidTransportDetails(String value) {
        if (!isCleanBusinessValue(value, 3, 60)) {
            return false;
        }
        String lower = value.toLowerCase(Locale.ROOT);
        if (GENERIC_VALUE_REJECTS.contains(lower) || lower.matches(".*\\b\\d{1,2}[-/]\\d{1,2}[-/]\\d{2,4}\\b.*")) {
            return false;
        }
        if (TRANSPORT_NAME_KEYWORDS.stream().anyMatch(lower::contains)) {
            return true;
        }
        if (TRANSPORT_MODE_KEYWORDS.stream().anyMatch(lower::equals)) {
            return true;
        }
        return value.split("\\s+").length >= 2 && value.matches(".*[A-Za-z].*");
    }

    private boolean isValidBankDetails(String value) {
        if (!isCleanBusinessValue(value, 8, 100)) {
            return false;
        }
        String lower = value.toLowerCase(Locale.ROOT);
        boolean bankSignal = lower.contains("bank") || lower.contains("account") || lower.contains("a/c") || lower.contains("ifsc");
        boolean numericSignal = LONG_DIGIT_SEQUENCE.matcher(value).find() || BANK_IFSC_PATTERN.matcher(value.toUpperCase(Locale.ROOT)).find();
        return bankSignal && numericSignal;
    }

    private boolean isValidVehicleNumber(String value) {
        return value != null && VEHICLE_PATTERN.matcher(value).find();
    }

    private boolean isValidIdentifierValue(String value) {
        return value != null
                && value.length() >= 4
                && value.length() <= 100
                && !NOISE_PATTERN.matcher(value).find()
                && value.matches("[A-Z0-9./-]+");
    }

    private boolean isCleanBusinessValue(String value, int minLength, int maxLength) {
        if (value == null) {
            return false;
        }
        String cleaned = value.trim();
        if (cleaned.length() < minLength || cleaned.length() > maxLength) {
            return false;
        }
        if (NOISE_PATTERN.matcher(cleaned).find()) {
            return false;
        }
        if (cleaned.contains("|") || cleaned.contains("_")) {
            return false;
        }
        int symbolCount = 0;
        for (char ch : cleaned.toCharArray()) {
            if (!Character.isLetterOrDigit(ch) && !Character.isWhitespace(ch) && "/-.,:&()".indexOf(ch) < 0) {
                symbolCount++;
            }
        }
        return symbolCount == 0 && cleaned.matches(".*[A-Za-z0-9].*");
    }

    private boolean looksLikeUnrelatedField(String value) {
        String lower = value == null ? "" : value.toLowerCase(Locale.ROOT);
        return lower.contains("grand total")
                || lower.contains("amount payable")
                || lower.contains("taxable value")
                || lower.contains("description")
                || lower.contains("invoice no")
                || lower.contains("gstin")
                || lower.contains("po number")
                || lower.contains("terms of payment")
                || lower.contains("vehicle number")
                || lower.contains("dispatched through")
                || lower.contains("reference code")
                || lower.contains("department")
                || lower.contains("irn")
                || lower.contains("ack no")
                || lower.contains("acknowledgement")
                || lower.contains("e-way bill")
                || lower.contains("buyer")
                || lower.contains("consignee");
    }

    private boolean looksLikeBankSectionStop(String value) {
        String lower = value == null ? "" : value.toLowerCase(Locale.ROOT);
        if (lower.contains("description")
                || lower.contains("grand total")
                || lower.contains("amount chargeable")
                || lower.contains("taxable value")
                || lower.contains("igst")
                || lower.contains("cgst")
                || lower.contains("sgst")
                || lower.contains("terms & conditions")
                || lower.contains("declaration")) {
            return true;
        }
        return lower.contains("bill to")
                || lower.contains("buyer")
                || lower.contains("ship to")
                || lower.contains("consignee");
    }

    private int qualityScore(String value) {
        int score = value == null ? 0 : value.length();
        if (value == null) {
            return score;
        }
        if (value.matches(".*\\d.*")) {
            score += 8;
        }
        if (value.matches(".*[A-Za-z].*")) {
            score += 8;
        }
        if (!value.contains("|") && !value.contains("_")) {
            score += 6;
        }
        return score;
    }

    public static class Result {
        private String poNumber;
        private String transportDetails;
        private String vehicleNumber;
        private String paymentTerms;
        private String bankDetails;
        private String irn;
        private String ackNumber;
        private String ewayBill;

        public String getPoNumber() {
            return poNumber;
        }

        public String getTransportDetails() {
            return transportDetails;
        }

        public String getVehicleNumber() {
            return vehicleNumber;
        }

        public String getPaymentTerms() {
            return paymentTerms;
        }

        public String getBankDetails() {
            return bankDetails;
        }

        public String getIrn() {
            return irn;
        }

        public String getAckNumber() {
            return ackNumber;
        }

        public String getEwayBill() {
            return ewayBill;
        }
    }
}
