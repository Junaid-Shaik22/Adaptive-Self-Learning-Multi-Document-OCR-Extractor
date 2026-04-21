package com.invoice.extractor.service.impl;

import com.invoice.extractor.extractor.BuyerExtractor;
import com.invoice.extractor.extractor.FieldExtractionResult;
import com.invoice.extractor.extractor.GstinExtractor;
import com.invoice.extractor.extractor.InvoiceDateExtractor;
import com.invoice.extractor.extractor.InvoiceNumberExtractor;
import com.invoice.extractor.extractor.InvoiceSupplementalFieldExtractor;
import com.invoice.extractor.extractor.InvoiceUniversalFieldExtractor;
import com.invoice.extractor.extractor.LineItemExtractor;
import com.invoice.extractor.extractor.SubtotalExtractor;
import com.invoice.extractor.extractor.TaxExtractor;
import com.invoice.extractor.extractor.TotalExtractor;
import com.invoice.extractor.extractor.VendorExtractor;
import com.invoice.extractor.model.InvoiceData;
import com.invoice.extractor.model.LineItem;
import com.invoice.extractor.model.InvoiceOcrDocument;
import com.invoice.extractor.service.InvoiceService;
import com.invoice.extractor.service.OcrService;
import com.invoice.extractor.service.TemplateExtractionService;
import com.invoice.extractor.service.TemplateLearningService;
import com.invoice.extractor.service.TemplateService;
import com.invoice.extractor.template.Template;
import com.invoice.extractor.template.TemplateField;
import com.invoice.extractor.util.AmountUtil;
import com.invoice.extractor.util.ConfidenceCalculator;
import com.invoice.extractor.util.DateUtil;
import com.invoice.extractor.util.OcrLayoutUtil;
import com.invoice.extractor.util.RegexUtil;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

@Service
public class InvoiceServiceImpl implements InvoiceService {
    private static final List<String> INVOICE_KEYWORDS = List.of("invoice no", "invoice number", "invoice #", "inv no", "bill no", "bill #");
    private static final List<String> DATE_KEYWORDS = List.of("date", "invoice date");
    private static final List<String> BUYER_KEYWORDS = List.of("bill to", "ship to", "consignee", "buyer");
    private static final List<String> GSTIN_KEYWORDS = List.of("gstin");
    private static final List<String> KNOWN_BANK_NAMES = List.of(
            "State Bank of India",
            "Punjab National Bank",
            "HDFC Bank Limited",
            "HDFC Bank",
            "ICICI Bank",
            "Uco Bank",
            "Canara Bank",
            "Bank of Baroda",
            "Indian Bank",
            "Axis Bank",
            "Union Bank of India",
            "Union Bank",
            "Kotak Mahindra Bank"
    );
    private static final Map<String, String> STATE_CODE_TO_NAME = Map.ofEntries(
            Map.entry("06", "Haryana"),
            Map.entry("07", "Delhi"),
            Map.entry("08", "Rajasthan"),
            Map.entry("09", "Uttar Pradesh"),
            Map.entry("24", "Gujarat"),
            Map.entry("27", "Maharashtra"),
            Map.entry("29", "Karnataka"),
            Map.entry("33", "Tamil Nadu"),
            Map.entry("36", "Telangana")
    );
    private static final java.util.regex.Pattern PRIORITY_PHONE_PATTERN = java.util.regex.Pattern.compile(
            "(?<!\\d)(?:\\+?91[-\\s]?)?(?:[6-9]\\d{9}|\\d{3,5}[-\\s]?\\d{6,8})(?!\\d)"
    );
    private static final java.util.regex.Pattern PRIORITY_PINCODE_PATTERN = java.util.regex.Pattern.compile("\\b\\d{6}\\b");
    private static final java.util.regex.Pattern PRIORITY_VEHICLE_PATTERN = java.util.regex.Pattern.compile(
            "(?i)\\b[A-Z]{2}\\s?\\d{1,2}\\s?[A-Z]{1,3}\\s?\\d{3,4}\\b"
    );
    private static final java.util.regex.Pattern EMAIL_REDACTION_PATTERN = java.util.regex.Pattern.compile(
            "(?i)\\b[\\w.%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}\\b"
    );
    private static final java.util.regex.Pattern IFSC_REDACTION_PATTERN = java.util.regex.Pattern.compile(
            "(?i)\\b[A-Z]{4}0[A-Z0-9]{6}\\b"
    );
    private static final java.util.regex.Pattern ACCOUNT_REDACTION_PATTERN = java.util.regex.Pattern.compile(
            "\\b\\d{8,18}\\b"
    );

    private final OcrService ocrService;
    private final TemplateService templateService;
    private final TemplateExtractionService templateExtractionService;
    private final TemplateLearningService templateLearningService;

    public InvoiceServiceImpl(OcrService ocrService,
                              TemplateService templateService,
                              TemplateExtractionService templateExtractionService,
                              TemplateLearningService templateLearningService) {
        this.ocrService = ocrService;
        this.templateService = templateService;
        this.templateExtractionService = templateExtractionService;
        this.templateLearningService = templateLearningService;
    }

    @Override
    public InvoiceData processInvoice(MultipartFile file) {
        InvoiceOcrDocument ocrDocument = ocrService.extractDocument(file);
        String rawText = ocrDocument.getCombinedText();
        LineIndexingService.Zones zones = LineIndexingService.indexLinesAndZones(rawText);
        GenericExtraction generic = extractGeneric(ocrDocument, zones);
        ProcessedTextContext processedText = buildProcessedTextContext(ocrDocument, zones, generic);
        InvoiceSupplementalFieldExtractor.Result supplementalFields = new InvoiceSupplementalFieldExtractor().extract(ocrDocument);

        String signature = templateService.generateSignature(rawText);
        Template template = templateService.findTemplate(signature, rawText);
        InvoiceData templateData = template != null ? templateExtractionService.extract(rawText, template) : new InvoiceData();

        Map<String, String> extractionMethod = new HashMap<>();
        InvoiceData data = new InvoiceData();
        data.setInvoiceNumber(resolveField("invoiceNumber", templateData.getInvoiceNumber(), this::isValidInvoiceNumber, generic.invoiceNumber, extractionMethod));
        data.setInvoiceDate(resolveField("invoiceDate", templateData.getInvoiceDate(), DateUtil::isValidInvoiceDate, generic.invoiceDate, extractionMethod));
        data.setVendorName(resolveField("vendor", templateData.getVendorName(), this::isValidVendorName, generic.vendorName, extractionMethod));
        data.setBuyerName(resolveField("buyer", templateData.getBuyerName(), this::isValidBuyerName, generic.buyerName, extractionMethod));
        data.setVendorGstin(resolveField("gstin", templateData.getVendorGstin(), this::isValidGstin,
                resultOf(generic.gstins.getVendorGstin(), generic.gstins.getVendorMethod(), generic.gstins.getVendorLineNumber()), extractionMethod));
        data.setBuyerGstin(resolveField("buyerGstin", templateData.getBuyerGstin(), this::isValidGstin,
                resultOf(generic.gstins.getBuyerGstin(), generic.gstins.getBuyerMethod(), generic.gstins.getBuyerLineNumber()), extractionMethod));
        data.setTotalAmount(resolveField("total", templateData.getTotalAmount(), value -> isValidTotal(value, zones), generic.totalAmount, extractionMethod));
        data.setTaxAmount(resolveField("tax", templateData.getTaxAmount(), value -> isValidTax(value, data.getTotalAmount()), generic.taxAmount, extractionMethod));
        data.setSubTotal(resolveField("subtotal", templateData.getSubTotal(), value -> isValidSubtotal(value, data.getTotalAmount()), generic.subTotal, extractionMethod));
        data.setCurrency("INR");

        data.setLineItems(selectLineItems(templateData.getLineItems(), generic.lineItems, extractionMethod));
        if (template != null) {
            data.setTemplateId(template.getTemplateId());
        }
        data.setPagesProcessed(ocrDocument.getPageCount());

        normalizeAmounts(data, extractionMethod, generic, zones);
        data.setLineItems(sanitizeLineItems(data.getLineItems(), data.getSubTotal(), data.getTotalAmount()));
        reconcileCoreDocumentFields(data, generic, extractionMethod);
        reconcileIdentityFields(data, generic, extractionMethod);
        normalizeEntityAssignments(data);
        scrubInvalidIdentityFields(data);
        InvoiceUniversalFieldExtractor.Result universalFields = new InvoiceUniversalFieldExtractor().extract(
                processedText.document(),
                data
        );
        applySupplementalFields(data, supplementalFields);
        applyUniversalFields(data, universalFields);
        applyPrioritySingletonFields(data, generic);
        hydrateSummaryAmounts(data, zones);
        repairDocumentFieldsFromLabeledText(processedText.document(), data);
        repairContactFieldsFromText(ocrDocument.getFirstPageText(), data);
        repairAddressFieldsFromRawDocument(ocrDocument, data);
        enrichPartyFieldsFromAddresses(data);
        applyHighPrecisionFilters(data, zones);

        double confidence = ConfidenceCalculator.calculate(data, extractionMethod);
        data.setConfidenceScore(confidence);
        data.setStatus(determineStatus(data, zones, rawText, confidence));
        if (shouldLearnTemplate(template, data, confidence, zones, generic.templateFields)) {
            Template learnedTemplate = templateLearningService.learnTemplate(rawText, data, signature, generic.templateFields);
            if (learnedTemplate != null) {
                data.setTemplateId(learnedTemplate.getTemplateId());
            }
        }
        applyMissingFieldDefaults(data);
        return data;
    }

    private GenericExtraction extractGeneric(InvoiceOcrDocument document, LineIndexingService.Zones combinedZones) {
        GenericExtraction merged = extractGeneric(combinedZones);
        if (document == null || !document.hasMultiplePages()) {
            return merged;
        }

        GenericExtraction firstPage = extractGeneric(LineIndexingService.indexLinesAndZones(document.getFirstPageText()));
        merged.invoiceNumber = preferField(firstPage.invoiceNumber, merged.invoiceNumber, this::isValidInvoiceNumber);
        merged.invoiceDate = preferField(firstPage.invoiceDate, merged.invoiceDate, DateUtil::isValidInvoiceDate);
        merged.vendorName = preferField(firstPage.vendorName, merged.vendorName, this::isValidVendorName);
        merged.buyerName = preferField(firstPage.buyerName, merged.buyerName, this::isValidBuyerName);
        merged.gstins = preferGstins(firstPage.gstins, merged.gstins);
        merged.poNumber = preferField(firstPage.poNumber, merged.poNumber, value -> cleanBusinessIdentifier(value, 30, false) != null);
        merged.vendorPhone = preferField(firstPage.vendorPhone, merged.vendorPhone, value -> cleanPhoneValue(value) != null);
        merged.pincode = preferField(firstPage.pincode, merged.pincode, value -> cleanPincodeValue(value) != null);
        merged.vehicleNumber = preferField(firstPage.vehicleNumber, merged.vehicleNumber, value -> cleanVehicleNumberValue(value) != null);

        GenericExtraction lastPage = extractGeneric(LineIndexingService.indexLinesAndZones(document.getLastPageText()));
        merged.totalAmount = preferField(lastPage.totalAmount, merged.totalAmount, this::looksLikeAmount);
        merged.taxAmount = preferField(lastPage.taxAmount, merged.taxAmount, this::looksLikeAmount);
        merged.subTotal = preferField(lastPage.subTotal, merged.subTotal, this::looksLikeAmount);
        merged.vehicleNumber = preferField(lastPage.vehicleNumber, merged.vehicleNumber, value -> cleanVehicleNumberValue(value) != null);

        String tableText = document.getMiddlePagesText();
        if (tableText == null || tableText.isBlank()) {
            tableText = document.getCombinedText();
        }
        LineIndexingService.Zones tableZones = LineIndexingService.indexLinesAndZones(tableText);
        ensureTableZoneForStandaloneTable(tableZones);
        GenericExtraction tablePages = extractGeneric(tableZones);
        if (tablePages.lineItems != null && !tablePages.lineItems.isEmpty()) {
            merged.lineItems = tablePages.lineItems;
        }

        return merged;
    }

    private GenericExtraction extractGeneric(LineIndexingService.Zones zones) {
        InvoiceNumberExtractor invoiceNumberExtractor = new InvoiceNumberExtractor();
        GstinExtractor gstinExtractor = new GstinExtractor();
        VendorExtractor vendorExtractor = new VendorExtractor();
        BuyerExtractor buyerExtractor = new BuyerExtractor();
        InvoiceDateExtractor dateExtractor = new InvoiceDateExtractor();
        TotalExtractor totalExtractor = new TotalExtractor();
        TaxExtractor taxExtractor = new TaxExtractor();
        SubtotalExtractor subtotalExtractor = new SubtotalExtractor();
        LineItemExtractor lineItemExtractor = new LineItemExtractor();

        GenericExtraction generic = new GenericExtraction();
        generic.invoiceNumber = invoiceNumberExtractor.extractResult(zones);
        generic.gstins = gstinExtractor.extractResult(zones);
        generic.invoiceDate = dateExtractor.extractResult(zones, generic.invoiceNumber.getLineNumber());
        generic.poNumber = extractPriorityIdentifier(zones, List.of(
                "customer po no", "customer po", "purchase order no", "purchase order",
                "buyer order no", "buyer's order", "buyers order", "po no", "po number", "p.o. no", "p.o no", "p.o."
        ), 30, false);
        generic.vendorPhone = extractPriorityPhone(zones);
        generic.pincode = extractPriorityPincode(zones);
        generic.vehicleNumber = extractPriorityVehicleNumber(zones);
        List<String> redactionTokens = collectRedactionTokens(generic);
        LineIndexingService.Zones redactedZones = redactLines(zones, redactionTokens, false);
        generic.vendorName = vendorExtractor.extractResult(redactedZones, generic.gstins.getVendorGstin());
        generic.buyerName = buyerExtractor.extractResult(redactedZones, generic.gstins.getBuyerGstin());
        generic.totalAmount = totalExtractor.extractResult(zones, null);

        Double totalValue = AmountUtil.parseAmount(generic.totalAmount.getValue());
        generic.subTotal = subtotalExtractor.extractResult(zones, null, null);
        Double subtotalValue = AmountUtil.parseAmount(generic.subTotal.getValue());
        generic.taxAmount = taxExtractor.extractResult(zones, totalValue, subtotalValue);
        Double taxValue = AmountUtil.parseAmount(generic.taxAmount.getValue());
        if (generic.subTotal.getValue() == null) {
            generic.subTotal = subtotalExtractor.extractResult(zones, totalValue, taxValue);
        }

        generic.lineItems = lineItemExtractor.extract(zones);
        generic.templateFields = buildTemplateFields(zones, generic);
        return generic;
    }

    private void applyPrioritySingletonFields(InvoiceData data, GenericExtraction generic) {
        if (data == null || generic == null) {
            return;
        }
        String poNumber = cleanBusinessIdentifier(valueOf(generic.poNumber), 30, false);
        if (shouldPreferPriorityValue(data.getPoNumber(), poNumber, 2)) {
            data.setPoNumber(poNumber);
        }
        String phone = cleanPhoneValue(valueOf(generic.vendorPhone));
        if (shouldPreferPriorityValue(data.getVendorPhone(), phone, 4)) {
            data.setVendorPhone(phone);
        }
        String pincode = cleanPincodeValue(valueOf(generic.pincode));
        if (shouldPreferPriorityValue(data.getPincode(), pincode, 0)) {
            data.setPincode(pincode);
        }
        String vehicleNumber = cleanVehicleNumberValue(valueOf(generic.vehicleNumber));
        if (shouldPreferPriorityValue(data.getVehicleNumber(), vehicleNumber, 0)) {
            data.setVehicleNumber(vehicleNumber);
        }
    }

    private boolean shouldPreferPriorityValue(String current, String candidate, int minimumGain) {
        if (candidate == null || candidate.isBlank()) {
            return false;
        }
        if (current == null || current.isBlank() || InvoiceData.NOT_MENTIONED.equals(current)) {
            return true;
        }
        return candidate.length() >= current.length() + minimumGain;
    }

    private String valueOf(FieldExtractionResult<String> result) {
        return result == null ? null : result.getValue();
    }

    private FieldExtractionResult<String> preferField(FieldExtractionResult<String> preferred,
                                                      FieldExtractionResult<String> fallback,
                                                      Predicate<String> validator) {
        if (preferred != null && validator.test(preferred.getValue())) {
            return preferred;
        }
        return fallback;
    }

    private GstinExtractor.Result preferGstins(GstinExtractor.Result preferred, GstinExtractor.Result fallback) {
        if (preferred == null) {
            return fallback;
        }
        if (fallback == null) {
            return preferred;
        }
        String vendorGstin = isValidGstin(preferred.getVendorGstin()) ? preferred.getVendorGstin() : fallback.getVendorGstin();
        String buyerGstin = isValidGstin(preferred.getBuyerGstin()) ? preferred.getBuyerGstin() : fallback.getBuyerGstin();
        String vendorMethod = isValidGstin(preferred.getVendorGstin()) ? preferred.getVendorMethod() : fallback.getVendorMethod();
        String buyerMethod = isValidGstin(preferred.getBuyerGstin()) ? preferred.getBuyerMethod() : fallback.getBuyerMethod();
        Integer vendorLine = isValidGstin(preferred.getVendorGstin()) ? preferred.getVendorLineNumber() : fallback.getVendorLineNumber();
        Integer buyerLine = isValidGstin(preferred.getBuyerGstin()) ? preferred.getBuyerLineNumber() : fallback.getBuyerLineNumber();
        return new GstinExtractor.Result(vendorGstin, buyerGstin, vendorMethod, buyerMethod, vendorLine, buyerLine);
    }

    private void ensureTableZoneForStandaloneTable(LineIndexingService.Zones zones) {
        if (zones == null || !zones.tableZone.isEmpty() || zones.getTableHeaderLine() == null) {
            return;
        }
        boolean headerSeen = false;
        for (LineIndexingService.IndexedLine line : zones.allLines) {
            if (!headerSeen) {
                headerSeen = line.getLineNumber() == zones.getTableHeaderLine().getLineNumber();
                continue;
            }
            String lower = line.getText().toLowerCase();
            if (OcrLayoutUtil.isItemStopLine(lower)) {
                break;
            }
            zones.tableZone.add(line);
        }
    }

    private FieldExtractionResult<String> extractPriorityIdentifier(LineIndexingService.Zones zones,
                                                                    List<String> labels,
                                                                    int maxLength,
                                                                    boolean requireAlphaAndDigit) {
        if (zones == null || labels == null || labels.isEmpty()) {
            return new FieldExtractionResult<>(null, "fallback", null);
        }
        String bestValue = null;
        Integer bestLine = null;
        int bestScore = Integer.MIN_VALUE;
        for (LineIndexingService.IndexedLine line : zones.allLines) {
            String lower = line.getText().toLowerCase();
            for (String label : labels) {
                String normalizedLabel = label.toLowerCase();
                if (!lower.contains(normalizedLabel)) {
                    continue;
                }
                String sameLine = stripPriorityLabel(line.getText(), normalizedLabel);
                String candidate = cleanBusinessIdentifier(firstBusinessToken(sameLine), maxLength, requireAlphaAndDigit);
                if (candidate == null) {
                    candidate = cleanBusinessIdentifier(firstBusinessToken(collectFollowingValue(zones.allLines, line.getLineNumber(), labels)), maxLength, requireAlphaAndDigit);
                }
                if (candidate == null) {
                    continue;
                }
                int score = 120;
                score += lower.startsWith(normalizedLabel) ? 20 : 0;
                score += "TOP".equals(zones.zoneForLineNumber(line.getLineNumber())) ? 18 : 0;
                score += "MIDDLE".equals(zones.zoneForLineNumber(line.getLineNumber())) ? 8 : -10;
                score += label.contains("po") || label.contains("order") ? 8 : 0;
                score -= lower.contains("address") ? 40 : 0;
                if (score > bestScore) {
                    bestScore = score;
                    bestValue = candidate;
                    bestLine = line.getLineNumber();
                }
            }
        }
        return new FieldExtractionResult<>(bestValue, bestValue == null ? "fallback" : "priority", bestLine);
    }

    private FieldExtractionResult<String> extractPriorityPhone(LineIndexingService.Zones zones) {
        if (zones == null) {
            return new FieldExtractionResult<>(null, "fallback", null);
        }
        String best = null;
        Integer bestLine = null;
        int bestScore = Integer.MIN_VALUE;
        for (LineIndexingService.IndexedLine line : zones.topZone) {
            String candidate = cleanPhoneValue(line.getText());
            if (candidate == null) {
                continue;
            }
            String lower = line.getText().toLowerCase();
            int score = 95;
            score += lower.contains("phone") || lower.contains("tel") || lower.contains("mob") ? 25 : 0;
            score += lower.contains("bank") ? -90 : 0;
            score += line.getLineNumber() <= 10 ? 10 : 0;
            if (score > bestScore) {
                bestScore = score;
                best = candidate;
                bestLine = line.getLineNumber();
            }
        }
        return new FieldExtractionResult<>(best, best == null ? "fallback" : "priority", bestLine);
    }

    private FieldExtractionResult<String> extractPriorityPincode(LineIndexingService.Zones zones) {
        if (zones == null) {
            return new FieldExtractionResult<>(null, "fallback", null);
        }
        String best = null;
        Integer bestLine = null;
        int bestScore = Integer.MIN_VALUE;
        for (LineIndexingService.IndexedLine line : zones.allLines) {
            java.util.regex.Matcher matcher = PRIORITY_PINCODE_PATTERN.matcher(line.getText());
            while (matcher.find()) {
                String candidate = cleanPincodeValue(matcher.group());
                if (candidate == null) {
                    continue;
                }
                String lower = line.getText().toLowerCase();
                int score = 80;
                score += lower.contains("pin") || lower.contains("pincode") || lower.contains("pin code") ? 30 : 0;
                score += OcrLayoutUtil.isAddressLike(lower) ? 18 : 0;
                score += lower.contains("amount") ? -90 : 0;
                if (score > bestScore) {
                    bestScore = score;
                    best = candidate;
                    bestLine = line.getLineNumber();
                }
            }
        }
        return new FieldExtractionResult<>(best, best == null ? "fallback" : "priority", bestLine);
    }

    private FieldExtractionResult<String> extractPriorityVehicleNumber(LineIndexingService.Zones zones) {
        if (zones == null) {
            return new FieldExtractionResult<>(null, "fallback", null);
        }
        String best = null;
        Integer bestLine = null;
        int bestScore = Integer.MIN_VALUE;
        for (LineIndexingService.IndexedLine line : zones.allLines) {
            java.util.regex.Matcher matcher = PRIORITY_VEHICLE_PATTERN.matcher(line.getText().toUpperCase());
            while (matcher.find()) {
                String candidate = cleanVehicleNumberValue(matcher.group());
                if (candidate == null) {
                    continue;
                }
                String lower = line.getText().toLowerCase();
                int score = 90;
                score += lower.contains("vehicle") || lower.contains("motor vehicle") ? 35 : 0;
                score += lower.contains("transport") || lower.contains("dispatch") ? 12 : 0;
                score -= lower.contains("bank") ? 80 : 0;
                if (score > bestScore) {
                    bestScore = score;
                    best = candidate;
                    bestLine = line.getLineNumber();
                }
            }
        }
        return new FieldExtractionResult<>(best, best == null ? "fallback" : "priority", bestLine);
    }

    private ProcessedTextContext buildProcessedTextContext(InvoiceOcrDocument document,
                                                           LineIndexingService.Zones zones,
                                                           GenericExtraction generic) {
        if (zones == null || generic == null) {
            return new ProcessedTextContext(document, zones, List.of());
        }
        List<String> redactionTokens = collectRedactionTokens(generic);
        LineIndexingService.Zones processedZones = redactLines(zones, redactionTokens, false);
        InvoiceOcrDocument processedDocument = redactDocument(document, redactionTokens, false);
        return new ProcessedTextContext(processedDocument, processedZones, redactionTokens);
    }

    private List<String> collectRedactionTokens(GenericExtraction generic) {
        List<String> redactionTokens = new ArrayList<>();
        if (generic == null) {
            return redactionTokens;
        }
        addRedactionToken(redactionTokens, valueOf(generic.invoiceNumber));
        addRedactionToken(redactionTokens, generic.gstins == null ? null : generic.gstins.getVendorGstin());
        addRedactionToken(redactionTokens, generic.gstins == null ? null : generic.gstins.getBuyerGstin());
        addRedactionToken(redactionTokens, valueOf(generic.poNumber));
        addRedactionToken(redactionTokens, valueOf(generic.invoiceDate));
        addRedactionToken(redactionTokens, valueOf(generic.vendorPhone));
        addRedactionToken(redactionTokens, valueOf(generic.pincode));
        addRedactionToken(redactionTokens, valueOf(generic.vehicleNumber));
        return redactionTokens;
    }

    private LineIndexingService.Zones redactLines(LineIndexingService.Zones zones,
                                                  List<String> redactionTokens,
                                                  boolean includeSensitivePatterns) {
        if (zones == null || zones.allLines.isEmpty()) {
            return zones;
        }
        List<LineIndexingService.IndexedLine> redactedLines = new ArrayList<>();
        for (LineIndexingService.IndexedLine line : zones.allLines) {
            String redacted = applyRedactions(line.getText(), redactionTokens, includeSensitivePatterns);
            redactedLines.add(new LineIndexingService.IndexedLine(
                    line.getLineNumber(),
                    redacted,
                    line.getOriginalText(),
                    line.getX(),
                    line.getY(),
                    Math.max(line.getWidth(), redacted == null ? 0 : redacted.length() * 8),
                    line.getHeight(),
                    line.getPageNumber(),
                    line.getColumn()
            ));
        }
        return LineIndexingService.indexLinesAndZones(redactedLines);
    }

    private InvoiceOcrDocument redactDocument(InvoiceOcrDocument document,
                                              List<String> redactionTokens,
                                              boolean includeSensitivePatterns) {
        if (document == null) {
            return InvoiceOcrDocument.single("");
        }
        List<com.invoice.extractor.model.InvoiceOcrPage> pages = new ArrayList<>();
        for (com.invoice.extractor.model.InvoiceOcrPage page : document.getPages()) {
            String redactedPageText = applyRedactionsPreservingLines(page.getText(), redactionTokens, includeSensitivePatterns);
            pages.add(new com.invoice.extractor.model.InvoiceOcrPage(page.getPageNumber(), page.getSourceName(), redactedPageText));
        }
        return new InvoiceOcrDocument(pages);
    }

    private void addRedactionToken(List<String> tokens, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        tokens.add(RegexUtil.normalizeLine(value));
    }

    private String redactToken(String text, String token) {
        if (text == null || text.isBlank() || token == null || token.isBlank()) {
            return text;
        }
        String redacted = text.replaceAll("(?i)" + spacedTokenPattern(token), " ###REMOVED### ");
        return RegexUtil.normalizeLine(redacted);
    }

    private String applyRedactions(String text, List<String> redactionTokens, boolean includeSensitivePatterns) {
        if (text == null || text.isBlank()) {
            return text;
        }
        String redacted = text;
        if (redactionTokens != null) {
            for (String token : redactionTokens) {
                redacted = redactToken(redacted, token);
            }
        }
        if (includeSensitivePatterns) {
            redacted = redactByPattern(redacted, EMAIL_REDACTION_PATTERN);
            redacted = redactByPattern(redacted, IFSC_REDACTION_PATTERN);
            redacted = redactByPattern(redacted, ACCOUNT_REDACTION_PATTERN);
        }
        return RegexUtil.normalizeLine(redacted);
    }

    private String applyRedactionsPreservingLines(String text,
                                                  List<String> redactionTokens,
                                                  boolean includeSensitivePatterns) {
        if (text == null || text.isBlank()) {
            return text;
        }
        String[] lines = text.split("\\R", -1);
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) {
                builder.append('\n');
            }
            builder.append(applyRedactions(lines[i], redactionTokens, includeSensitivePatterns));
        }
        return builder.toString();
    }

    private String redactByPattern(String text, java.util.regex.Pattern pattern) {
        if (text == null || text.isBlank() || pattern == null) {
            return text;
        }
        return pattern.matcher(text).replaceAll(" [[[REMOVED]]] ");
    }

    private String spacedTokenPattern(String value) {
        String normalized = value.replaceAll("[^A-Za-z0-9/._-]", "").toUpperCase();
        if (normalized.length() < 3) {
            return java.util.regex.Pattern.quote(value);
        }
        StringBuilder pattern = new StringBuilder();
        for (int i = 0; i < normalized.length(); i++) {
            char current = normalized.charAt(i);
            if (Character.isLetterOrDigit(current)) {
                if (pattern.length() > 0) {
                    pattern.append("\\s*");
                }
                pattern.append(java.util.regex.Pattern.quote(String.valueOf(current)));
            } else {
                pattern.append("\\s*").append(java.util.regex.Pattern.quote(String.valueOf(current))).append("\\s*");
            }
        }
        return pattern.toString();
    }

    private String stripPriorityLabel(String line, String label) {
        if (line == null) {
            return null;
        }
        return line.replaceFirst("(?i).*?" + java.util.regex.Pattern.quote(label)
                + "\\s*(?:no|number|date|dated)?\\s*(?:[:=#>|-]+\\s*)*", "");
    }

    private String collectFollowingValue(List<LineIndexingService.IndexedLine> lines, int lineNumber, List<String> labels) {
        if (lines == null) {
            return null;
        }
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).getLineNumber() != lineNumber) {
                continue;
            }
            for (int j = i + 1; j < lines.size() && j <= i + 2; j++) {
                String text = lines.get(j).getText();
                String lower = text.toLowerCase();
                if (looksLikePriorityBoundary(lower, labels)) {
                    break;
                }
                if (!text.trim().isEmpty()) {
                    return text;
                }
            }
        }
        return null;
    }

    private boolean looksLikePriorityBoundary(String lower, List<String> labels) {
        if (lower == null) {
            return true;
        }
        if (OcrLayoutUtil.looksLikeTableHeader(lower) || OcrLayoutUtil.isItemStopLine(lower)) {
            return true;
        }
        if (RegexUtil.containsAnyKeyword(lower, List.of("gstin", "invoice", "date", "bill to", "ship to", "buyer"))) {
            return true;
        }
        return labels != null && labels.stream().map(String::toLowerCase).anyMatch(lower::contains);
    }

    private String firstBusinessToken(String value) {
        if (value == null) {
            return null;
        }
        String normalized = RegexUtil.normalizeLine(value);
        if (normalized.isBlank()) {
            return null;
        }
        String[] tokens = normalized.split("\\s+");
        if (tokens.length == 0) {
            return null;
        }
        String candidate = tokens[0];
        if (candidate.length() < 3 && tokens.length > 1) {
            candidate = tokens[0] + tokens[1];
        }
        return candidate;
    }

    private Map<String, TemplateField> buildTemplateFields(LineIndexingService.Zones zones, GenericExtraction generic) {
        Map<String, TemplateField> fields = new HashMap<>();
        addTemplateField(fields, "invoiceNumber", generic.invoiceNumber, "TOP", INVOICE_KEYWORDS, zones.topZone);
        addTemplateField(fields, "invoiceDate", generic.invoiceDate, "TOP", DATE_KEYWORDS, zones.topZone);
        addTemplateField(fields, "vendorName", generic.vendorName, "TOP", List.of("ltd", "limited", "pvt", "corporation", "industries", "enterprises", "solutions"), zones.topZone);
        addTemplateField(fields, "vendorGstin", resultOf(generic.gstins.getVendorGstin(), generic.gstins.getVendorMethod(), generic.gstins.getVendorLineNumber()), "TOP", GSTIN_KEYWORDS, zones.topZone);
        addTemplateField(fields, "buyerName", generic.buyerName, "MIDDLE", BUYER_KEYWORDS, zones.middleZone);
        addTemplateField(fields, "buyerGstin", resultOf(generic.gstins.getBuyerGstin(), generic.gstins.getBuyerMethod(), generic.gstins.getBuyerLineNumber()), "MIDDLE", GSTIN_KEYWORDS, zones.middleZone);
        addTemplateField(fields, "subTotal", generic.subTotal, "BOTTOM", AmountUtil.SUBTOTAL_KEYWORDS, zones.bottomZone);
        addTemplateField(fields, "taxAmount", generic.taxAmount, "BOTTOM", AmountUtil.TAX_KEYWORDS, zones.bottomZone);
        addTemplateField(fields, "totalAmount", generic.totalAmount, "BOTTOM", AmountUtil.TOTAL_KEYWORDS, zones.bottomZone);

        if (zones.getTableHeaderLine() != null) {
            TemplateField tableHeader = new TemplateField();
            tableHeader.setLineNumber(zones.getTableHeaderLine().getLineNumber());
            tableHeader.setZone("TABLE");
            tableHeader.setRelativePosition(relativePosition(zones.tableZone, zones.getTableHeaderLine().getLineNumber()));
            tableHeader.setKeyword(inferKeyword(zones.tableZone, zones.getTableHeaderLine().getLineNumber(), List.of("description", "item", "qty", "quantity", "hsn", "sac")));
            fields.put("tableHeader", tableHeader);
        }
        if (zones.getTotalLine() != null) {
            TemplateField totalLine = new TemplateField();
            totalLine.setLineNumber(zones.getTotalLine().getLineNumber());
            totalLine.setZone("BOTTOM");
            totalLine.setRelativePosition(relativePosition(zones.bottomZone, zones.getTotalLine().getLineNumber()));
            totalLine.setKeyword(inferKeyword(zones.bottomZone, zones.getTotalLine().getLineNumber(), AmountUtil.TOTAL_KEYWORDS));
            fields.put("totalLine", totalLine);
        }
        if (zones.getTaxLine() != null) {
            TemplateField taxLine = new TemplateField();
            taxLine.setLineNumber(zones.getTaxLine().getLineNumber());
            taxLine.setZone("BOTTOM");
            taxLine.setRelativePosition(relativePosition(zones.bottomZone, zones.getTaxLine().getLineNumber()));
            taxLine.setKeyword(inferKeyword(zones.bottomZone, zones.getTaxLine().getLineNumber(), AmountUtil.TAX_KEYWORDS));
            fields.put("taxLine", taxLine);
        }
        return fields;
    }

    private void addTemplateField(Map<String, TemplateField> fields,
                                  String key,
                                  FieldExtractionResult<String> result,
                                  String zone,
                                  List<String> keywords,
                                  List<LineIndexingService.IndexedLine> zoneLines) {
        if (result == null || result.getValue() == null || result.getLineNumber() == null) {
            return;
        }
        TemplateField field = new TemplateField();
        field.setLineNumber(result.getLineNumber());
        field.setZone(zone);
        field.setRelativePosition(relativePosition(zoneLines, result.getLineNumber()));
        field.setKeyword(inferKeyword(zoneLines, result.getLineNumber(), keywords));
        fields.put(key, field);
    }

    private int relativePosition(List<LineIndexingService.IndexedLine> zoneLines, Integer lineNumber) {
        if (lineNumber == null) {
            return -1;
        }
        for (int i = 0; i < zoneLines.size(); i++) {
            if (zoneLines.get(i).getLineNumber() == lineNumber) {
                return i;
            }
        }
        return -1;
    }

    private String inferKeyword(List<LineIndexingService.IndexedLine> zoneLines, Integer lineNumber, List<String> keywords) {
        if (lineNumber == null) {
            return null;
        }
        for (LineIndexingService.IndexedLine line : zoneLines) {
            if (Math.abs(line.getLineNumber() - lineNumber) > 1) {
                continue;
            }
            for (String keyword : keywords) {
                if (line.getText().toLowerCase().contains(keyword.toLowerCase())) {
                    return keyword;
                }
            }
        }
        return null;
    }

    private String resolveField(String key,
                                String templateValue,
                                Predicate<String> validator,
                                FieldExtractionResult<String> genericResult,
                                Map<String, String> extractionMethod) {
        if (validator.test(templateValue)) {
            extractionMethod.put(key, "template");
            return templateValue;
        }
        extractionMethod.put(key, normalizeMethod(genericResult == null ? "fallback" : genericResult.getMethod()));
        return genericResult == null ? null : genericResult.getValue();
    }

    private List<LineItem> selectLineItems(List<LineItem> templateItems, List<LineItem> genericItems, Map<String, String> extractionMethod) {
        if (templateItems != null && !templateItems.isEmpty()
                && (genericItems == null || genericItems.isEmpty() || templateItems.size() >= genericItems.size())) {
            extractionMethod.put("lineItems", "template");
            return templateItems;
        }
        extractionMethod.put("lineItems", genericItems != null && !genericItems.isEmpty() ? "zone-keyword" : "fallback");
        return genericItems;
    }

    private void normalizeAmounts(InvoiceData data,
                                  Map<String, String> extractionMethod,
                                  GenericExtraction generic,
                                  LineIndexingService.Zones zones) {
        Double total = AmountUtil.parseAmount(data.getTotalAmount());
        Double tax = AmountUtil.parseAmount(data.getTaxAmount());
        Double subtotal = AmountUtil.parseAmount(data.getSubTotal());
        Double wordsTotal = AmountUtil.extractAmountFromWords(zones.bottomZone, AmountUtil.TOTAL_KEYWORDS);
        AmountUtil.SummaryAmounts summaryAmounts = AmountUtil.extractSummaryAmounts(zones.bottomZone);

        if (summaryAmounts != null) {
            if ((subtotal == null || subtotal <= 0 || (total != null && subtotal >= total)) && summaryAmounts.getSubtotal() != null) {
                subtotal = summaryAmounts.getSubtotal();
                data.setSubTotal(AmountUtil.formatAmount(subtotal));
                extractionMethod.put("subtotal", "fallback");
            }
            if ((tax == null || tax <= 0 || (total != null && tax >= total)) && summaryAmounts.getTax() != null) {
                tax = summaryAmounts.getTax();
                data.setTaxAmount(AmountUtil.formatAmount(tax));
                extractionMethod.put("tax", "fallback");
            }
        }

        if (total == null || !isLargestAmountInBottomZone(total, zones)) {
            data.setTotalAmount(generic.totalAmount.getValue());
            extractionMethod.put("total", generic.totalAmount.getMethod());
            total = AmountUtil.parseAmount(data.getTotalAmount());
        }
        if (wordsTotal != null
                && (total == null
                || (subtotal != null && total <= subtotal)
                || (tax != null && subtotal != null && !isAmountConsistent(subtotal, tax, total)
                && isAmountConsistent(subtotal, tax, wordsTotal)))) {
            total = wordsTotal;
            data.setTotalAmount(AmountUtil.formatAmount(total));
            extractionMethod.put("total", "fallback");
        }
        if (tax != null && total != null && tax >= total) {
            tax = AmountUtil.parseAmount(generic.taxAmount.getValue());
            data.setTaxAmount(generic.taxAmount.getValue());
            extractionMethod.put("tax", generic.taxAmount.getMethod());
        }
        if ((subtotal != null && total != null && subtotal >= total) || subtotal == null) {
            if (total != null && tax != null && tax < total) {
                subtotal = total - tax;
                data.setSubTotal(AmountUtil.formatAmount(subtotal));
                extractionMethod.put("subtotal", "fallback");
            } else {
                data.setSubTotal(generic.subTotal.getValue());
                extractionMethod.put("subtotal", generic.subTotal.getMethod());
                subtotal = AmountUtil.parseAmount(data.getSubTotal());
            }
        }
        if ((tax == null || tax <= 0) && total != null && subtotal != null && subtotal < total) {
            data.setTaxAmount(AmountUtil.formatAmount(total - subtotal));
            extractionMethod.put("tax", "fallback");
            tax = AmountUtil.parseAmount(data.getTaxAmount());
        }
        if (total != null && tax != null && subtotal == null && tax < total) {
            subtotal = total - tax;
            if (subtotal > AmountUtil.MIN_SIGNIFICANT_AMOUNT) {
                data.setSubTotal(AmountUtil.formatAmount(subtotal));
                extractionMethod.put("subtotal", "fallback");
            }
        }
        Double lineItemSum = sumLineItemAmounts(data.getLineItems());
        if (lineItemSum != null) {
            boolean preferLineItemSubtotal = subtotal == null
                    || subtotal <= 0
                    || (total != null && subtotal > total)
                    || (tax != null && total != null
                    && AmountUtil.approximatelyEquals(total, lineItemSum)
                    && lineItemSum > subtotal);
            if (preferLineItemSubtotal && (total == null || lineItemSum <= total * 1.1 || tax != null)) {
                subtotal = lineItemSum;
                data.setSubTotal(AmountUtil.formatAmount(subtotal));
                extractionMethod.put("subtotal", "fallback");
            }
            if (tax != null && tax > 0) {
                Double recomputedFromItems = lineItemSum + tax;
                boolean totalLooksLikeTaxableValue = total == null
                        || total <= lineItemSum
                        || AmountUtil.approximatelyEquals(total, lineItemSum)
                        || !isAmountConsistent(lineItemSum, tax, total);
                if (totalLooksLikeTaxableValue && bottomZoneContainsAmount(recomputedFromItems, zones)) {
                    total = recomputedFromItems;
                    subtotal = lineItemSum;
                    data.setSubTotal(AmountUtil.formatAmount(subtotal));
                    data.setTotalAmount(AmountUtil.formatAmount(total));
                    extractionMethod.put("subtotal", "fallback");
                    extractionMethod.put("total", "fallback");
                }
            }
        }
        if (total != null && subtotal != null && tax != null && !isAmountConsistent(subtotal, tax, total)) {
            Double recomputedTotal = subtotal + tax;
            if (bottomZoneContainsAmount(recomputedTotal, zones)
                    || (lineItemSum != null && AmountUtil.approximatelyEquals(lineItemSum, subtotal) && recomputedTotal > subtotal)) {
                data.setTotalAmount(AmountUtil.formatAmount(recomputedTotal));
                extractionMethod.put("total", "fallback");
                total = recomputedTotal;
            }
        }
        if (wordsTotal != null && tax != null && wordsTotal > tax && (total == null || !AmountUtil.approximatelyEquals(total, wordsTotal))) {
            total = wordsTotal;
            data.setTotalAmount(AmountUtil.formatAmount(total));
            extractionMethod.put("total", "fallback");
        }
        if (wordsTotal != null && tax != null && wordsTotal > tax
                && (subtotal == null || !isAmountConsistent(subtotal, tax, wordsTotal))) {
            subtotal = wordsTotal - tax;
            if (subtotal > 0 && subtotal < wordsTotal) {
                data.setSubTotal(AmountUtil.formatAmount(subtotal));
                extractionMethod.put("subtotal", "fallback");
            }
        }
        if (subtotal != null && tax != null && (total == null || total <= tax || total <= subtotal)) {
            total = subtotal + tax;
            data.setTotalAmount(AmountUtil.formatAmount(total));
            extractionMethod.put("total", "fallback");
        }
        if (subtotal != null) {
            data.setSubTotal(AmountUtil.formatAmount(subtotal));
        }
        if (tax != null) {
            data.setTaxAmount(AmountUtil.formatAmount(tax));
        }
        if (total != null) {
            data.setTotalAmount(AmountUtil.formatAmount(total));
        }
    }

    private FieldExtractionResult<String> resultOf(String value, String method, Integer lineNumber) {
        return new FieldExtractionResult<>(value, method == null ? "fallback" : normalizeMethod(method), lineNumber);
    }

    private boolean looksLikeAmount(String value) {
        Double amount = AmountUtil.parseAmount(value);
        return amount != null && amount > AmountUtil.MIN_SIGNIFICANT_AMOUNT;
    }

    private boolean isValidInvoiceNumber(String value) {
        return cleanInvoiceNumberValue(value) != null;
    }

    private boolean isValidGstin(String value) {
        return RegexUtil.isValidGstin(value);
    }

    private boolean isValidName(String value) {
        if (value == null || !value.matches(".*[A-Za-z].*") || value.matches("\\d+")) {
            return false;
        }
        String cleaned = value.replaceAll("[^A-Za-z0-9 ]", " ").trim();
        String[] words = cleaned.split("\\s+");
        int alphaWords = 0;
        int digitChars = 0;
        int letterChars = 0;
        for (char ch : value.toCharArray()) {
            if (Character.isDigit(ch)) {
                digitChars++;
            } else if (Character.isLetter(ch)) {
                letterChars++;
            }
        }
        for (String word : words) {
            if (word.matches(".*[A-Za-z].*") && !word.matches(".*\\d.*")) {
                alphaWords++;
            }
        }
        if (alphaWords < 2) {
            return false;
        }
        if (digitChars > 0 && digitChars >= letterChars) {
            return false;
        }
        String lower = value.toLowerCase();
        return !lower.contains("invoice no") && !lower.contains("dated");
    }

    private boolean isValidVendorName(String value) {
        if (!isValidName(value)) {
            return false;
        }
        String lower = value.toLowerCase();
        if (lower.contains("description") || lower.contains("goods") || lower.contains("invoice details")
                || lower.contains("dispatch") || lower.contains("delivery note") || lower.contains("mode/terms")
                || lower.contains("reference no") || lower.contains("bank") || lower.contains("ifsc")
                || lower.contains("account") || lower.contains("ack no") || lower.contains("ack date")
                || lower.contains("consignee") || lower.contains("bill to") || lower.contains("billed to")
                || lower.contains("ship to") || lower.contains("buyer") || lower.contains("department of atomic energy")) {
            return false;
        }
        if (lower.contains("manager") || lower.contains("officer") || lower.contains("directorate")
                || lower.contains("purchase") || lower.contains("stores")) {
            return false;
        }
        return !containsNameNoise(lower);
    }

    private boolean isValidBuyerName(String value) {
        if (!isValidName(value)) {
            return false;
        }
        String lower = value.toLowerCase();
        if (lower.contains("description") || lower.contains("invoice details") || lower.contains("nvoice detals")
                || lower.contains("dispatch") || lower.contains("delivery note") || lower.contains("mode/terms")
                || lower.contains("reference no") || lower.contains("buyers order") || lower.contains("buyer's order")
                || lower.contains("purchase order") || lower.contains("bank") || lower.contains("ifsc")
                || lower.contains("account") || lower.contains("party pincode") || lower.contains("party e-mail")
                || lower.contains("party mobile")) {
            return false;
        }
        return !containsNameNoise(lower);
    }

    private boolean isValidTotal(String value, LineIndexingService.Zones zones) {
        Double amount = AmountUtil.parseAmount(value);
        return amount != null && amount > AmountUtil.MIN_SIGNIFICANT_AMOUNT && isLargestAmountInBottomZone(amount, zones);
    }

    private boolean isValidTax(String value, String totalValue) {
        Double tax = AmountUtil.parseAmount(value);
        Double total = AmountUtil.parseAmount(totalValue);
        return tax != null && total != null && tax > AmountUtil.MIN_SIGNIFICANT_AMOUNT && tax < total;
    }

    private boolean isValidSubtotal(String value, String totalValue) {
        Double subtotal = AmountUtil.parseAmount(value);
        Double total = AmountUtil.parseAmount(totalValue);
        return subtotal != null && total != null && subtotal > AmountUtil.MIN_SIGNIFICANT_AMOUNT && subtotal < total;
    }

    private boolean isLargestAmountInBottomZone(Double amount, LineIndexingService.Zones zones) {
        double max = 0.0;
        for (var candidate : AmountUtil.extractCandidates(zones.bottomZone)) {
            String lineText = candidate.getLine().getText();
            boolean lineHasCurrencyToken = AmountUtil.extractRawNumericTokens(lineText).stream().anyMatch(AmountUtil::looksLikeCurrencyToken);
            if (candidate.isPercentToken() || AmountUtil.isIgnoredAmountLine(lineText)) {
                continue;
            }
            String lower = lineText.toLowerCase();
            if (AmountUtil.isTaxLine(lineText)
                    && !(lower.contains("grand total") || lower.contains("invoice value")
                    || lower.contains("amount payable") || lower.contains("after tax")
                    || lower.matches("^total\\b.*"))) {
                continue;
            }
            if (!AmountUtil.looksLikeCurrencyToken(candidate.getToken()) && lineHasCurrencyToken) {
                continue;
            }
            if (!AmountUtil.isPreferredAmountLine(lineText, AmountUtil.TOTAL_KEYWORDS)
                    && !AmountUtil.looksLikeCurrencyToken(candidate.getToken())) {
                continue;
            }
            max = Math.max(max, candidate.getValue());
        }
        return amount != null && max > 0 && Double.compare(amount, max) == 0;
    }

    private boolean bottomZoneContainsAmount(Double amount, LineIndexingService.Zones zones) {
        if (amount == null) {
            return false;
        }
        for (var candidate : AmountUtil.extractCandidates(zones.bottomZone)) {
            if (candidate.isPercentToken()) {
                continue;
            }
            if (AmountUtil.approximatelyEquals(candidate.getValue(), amount)) {
                return true;
            }
        }
        return false;
    }

    private List<LineItem> sanitizeLineItems(List<LineItem> items, String subtotalValue, String totalValue) {
        if (items == null || items.isEmpty()) {
            return items;
        }
        Double subtotal = AmountUtil.parseAmount(subtotalValue);
        Double total = AmountUtil.parseAmount(totalValue);
        Double ceiling = total != null ? total : subtotal;
        List<LineItem> cleaned = new java.util.ArrayList<>();
        for (LineItem item : items) {
            if (item == null) {
                continue;
            }
            normalizeLineItem(item);
            String description = item.getDescription() == null ? "" : item.getDescription().toLowerCase();
            Double amount = AmountUtil.parseAmount(item.getAmount());
            if (description.contains("seal nos") || description.contains("remarks") || description.contains("batch no")) {
                continue;
            }
            if (ceiling != null && amount != null && amount > ceiling * 1.25) {
                continue;
            }
            if (!isHighPrecisionLineItem(item, ceiling)) {
                continue;
            }
            cleaned.add(item);
        }
        List<LineItem> deduped = new java.util.ArrayList<>();
        for (LineItem item : cleaned) {
            int duplicateIndex = findDuplicateIndex(deduped, item, ceiling);
            if (duplicateIndex >= 0) {
                if (scoreLineItem(item) > scoreLineItem(deduped.get(duplicateIndex))) {
                    deduped.set(duplicateIndex, item);
                }
                continue;
            }
            deduped.add(item);
        }
        return deduped;
    }

    private Double sumLineItemAmounts(List<LineItem> items) {
        if (items == null || items.isEmpty()) {
            return null;
        }
        double sum = 0.0;
        int count = 0;
        for (LineItem item : items) {
            Double amount = AmountUtil.parseAmount(item.getAmount());
            if (amount == null || amount <= 0) {
                continue;
            }
            sum += amount;
            count++;
        }
        return count == 0 ? null : sum;
    }

    private int findDuplicateIndex(List<LineItem> items, LineItem candidate, Double ceiling) {
        Double candidateAmount = AmountUtil.parseAmount(candidate.getAmount());
        if (candidateAmount == null || ceiling == null || candidateAmount < ceiling * 0.75) {
            return -1;
        }
        for (int i = 0; i < items.size(); i++) {
            Double existingAmount = AmountUtil.parseAmount(items.get(i).getAmount());
            if (existingAmount != null && AmountUtil.approximatelyEquals(existingAmount, candidateAmount)) {
                return i;
            }
        }
        return -1;
    }

    private int scoreLineItem(LineItem item) {
        int score = 0;
        String description = item.getDescription() == null ? "" : item.getDescription();
        String lower = description.toLowerCase();
        if (description.matches(".*[A-Za-z].*")) {
            score += 30;
        }
        if (description.split("\\s+").length >= 3) {
            score += 20;
        }
        if (item.getQuantity() != null) {
            score += 15;
        }
        if (item.getUnitPrice() != null) {
            score += 15;
        }
        if (item.getHsn() != null && item.getHsn().matches("\\d{4,8}")) {
            score += 10;
        }
        if (lower.contains("code") || lower.contains("value (rs")) {
            score -= 20;
        }
        int punctuation = 0;
        for (char ch : description.toCharArray()) {
            if (!Character.isLetterOrDigit(ch) && !Character.isWhitespace(ch)) {
                punctuation++;
            }
        }
        if (punctuation > Math.max(4, description.length() / 5)) {
            score -= 20;
        }
        return score;
    }

    private void normalizeEntityAssignments(InvoiceData data) {
        if (sameNormalizedValue(data.getVendorName(), data.getBuyerName())
                && (sameNormalizedValue(data.getVendorGstin(), data.getBuyerGstin()) || data.getBuyerGstin() == null)) {
            data.setBuyerName(null);
        }
        if (sameNormalizedValue(data.getVendorGstin(), data.getBuyerGstin())
                && sameNormalizedValue(data.getVendorName(), data.getBuyerName())) {
            data.setBuyerGstin(null);
        }
    }

    private void scrubInvalidIdentityFields(InvoiceData data) {
        if (!isValidVendorName(data.getVendorName())) {
            data.setVendorName(null);
        }
        if (!isValidBuyerName(data.getBuyerName())) {
            data.setBuyerName(null);
        }
        if (!isValidGstin(data.getVendorGstin())) {
            data.setVendorGstin(null);
        }
        if (!isValidGstin(data.getBuyerGstin())
                || sameNormalizedValue(data.getVendorGstin(), data.getBuyerGstin())) {
            data.setBuyerGstin(null);
        }
        if (sameNormalizedValue(data.getVendorName(), data.getBuyerName())) {
            data.setBuyerName(null);
        }
        // Detect swapped GSTIN roles: if vendor GSTIN belongs to a known government
        // entity (buyer), swap them.
        if (data.getVendorGstin() != null && data.getBuyerGstin() == null) {
            if (isGovernmentEntityGstin(data.getVendorGstin())) {
                data.setBuyerGstin(data.getVendorGstin());
                data.setVendorGstin(null);
            }
        }
    }

    /**
     * Detect GSTINs belonging to known government entities (DAE, NFC, ECIL, etc.)
     * that should be assigned the buyer role, not vendor.
     */
    private boolean isGovernmentEntityGstin(String gstin) {
        if (gstin == null || gstin.length() < 15) {
            return false;
        }
        // Extract embedded PAN (characters 2-12)
        String pan = gstin.substring(2, 12);
        // Known government PANs
        if (Set.of("AAAGN1030Q", "AAAGD0290L", "AAAGE0014G", "AAAGB0282M").contains(pan)) {
            return true;
        }
        // Government PANs typically have 'A' as the 4th character (Association/AOP)
        // and are registered under GSTIN entity code '1' (central/state govt)
        return false;
    }

    private boolean sameNormalizedValue(String left, String right) {
        if (left == null || right == null) {
            return false;
        }
        return RegexUtil.normalizeForComparison(left).equals(RegexUtil.normalizeForComparison(right));
    }

    private String determineStatus(InvoiceData data,
                                   LineIndexingService.Zones zones,
                                   String rawText,
                                   double confidence) {
        boolean invoiceNumberValid = isValidInvoiceNumber(data.getInvoiceNumber());
        boolean vendorIdentityValid = isValidVendorName(data.getVendorName()) && isValidGstin(data.getVendorGstin());
        boolean buyerIdentityPresent = isValidBuyerName(data.getBuyerName()) || isValidGstin(data.getBuyerGstin());
        boolean buyerIdentityStrong = isValidBuyerName(data.getBuyerName())
                || (isValidGstin(data.getBuyerGstin()) && isCleanBusinessText(data.getBuyerAddress(), 10, 240));
        boolean totalValid = isValidTotal(data.getTotalAmount(), zones);

        Double subtotal = AmountUtil.parseAmount(data.getSubTotal());
        Double tax = AmountUtil.parseAmount(data.getTaxAmount());
        Double total = AmountUtil.parseAmount(data.getTotalAmount());
        boolean mathConsistent = isAmountConsistent(subtotal, tax, total);
        String lower = rawText == null ? "" : rawText.toLowerCase();
        boolean taxDocument = lower.contains("tax invoice")
                || lower.contains("igst")
                || lower.contains("cgst")
                || lower.contains("sgst");
        boolean amountValidationCorrect = totalValid
                && (!taxDocument
                || mathConsistent
                || (subtotal == null && tax == null)
                || (subtotal != null && subtotal < total && tax == null)
                || (tax != null && tax < total && subtotal == null));
        boolean coreValid = invoiceNumberValid && vendorIdentityValid && totalValid;
        boolean buyerUsable = buyerIdentityPresent || isCleanBusinessText(data.getBuyerAddress(), 10, 240);

        if (!coreValid || confidence < 0.52) {
            return "FAILED";
        }
        if (confidence >= 0.80 && buyerUsable && buyerIdentityStrong && amountValidationCorrect) {
            return "SUCCESS";
        }
        if (confidence >= 0.65 && (!taxDocument || amountValidationCorrect)) {
            return "PARTIAL_SUCCESS";
        }
        return coreValid ? "PARTIAL_SUCCESS" : "FAILED";
    }

    private boolean shouldLearnTemplate(Template template,
                                        InvoiceData data,
                                        double confidence,
                                        LineIndexingService.Zones zones,
                                        Map<String, TemplateField> templateFields) {
        boolean anchorIdentity = isValidVendorName(data.getVendorName()) || isValidGstin(data.getVendorGstin());
        boolean keyAmountsValid = isValidTotal(data.getTotalAmount(), zones)
                && (data.getSubTotal() == null || data.getTaxAmount() == null
                || isAmountConsistent(AmountUtil.parseAmount(data.getSubTotal()), AmountUtil.parseAmount(data.getTaxAmount()), AmountUtil.parseAmount(data.getTotalAmount())));
        boolean keyDocumentFields = isValidInvoiceNumber(data.getInvoiceNumber())
                && DateUtil.isValidInvoiceDate(data.getInvoiceDate());
        int fieldCount = templateFields == null ? 0 : templateFields.size();
        return confidence >= 0.60
                && anchorIdentity
                && keyAmountsValid
                && (keyDocumentFields || fieldCount >= 5)
                && ("SUCCESS".equals(data.getStatus()) || confidence >= 0.75 || fieldCount >= 7);
    }

    private String normalizeMethod(String method) {
        if (method == null || method.isBlank() || "fallback".equals(method) || "template".equals(method) || method.contains("zone-")) {
            return method == null ? "fallback" : method;
        }
        return "zone-" + method;
    }

    private boolean isAmountConsistent(Double subtotal, Double tax, Double total) {
        if (subtotal == null || tax == null || total == null) {
            return false;
        }
        return AmountUtil.approximatelyEquals(subtotal + tax, total);
    }

    private void reconcileIdentityFields(InvoiceData data,
                                         GenericExtraction generic,
                                         Map<String, String> extractionMethod) {
        if (generic == null) {
            return;
        }
        String genericVendorName = generic.vendorName == null ? null : generic.vendorName.getValue();
        String genericBuyerName = generic.buyerName == null ? null : generic.buyerName.getValue();
        String genericVendorGstin = generic.gstins == null ? null : generic.gstins.getVendorGstin();
        String genericBuyerGstin = generic.gstins == null ? null : generic.gstins.getBuyerGstin();

        if (shouldPreferVendorName(genericVendorName, data.getVendorName())) {
            data.setVendorName(genericVendorName);
            extractionMethod.put("vendor", normalizeMethod(generic.vendorName.getMethod()));
        }
        if (shouldPreferBuyerName(genericBuyerName, data.getBuyerName())) {
            data.setBuyerName(genericBuyerName);
            extractionMethod.put("buyer", normalizeMethod(generic.buyerName.getMethod()));
        }
        if (RegexUtil.isValidGstin(genericVendorGstin)
                && (!RegexUtil.isValidGstin(data.getVendorGstin())
                || sameNormalizedValue(data.getVendorGstin(), data.getBuyerGstin()) && !sameNormalizedValue(genericVendorGstin, genericBuyerGstin))) {
            data.setVendorGstin(genericVendorGstin);
            extractionMethod.put("gstin", normalizeMethod(generic.gstins.getVendorMethod()));
        }
        if (RegexUtil.isValidGstin(genericBuyerGstin)
                && (!RegexUtil.isValidGstin(data.getBuyerGstin())
                || sameNormalizedValue(data.getBuyerGstin(), data.getVendorGstin()) && !sameNormalizedValue(genericVendorGstin, genericBuyerGstin))) {
            data.setBuyerGstin(genericBuyerGstin);
            extractionMethod.put("buyerGstin", normalizeMethod(generic.gstins.getBuyerMethod()));
        }
    }

    private void reconcileCoreDocumentFields(InvoiceData data,
                                             GenericExtraction generic,
                                             Map<String, String> extractionMethod) {
        if (generic == null) {
            return;
        }
        if (shouldPreferInvoiceNumber(generic.invoiceNumber, data.getInvoiceNumber())) {
            data.setInvoiceNumber(generic.invoiceNumber.getValue());
            extractionMethod.put("invoiceNumber", normalizeMethod(generic.invoiceNumber.getMethod()));
        }
        if (shouldPreferInvoiceDate(generic.invoiceDate, data.getInvoiceDate())) {
            data.setInvoiceDate(generic.invoiceDate.getValue());
            extractionMethod.put("invoiceDate", normalizeMethod(generic.invoiceDate.getMethod()));
        }
        if (shouldPreferAmount(generic.totalAmount, data.getTotalAmount())) {
            data.setTotalAmount(generic.totalAmount.getValue());
            extractionMethod.put("total", normalizeMethod(generic.totalAmount.getMethod()));
        }
    }

    private boolean shouldPreferInvoiceNumber(FieldExtractionResult<String> candidate, String current) {
        if (candidate == null || !isValidInvoiceNumber(candidate.getValue())) {
            return false;
        }
        if (!isValidInvoiceNumber(current)) {
            return true;
        }
        int candidateScore = scoreInvoiceNumber(candidate.getValue(), candidate.getMethod());
        int currentScore = scoreInvoiceNumber(current, null);
        return candidateScore > currentScore + 8;
    }

    private boolean shouldPreferInvoiceDate(FieldExtractionResult<String> candidate, String current) {
        if (candidate == null || !DateUtil.isValidInvoiceDate(candidate.getValue())) {
            return false;
        }
        if (!DateUtil.isValidInvoiceDate(current)) {
            return true;
        }
        return Math.abs(candidate.getValue().length() - 10) < Math.abs(current.length() - 10);
    }

    private boolean shouldPreferAmount(FieldExtractionResult<String> candidate, String current) {
        Double candidateAmount = candidate == null ? null : AmountUtil.parseAmount(candidate.getValue());
        Double currentAmount = AmountUtil.parseAmount(current);
        if (candidateAmount == null) {
            return false;
        }
        if (currentAmount == null) {
            return true;
        }
        return candidateAmount > currentAmount && currentAmount < AmountUtil.MIN_SIGNIFICANT_AMOUNT * 10;
    }

    private int scoreInvoiceNumber(String value, String method) {
        if (value == null) {
            return Integer.MIN_VALUE;
        }
        String normalized = RegexUtil.repairInvoiceNumberCandidate(value).toUpperCase();
        if (!isValidInvoiceNumber(normalized)) {
            return Integer.MIN_VALUE / 2;
        }
        int score = normalized.length() * 2;
        if (normalized.contains("/") || normalized.contains("-")) {
            score += 30;
        }
        if (normalized.matches("^\\d{1,12}$")) {
            score += method != null && method.contains("keyword") ? 128 : -12;
        }
        if (normalized.matches(".*[A-Z].*") && normalized.matches(".*\\d.*")) {
            score += 18;
        }
        if (method != null && method.contains("keyword")) {
            score += 12;
        }
        return score;
    }

    private boolean shouldPreferVendorName(String candidate, String current) {
        if (!isValidVendorName(candidate)) {
            return false;
        }
        if (!isValidVendorName(current)) {
            return true;
        }
        return scoreVendorName(candidate) > scoreVendorName(current) + 10;
    }

    private boolean shouldPreferBuyerName(String candidate, String current) {
        if (!isValidBuyerName(candidate)) {
            return false;
        }
        if (!isValidBuyerName(current)) {
            return true;
        }
        return scoreBuyerName(candidate) > scoreBuyerName(current) + 10;
    }

    private int scoreVendorName(String value) {
        int score = 0;
        String lower = value.toLowerCase();
        if (RegexUtil.containsAnyKeyword(lower, List.of("ltd", "limited", "pvt", "private", "llp", "industries",
                "corporation", "engineering", "electronics", "hydraulics", "solutions", "chemicals", "systems", "products"))) {
            score += 45;
        }
        if (value.equals(value.toUpperCase()) && value.matches(".*[A-Z].*")) {
            score += 15;
        }
        score += Math.max(0, 30 - value.length() / 3);
        if (value.contains(",")) {
            score -= 12;
        }
        if (containsNameNoise(lower)) {
            score -= 50;
        }
        return score;
    }

    private int scoreBuyerName(String value) {
        int score = 0;
        String lower = value.toLowerCase();
        if (RegexUtil.containsAnyKeyword(lower, List.of("m/s", "department", "directorate", "stores", "officer",
                "manager", "materials", "complex", "atomic", "fuel", "regional", "unit", "plant"))) {
            score += 35;
        }
        if (!value.matches(".*\\d.*")) {
            score += 10;
        }
        score += Math.max(0, 24 - value.length() / 4);
        if (containsNameNoise(lower)) {
            score -= 50;
        }
        return score;
    }

    private boolean containsNameNoise(String lower) {
        return OcrLayoutUtil.isLogisticsLike(lower)
                || OcrLayoutUtil.isHeaderNoise(lower)
                || lower.contains("ship to")
                || lower.contains("shipped to")
                || lower.contains("bill to")
                || lower.contains("billed to")
                || lower.contains("consignee")
                || lower.contains("invoice no")
                || lower.contains("dated")
                || lower.contains("voucher")
                || lower.contains("amount")
                || lower.contains("gstin")
                || lower.contains("buyers order")
                || lower.contains("buyer's order")
                || lower.contains("uyers order")
                || lower.contains("terms of delivery")
                || lower.contains("dispatch doc")
                || lower.contains("reference no")
                || lower.contains("state code")
                || lower.contains("pin code")
                || lower.contains("place of supply");
    }

    private void applySupplementalFields(InvoiceData data, InvoiceSupplementalFieldExtractor.Result supplementalFields) {
        if (supplementalFields == null) {
            return;
        }
        data.setPoNumber(preferNonBlank(data.getPoNumber(), supplementalFields.getPoNumber()));
        data.setTransportDetails(preferLongerValue(data.getTransportDetails(), supplementalFields.getTransportDetails()));
        data.setVehicleNumber(preferNonBlank(data.getVehicleNumber(), supplementalFields.getVehicleNumber()));
        data.setPaymentTerms(preferLongerValue(data.getPaymentTerms(), supplementalFields.getPaymentTerms()));
        data.setBankDetails(preferLongerValue(data.getBankDetails(), supplementalFields.getBankDetails()));
        data.setIrn(preferNonBlank(data.getIrn(), supplementalFields.getIrn()));
        data.setAckNumber(preferNonBlank(data.getAckNumber(), supplementalFields.getAckNumber()));
        data.setEwayBill(preferNonBlank(data.getEwayBill(), supplementalFields.getEwayBill()));
    }

    private void applyUniversalFields(InvoiceData data, InvoiceUniversalFieldExtractor.Result universalFields) {
        if (universalFields == null) {
            return;
        }
        data.setPoDate(preferNonBlank(data.getPoDate(), universalFields.getPoDate()));
        data.setOrderReference(preferNonBlank(data.getOrderReference(), universalFields.getOrderReference()));
        data.setDeliveryNote(preferNonBlank(data.getDeliveryNote(), universalFields.getDeliveryNote()));
        data.setDispatchThrough(preferLongerValue(data.getDispatchThrough(), universalFields.getDispatchThrough()));
        data.setTransporterName(preferLongerValue(data.getTransporterName(), universalFields.getTransporterName()));
        data.setTransportDetails(preferLongerValue(data.getTransportDetails(), universalFields.getTransportDetails()));
        data.setDestination(preferNonBlank(data.getDestination(), universalFields.getDestination()));
        data.setPlaceOfSupply(preferNonBlank(data.getPlaceOfSupply(), universalFields.getPlaceOfSupply()));
        data.setBankName(preferLongerValue(data.getBankName(), universalFields.getBankName()));
        data.setAccountNumber(preferNonBlank(data.getAccountNumber(), universalFields.getAccountNumber()));
        data.setIfscCode(preferNonBlank(data.getIfscCode(), universalFields.getIfscCode()));
        data.setBranch(preferLongerValue(data.getBranch(), universalFields.getBranch()));
        data.setVendorPhone(preferLongerValue(data.getVendorPhone(), universalFields.getVendorPhone()));
        data.setVendorEmail(preferNonBlank(data.getVendorEmail(), universalFields.getVendorEmail()));
        data.setVendorWebsite(preferNonBlank(data.getVendorWebsite(), universalFields.getVendorWebsite()));
        data.setVendorAddress(preferLongerValue(data.getVendorAddress(), universalFields.getVendorAddress()));
        data.setBuyerAddress(preferLongerValue(data.getBuyerAddress(), universalFields.getBuyerAddress()));
        data.setVendorPAN(preferNonBlank(data.getVendorPAN(), universalFields.getVendorPAN()));
        data.setVendorCIN(preferNonBlank(data.getVendorCIN(), universalFields.getVendorCIN()));
        data.setMsmeNumber(preferNonBlank(data.getMsmeNumber(), universalFields.getMsmeNumber()));
        data.setState(preferNonBlank(data.getState(), universalFields.getState()));
        data.setStateCode(preferNonBlank(data.getStateCode(), universalFields.getStateCode()));
        data.setPincode(preferNonBlank(data.getPincode(), universalFields.getPincode()));
        data.setTaxableValue(preferNonBlank(data.getTaxableValue(), universalFields.getTaxableValue(), data.getSubTotal()));
        data.setCgst(preferNonBlank(data.getCgst(), universalFields.getCgst()));
        data.setSgst(preferNonBlank(data.getSgst(), universalFields.getSgst()));
        data.setIgst(preferNonBlank(data.getIgst(), universalFields.getIgst()));
        data.setRoundOff(preferNonBlank(data.getRoundOff(), universalFields.getRoundOff()));
    }

    private void hydrateSummaryAmounts(InvoiceData data, LineIndexingService.Zones zones) {
        if (zones == null) {
            return;
        }
        AmountUtil.SummaryAmounts summary = AmountUtil.extractSummaryAmounts(zones.bottomZone);
        Double keywordSubtotal = AmountUtil.extractBestAmountByKeywords(zones.bottomZone, AmountUtil.SUBTOTAL_KEYWORDS, true);
        Double keywordTax = AmountUtil.extractBestAmountByKeywords(zones.bottomZone, AmountUtil.TAX_KEYWORDS, true);
        Double keywordTotal = AmountUtil.extractBestAmountByKeywords(zones.bottomZone, AmountUtil.TOTAL_KEYWORDS, true);
        Double keywordSubtotalAll = AmountUtil.extractBestAmountByKeywords(zones.allLines, AmountUtil.SUBTOTAL_KEYWORDS, true);
        Double keywordTaxAll = AmountUtil.extractBestAmountByKeywords(zones.allLines, AmountUtil.TAX_KEYWORDS, true);
        Double keywordTotalAll = AmountUtil.extractBestAmountByKeywords(zones.allLines, AmountUtil.TOTAL_KEYWORDS, true);
        if (keywordSubtotal == null || (keywordSubtotalAll != null && keywordSubtotalAll > keywordSubtotal)) {
            keywordSubtotal = keywordSubtotalAll;
        }
        if (keywordTax == null || (keywordTaxAll != null && keywordTaxAll > keywordTax)) {
            keywordTax = keywordTaxAll;
        }
        if (keywordTotal == null || (keywordTotalAll != null && keywordTotalAll > keywordTotal)) {
            keywordTotal = keywordTotalAll;
        }
        if (summary != null) {
            if (data.getSubTotal() == null && summary.getSubtotal() != null) {
                data.setSubTotal(AmountUtil.formatAmount(summary.getSubtotal()));
            }
            if (data.getTaxableValue() == null && summary.getSubtotal() != null) {
                data.setTaxableValue(AmountUtil.formatAmount(summary.getSubtotal()));
            }
            if (data.getTaxAmount() == null && summary.getTax() != null) {
                data.setTaxAmount(AmountUtil.formatAmount(summary.getTax()));
            }
        }
        Double subtotal = AmountUtil.parseAmount(data.getSubTotal());
        Double tax = AmountUtil.parseAmount(data.getTaxAmount());
        Double total = AmountUtil.parseAmount(data.getTotalAmount());
        if ((subtotal == null || (total != null && subtotal >= total)) && keywordSubtotal != null) {
            data.setSubTotal(AmountUtil.formatAmount(keywordSubtotal));
            if (data.getTaxableValue() == null) {
                data.setTaxableValue(AmountUtil.formatAmount(keywordSubtotal));
            }
        }
        if (tax == null && keywordTax != null) {
            data.setTaxAmount(AmountUtil.formatAmount(keywordTax));
        }
        Double wordsTotal = AmountUtil.extractAmountFromWords(zones.bottomZone, AmountUtil.TOTAL_KEYWORDS);
        subtotal = AmountUtil.parseAmount(data.getSubTotal());
        tax = AmountUtil.parseAmount(data.getTaxAmount());
        total = AmountUtil.parseAmount(data.getTotalAmount());
        if (keywordTotal != null && (total == null
                || (tax != null && total <= tax)
                || (subtotal != null && total <= subtotal)
                || total < keywordTotal * 0.85)) {
            data.setTotalAmount(AmountUtil.formatAmount(keywordTotal));
            total = keywordTotal;
        }
        if (wordsTotal != null && (total == null
                || (tax != null && total <= tax)
                || (subtotal != null && total <= subtotal)
                || total < wordsTotal * 0.85
                || !AmountUtil.approximatelyEquals(total, wordsTotal))) {
            data.setTotalAmount(AmountUtil.formatAmount(wordsTotal));
            total = wordsTotal;
        }
        if (subtotal != null && tax != null && (total == null || total <= tax || total <= subtotal)) {
            data.setTotalAmount(AmountUtil.formatAmount(subtotal + tax));
        }
    }

    private void repairDocumentFieldsFromLabeledText(InvoiceOcrDocument document, InvoiceData data) {
        if (document == null || data == null) {
            return;
        }
        String labeledInvoiceNumber = extractDirectLabeledInvoiceNumber(firstNonBlank(document.getFirstPageText(), document.getCombinedText()));
        if (shouldPreferLabeledInvoiceNumber(labeledInvoiceNumber, data.getInvoiceNumber())) {
            data.setInvoiceNumber(labeledInvoiceNumber);
        }
        repairSummaryAmountsFromText(document.getCombinedText(), data);
        repairBankFieldsFromText(document.getCombinedText(), data);
        String labeledAccountNumber = extractLabeledAccountNumber(document.getCombinedText());
        if (shouldReplaceAccountNumber(data.getAccountNumber(), labeledAccountNumber)) {
            data.setAccountNumber(labeledAccountNumber);
        }
        String labeledIfsc = extractLabeledIfsc(document.getCombinedText());
        if (data.getIfscCode() == null && labeledIfsc != null) {
            data.setIfscCode(labeledIfsc);
        }
        repairTransportFieldsFromText(document.getCombinedText(), data);
        repairBuyerFieldsFromText(firstNonBlank(document.getFirstPageText(), document.getCombinedText()), data);
    }

    private String extractDirectLabeledInvoiceNumber(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile(
                "(?im)\\b(?:invoice\\s*(?:no|number|#)|inv\\s*no|bill\\s*(?:no|#))\\.?\\s*(?:[:#>|-]+\\s*)*([A-Z0-9/-]{1,20})")
                .matcher(text);
        String best = null;
        int bestScore = Integer.MIN_VALUE;
        while (matcher.find()) {
            String candidate = cleanInvoiceNumberValue(matcher.group(1));
            if (candidate == null) {
                continue;
            }
            int score = scoreInvoiceNumber(candidate, "keyword");
            if (candidate.matches("^\\d{1,12}$")) {
                score += 100;
            }
            if (score > bestScore) {
                bestScore = score;
                best = candidate;
            }
        }
        return best;
    }

    private boolean shouldPreferLabeledInvoiceNumber(String candidate, String current) {
        if (!isValidInvoiceNumber(candidate)) {
            return false;
        }
        if (!isValidInvoiceNumber(current)) {
            return true;
        }
        if (candidate.matches("^\\d{1,12}$") && !current.matches("^\\d{1,12}$")) {
            return true;
        }
        return scoreInvoiceNumber(candidate, "keyword") > scoreInvoiceNumber(current, null) + 4;
    }

    private void repairSummaryAmountsFromText(String text, InvoiceData data) {
        if (text == null || text.isBlank()) {
            return;
        }
        List<String> lines = normalizedLines(text);
        List<LineIndexingService.IndexedLine> indexedLines = toIndexedLines(lines);

        Double totalFromWords = AmountUtil.extractAmountFromWords(indexedLines, AmountUtil.TOTAL_KEYWORDS);
        Double totalCandidate = firstNonNull(
                strongerAmount(totalFromWords, extractLargestAmountForKeywords(lines, AmountUtil.TOTAL_KEYWORDS)),
                strongerAmount(extractLargestAmountForKeywords(lines, List.of("grand total", "amount payable", "invoice amt", "inv value", "invoice value")), null)
        );
        Double taxCandidate = firstNonNull(
                extractSmallestAmountForKeywords(lines, AmountUtil.TAX_KEYWORDS),
                extractTaxAmountFromBreakdown(lines),
                extractLargestAmountForKeywords(lines, List.of("tax amount", "gst output", "integrated tax"))
        );
        Double subtotalCandidate = extractLargestAmountForKeywords(lines, AmountUtil.SUBTOTAL_KEYWORDS);

        Double currentTotal = AmountUtil.parseAmount(data.getTotalAmount());
        Double currentTax = AmountUtil.parseAmount(data.getTaxAmount());
        Double currentSubtotal = AmountUtil.parseAmount(data.getSubTotal());

        if (shouldReplaceTotalAmount(currentTotal, totalCandidate, currentTax, currentSubtotal)) {
            data.setTotalAmount(AmountUtil.formatAmount(totalCandidate));
            currentTotal = totalCandidate;
        }
        if (shouldReplaceTaxAmount(currentTax, taxCandidate, currentTotal)) {
            data.setTaxAmount(AmountUtil.formatAmount(taxCandidate));
            currentTax = taxCandidate;
        }
        if (shouldReplaceSubtotalAmount(currentSubtotal, subtotalCandidate, currentTotal)) {
            data.setSubTotal(AmountUtil.formatAmount(subtotalCandidate));
            if (data.getTaxableValue() == null) {
                data.setTaxableValue(AmountUtil.formatAmount(subtotalCandidate));
            }
            currentSubtotal = subtotalCandidate;
        }
        if (currentSubtotal != null && currentTotal != null && currentTax != null
                && currentSubtotal >= currentTotal && currentTotal > currentTax) {
            Double derivedSubtotal = currentTotal - currentTax;
            data.setSubTotal(AmountUtil.formatAmount(derivedSubtotal));
            data.setTaxableValue(AmountUtil.formatAmount(derivedSubtotal));
            currentSubtotal = derivedSubtotal;
        }
        if (currentSubtotal == null && currentTotal != null && currentTax != null && currentTotal > currentTax) {
            Double derivedSubtotal = currentTotal - currentTax;
            data.setSubTotal(AmountUtil.formatAmount(derivedSubtotal));
            if (data.getTaxableValue() == null || AmountUtil.parseAmount(data.getTaxableValue()) == null) {
                data.setTaxableValue(AmountUtil.formatAmount(derivedSubtotal));
            }
        }
        if (data.getTaxAmount() == null) {
            Double refreshedSubtotal = AmountUtil.parseAmount(data.getSubTotal());
            Double refreshedTotal = AmountUtil.parseAmount(data.getTotalAmount());
            if (refreshedSubtotal != null && refreshedTotal != null && refreshedTotal > refreshedSubtotal) {
                data.setTaxAmount(AmountUtil.formatAmount(refreshedTotal - refreshedSubtotal));
            }
        }
    }

    private void repairBankFieldsFromText(String text, InvoiceData data) {
        if (text == null || text.isBlank()) {
            return;
        }
        String namedBank = extractNamedBank(text);
        if (namedBank != null && !isRecognizedBankName(data.getBankName())) {
            data.setBankName(namedBank);
        }
        for (String line : normalizedLines(text)) {
            if (!isRecognizedBankName(data.getBankName())) {
                String bankName = cleanBankNameValue(line, line);
                if (bankName != null) {
                    data.setBankName(bankName);
                }
            }
            if (isWeakAccountNumber(data.getAccountNumber())) {
                String account = cleanAccountNumber(line, line);
                if (shouldReplaceAccountNumber(data.getAccountNumber(), account)) {
                    data.setAccountNumber(account);
                }
            }
            if (data.getIfscCode() == null) {
                String ifsc = cleanIfscValue(line, line);
                if (ifsc != null) {
                    data.setIfscCode(ifsc);
                }
            }
            if (!isWeakBankName(data.getBankName()) && data.getAccountNumber() != null && data.getIfscCode() != null) {
                return;
            }
        }
        if (namedBank != null && !isRecognizedBankName(data.getBankName())) {
            data.setBankName(namedBank);
        }
        String accountCandidate = extractLabeledAccountNumber(text);
        if (shouldReplaceAccountNumber(data.getAccountNumber(), accountCandidate)) {
            data.setAccountNumber(accountCandidate);
        }
        if (data.getIfscCode() == null) {
            String ifscCandidate = extractLabeledIfsc(text);
            if (ifscCandidate != null) {
                data.setIfscCode(ifscCandidate);
            }
        }
    }

    private void repairTransportFieldsFromText(String text, InvoiceData data) {
        if (text == null || text.isBlank()) {
            return;
        }
        String transporter = cleanTransporterNameValue(extractLabeledValue(text,
                "(?im)\\btransporter\\s*(?:name|nm\\.?)\\b\\s*[:=-]?\\s*(.+)$"), null, null);
        if (data.getTransporterName() == null && transporter != null) {
            data.setTransporterName(transporter);
        }

        String transport = cleanTransportDetailsValue(firstNonBlank(
                extractLabeledValue(text, "(?im)\\btransport(?:er)?\\b\\s*[:=-]?\\s*(.+)$"),
                transporter
        ));
        if (data.getTransportDetails() == null && transport != null) {
            data.setTransportDetails(transport);
        }

        String mode = cleanDispatchValue(extractLabeledValue(text,
                "(?im)\\b(?:dispatched\\s+through|dispatch\\s+through|transportation\\s+mode|mode)\\b\\s*[:=-]?\\s*(.+)$"));
        if (data.getDispatchThrough() == null && mode != null) {
            data.setDispatchThrough(mode);
        }

        String destination = cleanLocationValue(extractLabeledValue(text,
                "(?im)\\b(?:destination|station)\\b\\s*[:=-]?\\s*(.+)$"));
        if (data.getDestination() == null && destination != null) {
            data.setDestination(destination);
        }
    }

    private void repairContactFieldsFromText(String text, InvoiceData data) {
        if (text == null || text.isBlank() || data == null) {
            return;
        }
        String vendorHeaderText = vendorHeaderOnlyText(text);
        if (!isValidVendorName(data.getVendorName())) {
            String vendorName = deriveVendorNameFromHeader(vendorHeaderText);
            if (isValidVendorName(vendorName)) {
                data.setVendorName(vendorName);
            }
        }
        if (!isValidGstin(data.getVendorGstin())) {
            String vendorGstin = extractRepairableGstin(vendorHeaderText);
            if (isValidGstin(vendorGstin)) {
                data.setVendorGstin(vendorGstin);
            }
        }
        if (data.getVendorEmail() == null) {
            java.util.regex.Matcher emailMatcher = java.util.regex.Pattern
                    .compile("(?i)[\\w.%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}")
                    .matcher(vendorHeaderText);
            if (emailMatcher.find()) {
                data.setVendorEmail(emailMatcher.group().toLowerCase());
            }
        }
        if (data.getVendorWebsite() == null) {
            java.util.regex.Matcher websiteMatcher = java.util.regex.Pattern
                    .compile("(?i)(?:https?://|www\\.)?[A-Z0-9.-]+\\.(?:com|in|org|net|co\\.in|biz|info)")
                    .matcher(vendorHeaderText);
            while (websiteMatcher.find()) {
                String candidate = websiteMatcher.group();
                if (candidate.contains("@")) {
                    continue;
                }
                String cleaned = cleanWebsiteValue(candidate);
                if (cleaned != null) {
                    data.setVendorWebsite(cleaned);
                    break;
                }
            }
        }
        if (data.getVendorPhone() == null) {
            String phone = cleanPhoneValue(vendorHeaderText);
            if (phone != null) {
                data.setVendorPhone(phone);
            }
        }
        if (data.getVendorCIN() == null) {
            java.util.regex.Matcher cinMatcher = java.util.regex.Pattern
                    .compile("(?i)\\b[LU]\\d{5}[A-Z]{2}\\d{4}[A-Z]{3}\\d{6}\\b")
                    .matcher(vendorHeaderText);
            if (cinMatcher.find()) {
                data.setVendorCIN(cinMatcher.group().toUpperCase());
            }
        }
    }

    private String deriveVendorNameFromHeader(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String best = null;
        int bestScore = Integer.MIN_VALUE;
        for (String line : normalizedLines(text)) {
            for (String part : line.split("\\|")) {
                String candidate = cleanPartyName(part, true);
                if (!isValidVendorName(candidate)) {
                    continue;
                }
                int score = scoreVendorName(candidate);
                String lower = part.toLowerCase();
                if (lower.contains("gstin") || lower.contains("invoice") || lower.contains("dated")) {
                    score -= 20;
                }
                if (score > bestScore) {
                    best = candidate;
                    bestScore = score;
                }
            }
        }
        return best;
    }

    private String extractRepairableGstin(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        for (String line : normalizedLines(text)) {
            String lower = line.toLowerCase();
            if (!(lower.contains("gstin") || lower.contains("gst no") || lower.contains("gst in")
                    || lower.contains("gstin/uin") || lower.contains("uin") || lower.contains("tin no"))) {
                continue;
            }
            String candidateRegion = line.replaceFirst("(?i)^.*?\\b(?:gstin/uin|gstin|gst\\s*no|gst\\s*in|uin|tin\\s*no|party\\s*gst)\\b\\s*[:#=-]*\\s*", "");
            String compact = candidateRegion.replaceAll("[^A-Za-z0-9]", "").toUpperCase();
            if (compact.length() < 15) {
                continue;
            }
            String direct = cleanGstinValue(compact);
            if (direct != null) {
                return direct;
            }
            for (int start = 0; start <= compact.length() - 15; start++) {
                String repaired = cleanGstinValue(compact.substring(start, start + 15));
                if (repaired != null) {
                    return repaired;
                }
            }
            if (compact.length() > 15) {
                for (int drop = 0; drop < compact.length(); drop++) {
                    String reduced = compact.substring(0, drop) + compact.substring(drop + 1);
                    if (reduced.length() != 15) {
                        continue;
                    }
                    String repaired = cleanGstinValue(reduced);
                    if (repaired != null) {
                        return repaired;
                    }
                }
            }
        }
        return null;
    }

    private void repairAddressFieldsFromRawDocument(InvoiceOcrDocument document, InvoiceData data) {
        if (document == null || data == null) {
            return;
        }
        InvoiceUniversalFieldExtractor.Result rawFields = new InvoiceUniversalFieldExtractor().extract(document, data);
        String buyerAddress = cleanAddressValue(firstNonBlank(data.getBuyerAddress(), rawFields.getBuyerAddress()));
        if (buyerAddress != null) {
            data.setBuyerAddress(buyerAddress);
        }
        String vendorAddress = cleanAddressValue(firstNonBlank(data.getVendorAddress(), rawFields.getVendorAddress()));
        if (vendorAddress != null) {
            data.setVendorAddress(vendorAddress);
        }
    }

    private String vendorHeaderOnlyText(String firstPageText) {
        if (firstPageText == null || firstPageText.isBlank()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (String rawLine : firstPageText.split("\\R")) {
            String lower = rawLine.toLowerCase();
            if (lower.contains("bill to")
                    || lower.contains("billed to")
                    || lower.contains("buyer")
                    || lower.contains("ship to")
                    || lower.contains("shipped to")
                    || lower.contains("consignee")
                    || lower.contains("description")) {
                break;
            }
            if (builder.length() > 0) {
                builder.append('\n');
            }
            builder.append(rawLine);
        }
        return builder.toString();
    }

    private boolean isWeakBankName(String value) {
        if (value == null || value.isBlank()) {
            return true;
        }
        String lower = value.toLowerCase();
        return lower.equals("our bank")
                || lower.equals("s bank")
                || lower.equals("bank details")
                || lower.contains("bank details")
                || lower.contains("bank tame")
                || lower.contains("company's bank")
                || lower.contains("companys bank")
                || lower.contains("details of receiver")
                || lower.contains("details of consignee");
    }

    private boolean isRecognizedBankName(String value) {
        return extractNamedBank(value) != null;
    }

    private boolean isWeakAccountNumber(String value) {
        if (value == null) {
            return true;
        }
        String digits = value.replaceAll("\\D", "");
        return digits.length() < 10 || digits.length() > 18;
    }

    private boolean shouldReplaceAccountNumber(String current, String candidate) {
        if (candidate == null) {
            return false;
        }
        if (isWeakAccountNumber(current)) {
            return true;
        }
        String currentDigits = current.replaceAll("\\D", "");
        String candidateDigits = candidate.replaceAll("\\D", "");
        return candidateDigits.length() > currentDigits.length();
    }

    private String extractNamedBank(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String normalizedText = normalizeKnownBankOcr(text);
        for (String bankName : KNOWN_BANK_NAMES) {
            java.util.regex.Matcher matcher = java.util.regex.Pattern
                    .compile("(?i)\\b" + java.util.regex.Pattern.quote(bankName) + "\\b")
                    .matcher(normalizedText);
            if (matcher.find()) {
                return RegexUtil.normalizeLine(matcher.group());
            }
        }
        return null;
    }

    private String extractLabeledAccountNumber(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile(
                "(?im)(?:a/c(?:\\s*no)?|account(?:\\s*(?:no|number))?)\\s*[:=-]?\\s*([0-9\\- ]{8,24})")
                .matcher(text);
        String best = null;
        while (matcher.find()) {
            String digits = matcher.group(1).replaceAll("\\D", "");
            if (digits.matches("\\d{8,18}") && (best == null || digits.length() > best.length())) {
                best = digits;
            }
        }
        if (best != null) {
            return best;
        }
        for (String line : normalizedLines(text)) {
            String lower = line.toLowerCase();
            if (!(lower.contains("account") || lower.contains("a/c"))) {
                continue;
            }
            java.util.regex.Matcher digitsMatcher = java.util.regex.Pattern.compile("\\b\\d{8,18}\\b").matcher(line);
            while (digitsMatcher.find()) {
                String digits = digitsMatcher.group();
                if (best == null || digits.length() > best.length()) {
                    best = digits;
                }
            }
        }
        return best;
    }

    private String extractLabeledIfsc(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile(
                "(?im)(?:ifsc(?:\\s*code)?|ifs\\s*code|branch\\s*&\\s*ifs\\s*code)\\s*[:=-]?\\s*([A-Z0-9]{8,20})")
                .matcher(text);
        while (matcher.find()) {
            String normalized = normalizeIfscCandidate(matcher.group(1));
            if (normalized != null) {
                return normalized;
            }
        }
        return null;
    }

    private void repairBuyerFieldsFromText(String text, InvoiceData data) {
        if (text == null || text.isBlank()) {
            return;
        }
        List<String> block = extractBuyerBlock(text);
        if (block.isEmpty()) {
            return;
        }
        String joined = collapseRepeatedSegments(String.join(", ", block));
        String buyerAddress = cleanAddressValue(joined);
        if (shouldReplaceBuyerAddress(data.getBuyerAddress(), buyerAddress)) {
            data.setBuyerAddress(buyerAddress);
        }
        if (!isValidGstin(data.getBuyerGstin())) {
            String buyerGstin = extractRepairableGstin(joined);
            if (isValidGstin(buyerGstin)) {
                data.setBuyerGstin(buyerGstin);
            }
        }
        if (!isValidBuyerName(data.getBuyerName())) {
            for (String line : block) {
                String candidate = cleanPartyName(line, false);
                if (isValidBuyerName(candidate)) {
                    data.setBuyerName(candidate);
                    return;
                }
            }
            String derived = derivePartyNameFromAddress(firstNonBlank(buyerAddress, joined), false);
            if (isValidBuyerName(derived)) {
                data.setBuyerName(derived);
            }
        }
    }

    private boolean shouldReplaceBuyerAddress(String current, String candidate) {
        if (candidate == null) {
            return false;
        }
        if (current == null || cleanAddressValue(current) == null) {
            return true;
        }
        return candidate.length() > current.length() + 8;
    }

    private List<String> extractBuyerBlock(String text) {
        List<String> lines = normalizedLines(text);
        int start = -1;
        for (int i = 0; i < lines.size(); i++) {
            String lower = lines.get(i).toLowerCase();
            if (lower.contains("bill to")
                    || lower.contains("billed to")
                    || lower.contains("buyer (bill to)")
                    || lower.startsWith("buyer ")
                    || lower.contains("details of buyer")
                    || lower.contains("details of purchaser")
                    || lower.contains("details of receiver (billed to)")
                    || lower.contains("details of recipient")) {
                start = i;
                break;
            }
        }
        if (start < 0) {
            return List.of();
        }
        List<String> block = new ArrayList<>();
        String inline = extractBuyerInlineFromRawLine(lines.get(start));
        if (inline != null) {
            block.add(inline);
        }
        for (int i = start + 1; i < lines.size() && block.size() < 6; i++) {
            String rawLine = lines.get(i);
            String rawLower = rawLine.toLowerCase();
            if ((rawLower.contains("gstin") || rawLower.contains("uin")) && !block.isEmpty()) {
                block.add(rawLine);
                break;
            }
            String candidate = extractPrimaryBuyerColumn(rawLine);
            String lower = candidate.toLowerCase();
            if (candidate.isBlank()) {
                continue;
            }
            if (lower.contains("gstin")
                    || lower.contains("description")
                    || lower.contains("hsn")
                    || lower.contains("qty")
                    || lower.contains("taxable")
                    || lower.contains("invoice amt")
                    || lower.contains("grand total")
                    || lower.contains("buyers order")
                    || lower.contains("buyer's order")
                    || lower.contains("terms of delivery")
                    || lower.contains("dispatch doc")) {
                break;
            }
            if (lower.contains("bank")
                    || lower.contains("account")
                    || lower.contains("ifsc")
                    || lower.contains("transporter")
                    || lower.contains("bill of lading")
                    || lower.contains("lr-rr")
                    || lower.contains("delivery note date")) {
                continue;
            }
            boolean useful = lower.contains("department")
                    || lower.contains("atomic energy")
                    || lower.contains("nuclear")
                    || lower.contains("stores")
                    || lower.contains("officer")
                    || lower.contains("manager")
                    || lower.contains("complex")
                    || lower.contains("ecil")
                    || lower.contains("hrpsu")
                    || lower.contains("apo")
                    || lower.contains("hyderabad")
                    || lower.matches(".*\\d{6}.*");
            if (!useful && !block.isEmpty()) {
                break;
            }
            if (useful) {
                block.add(candidate);
            }
        }
        return block;
    }

    private String extractBuyerInlineFromRawLine(String line) {
        if (line == null) {
            return null;
        }
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile(
                "(?i)(?:bill(?:ed)? to|buyer(?:\\s*\\(bill to\\))?|details of buyer|details of purchaser|details of receiver \\(billed to\\)|details of recipient(?: \\( billed to\\))?)\\s*[:=-]*\\s*(.+)")
                .matcher(line);
        if (!matcher.find()) {
            return null;
        }
        String best = null;
        int bestScore = Integer.MIN_VALUE;
        for (String part : matcher.group(1).split("\\|")) {
            String candidate = cleanBuyerColumnCandidate(part);
            if (candidate.isBlank()) {
                continue;
            }
            int score = scoreBuyerColumnCandidate(candidate);
            if (score > bestScore) {
                best = candidate;
                bestScore = score;
            }
        }
        return bestScore > 0 ? best : null;
    }

    private String extractPrimaryBuyerColumn(String line) {
        if (line == null) {
            return "";
        }
        String inline = extractBuyerInlineFromRawLine(line);
        if (inline != null) {
            return inline;
        }
        if (line.contains("|")) {
            String best = "";
            int bestScore = Integer.MIN_VALUE;
            for (String part : line.split("\\|")) {
                String candidate = cleanBuyerColumnCandidate(part);
                if (candidate.isBlank()) {
                    continue;
                }
                int score = scoreBuyerColumnCandidate(candidate);
                if (score > bestScore) {
                    best = candidate;
                    bestScore = score;
                }
            }
            return bestScore > 0 ? best : "";
        }
        String lower = line.toLowerCase();
        if (lower.contains("bank") || lower.contains("account") || lower.contains("ifsc")) {
            return "";
        }
        return cleanBuyerColumnCandidate(line);
    }

    private String cleanBuyerColumnCandidate(String value) {
        if (value == null) {
            return "";
        }
        return RegexUtil.normalizeLine(value.replace('\'', ' '))
                .replaceFirst("^[^A-Za-z0-9]+", "")
                .replaceFirst("(?i)^(?:name|address|party|customer code)\\s*[:=-]*\\s*", "")
                .replaceFirst("(?i)\\bGEMC[-A-Z0-9/]{6,}.*$", "")
                .replaceFirst("(?i)\\b(?:buyers?|uyers?)\\s*order\\s*no\\b.*$", "")
                .replaceFirst("(?i)\\bterms\\s+of\\s+delivery\\b.*$", "")
                .replaceFirst("(?i)\\bbill\\s+of\\s+lading\\b.*$", "")
                .replaceFirst("(?i)\\blr[-/ ]?rr\\s*no\\b.*$", "")
                .replaceFirst("(?i)\\bdispatch document no\\b.*$", "")
                .replaceFirst("(?i)\\bdelivery note date\\b.*$", "")
                .replaceAll("^:+\\s*", "")
                .trim();
    }

    private int scoreBuyerColumnCandidate(String candidate) {
        if (candidate == null || candidate.isBlank()) {
            return Integer.MIN_VALUE;
        }
        String lower = candidate.toLowerCase();
        if (lower.contains("bank")
                || lower.contains("account")
                || lower.contains("ifsc")
                || lower.contains("invoice")
                || lower.contains("gstin")
                || lower.contains("buyers order")
                || lower.contains("buyer's order")
                || lower.contains("terms of delivery")
                || lower.contains("bill of lading")
                || lower.contains("lr-rr")
                || lower.contains("reverse charge")
                || lower.contains("station")
                || lower.contains("party mobile")
                || lower.contains("party e-mail")
                || lower.contains("party email")
                || lower.contains("party pincode")
                || lower.contains("state name")) {
            return Integer.MIN_VALUE;
        }
        int score = 0;
        if (lower.contains("department")) {
            score += 50;
        }
        if (lower.contains("atomic")) {
            score += 30;
        }
        if (lower.contains("energy")) {
            score += 20;
        }
        if (lower.contains("stores") || lower.contains("officer") || lower.contains("directorate")) {
            score += 20;
        }
        if (lower.contains("nuclear") || lower.contains("complex") || lower.contains("fuel")) {
            score += 20;
        }
        if (lower.contains("hrpsu") || lower.contains("apo")) {
            score += 12;
        }
        if (lower.contains("ecil") || lower.contains("hyderabad")) {
            score += 10;
        }
        if (candidate.matches(".*\\d{6}.*")) {
            score += 8;
        }
        score += Math.min(candidate.length(), 40);
        return score;
    }

    private List<String> normalizedLines(String text) {
        List<String> lines = new ArrayList<>();
        for (String raw : text.split("\\n")) {
            String normalized = RegexUtil.normalizeLine(raw);
            if (!normalized.isBlank()) {
                lines.add(normalized);
            }
        }
        return lines;
    }

    private List<LineIndexingService.IndexedLine> toIndexedLines(List<String> lines) {
        List<LineIndexingService.IndexedLine> indexedLines = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            indexedLines.add(new LineIndexingService.IndexedLine(i + 1, lines.get(i)));
        }
        return indexedLines;
    }

    private Double extractLargestAmountForKeywords(List<String> lines, List<String> keywords) {
        Double best = null;
        for (String line : lines) {
            String lower = line.toLowerCase();
            if (!RegexUtil.containsAnyKeyword(lower, keywords) || lower.contains("bank")) {
                continue;
            }
            for (Double value : extractLineAmounts(line, false)) {
                if (best == null || value > best) {
                    best = value;
                }
            }
        }
        return best;
    }

    private Double extractSmallestAmountForKeywords(List<String> lines, List<String> keywords) {
        Double best = null;
        for (String line : lines) {
            String lower = line.toLowerCase();
            if (!RegexUtil.containsAnyKeyword(lower, keywords) || lower.contains("bank")) {
                continue;
            }
            for (Double value : extractLineAmounts(line, true)) {
                if (best == null || value < best) {
                    best = value;
                }
            }
        }
        return best;
    }

    private Double extractTaxAmountFromBreakdown(List<String> lines) {
        for (int i = 0; i < lines.size(); i++) {
            String lower = lines.get(i).toLowerCase();
            boolean taxHeader = lower.contains("tax rate")
                    || (lower.contains("taxable") && lower.contains("tax"))
                    || lower.contains("igstamt")
                    || lower.contains("cgstamt")
                    || lower.contains("sgstamt");
            if (!taxHeader) {
                continue;
            }
            for (int j = i + 1; j < lines.size() && j <= i + 2; j++) {
                List<Double> candidates = new ArrayList<>(extractLineAmounts(lines.get(j), false));
                if (candidates.isEmpty()) {
                    continue;
                }
                candidates.sort(Double::compareTo);
                return candidates.get(0);
            }
        }
        return null;
    }

    private List<Double> extractLineAmounts(String line, boolean preferSmallest) {
        List<String> rawTokens = AmountUtil.extractRawNumericTokens(line);
        List<Double> values = new ArrayList<>();
        boolean hasCurrencyToken = rawTokens.stream().anyMatch(AmountUtil::looksLikeCurrencyToken);
        for (String token : rawTokens) {
            Double value = AmountUtil.parseAmount(token);
            if (value == null || value < AmountUtil.MIN_SIGNIFICANT_AMOUNT || isPercentToken(line, token)) {
                continue;
            }
            if (hasCurrencyToken && !AmountUtil.looksLikeCurrencyToken(token)) {
                continue;
            }
            values.add(value);
        }
        if (values.size() >= 3 && preferSmallest) {
            values.sort(Double::compareTo);
            return List.of(values.get(0));
        }
        return values;
    }

    private boolean isPercentToken(String line, String token) {
        int index = line.indexOf(token);
        if (index < 0) {
            return false;
        }
        int cursor = index + token.length();
        while (cursor < line.length() && Character.isWhitespace(line.charAt(cursor))) {
            cursor++;
        }
        return cursor < line.length() && line.charAt(cursor) == '%';
    }

    private Double strongerAmount(Double preferred, Double fallback) {
        if (preferred == null) {
            return fallback;
        }
        if (fallback == null) {
            return preferred;
        }
        return preferred >= fallback ? preferred : fallback;
    }

    @SafeVarargs
    private final <T> T firstNonNull(T... values) {
        if (values == null) {
            return null;
        }
        for (T value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private boolean shouldReplaceTotalAmount(Double current, Double candidate, Double tax, Double subtotal) {
        if (candidate == null) {
            return false;
        }
        if (current == null || current < AmountUtil.MIN_SIGNIFICANT_AMOUNT) {
            return true;
        }
        if ((tax != null && current <= tax) || (subtotal != null && current <= subtotal)) {
            return true;
        }
        return candidate > current * 1.15;
    }

    private boolean shouldReplaceTaxAmount(Double current, Double candidate, Double total) {
        if (candidate == null) {
            return false;
        }
        if (total != null && candidate >= total) {
            return false;
        }
        return current == null || current < AmountUtil.MIN_SIGNIFICANT_AMOUNT || (total != null && current >= total);
    }

    private boolean shouldReplaceSubtotalAmount(Double current, Double candidate, Double total) {
        if (candidate == null) {
            return false;
        }
        if (total != null && candidate >= total) {
            return false;
        }
        return current == null || (total != null && current >= total);
    }

    private String extractLabeledValue(String text, String regex) {
        if (text == null || text.isBlank()) {
            return null;
        }
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile(regex).matcher(text);
        while (matcher.find()) {
            String candidate = RegexUtil.normalizeLine(matcher.group(1));
            if (!candidate.isBlank()) {
                return candidate;
            }
        }
        return null;
    }

    private void enrichPartyFieldsFromAddresses(InvoiceData data) {
        if (!isValidBuyerName(data.getBuyerName())) {
            String derivedBuyer = derivePartyNameFromAddress(data.getBuyerAddress(), false);
            if (isValidBuyerName(derivedBuyer)) {
                data.setBuyerName(derivedBuyer);
            }
        }
        if (!isValidVendorName(data.getVendorName())) {
            String derivedVendor = derivePartyNameFromAddress(data.getVendorAddress(), true);
            if (isValidVendorName(derivedVendor)) {
                data.setVendorName(derivedVendor);
            }
        }
        if (sameNormalizedValue(data.getBuyerAddress(), data.getVendorAddress())) {
            data.setBuyerAddress(null);
        }
    }

    private void applyHighPrecisionFilters(InvoiceData data, LineIndexingService.Zones zones) {
        data.setInvoiceNumber(cleanInvoiceNumberValue(data.getInvoiceNumber()));
        data.setInvoiceDate(cleanInvoiceDateValue(data.getInvoiceDate()));
        data.setVendorName(cleanPartyName(data.getVendorName(), true));
        data.setVendorGstin(cleanGstinValue(data.getVendorGstin()));
        data.setBuyerName(cleanPartyName(data.getBuyerName(), false));
        data.setBuyerGstin(cleanGstinValue(data.getBuyerGstin()));
        data.setPoNumber(cleanBusinessIdentifier(data.getPoNumber(), 30, false));
        data.setPoDate(cleanOptionalDate(data.getPoDate()));
        data.setOrderReference(cleanReferenceValue(data.getOrderReference(), false));
        data.setDeliveryNote(cleanReferenceValue(data.getDeliveryNote(), true));
        data.setDispatchThrough(cleanDispatchValue(data.getDispatchThrough()));
        data.setTransporterName(cleanTransporterNameValue(data.getTransporterName(), data.getDispatchThrough(), data.getTransportDetails()));
        data.setPaymentTerms(cleanPaymentTermsValue(data.getPaymentTerms()));
        data.setTransportDetails(cleanTransportDetailsValue(data.getTransportDetails()));
        data.setVehicleNumber(cleanVehicleNumberValue(data.getVehicleNumber()));
        data.setBankDetails(cleanBankDetailsValue(data.getBankDetails()));
        data.setBankName(cleanBankNameValue(data.getBankName(), data.getBankDetails()));
        data.setAccountNumber(cleanAccountNumber(data.getAccountNumber(), data.getBankDetails()));
        data.setIfscCode(cleanIfscValue(data.getIfscCode(), data.getBankDetails()));
        data.setBranch(cleanLocationValue(data.getBranch()));
        data.setStateCode(cleanStateCodeValue(data.getStateCode(), data.getBuyerGstin()));
        data.setState(cleanStateValue(data.getState(), data.getStateCode(), data.getPlaceOfSupply()));
        data.setDestination(cleanLocationValue(data.getDestination()));
        data.setPlaceOfSupply(cleanPlaceOfSupplyValue(data.getPlaceOfSupply(), data.getState(), data.getStateCode()));
        data.setIrn(cleanBusinessIdentifier(data.getIrn(), 100, false));
        data.setAckNumber(cleanBusinessIdentifier(data.getAckNumber(), 100, false));
        data.setEwayBill(cleanBusinessIdentifier(data.getEwayBill(), 100, false));
        data.setVendorPhone(cleanPhoneValue(data.getVendorPhone()));
        if (data.getAccountNumber() != null && data.getVendorPhone() != null
                && data.getVendorPhone().replaceAll("\\D", "").contains(data.getAccountNumber())) {
            data.setAccountNumber(null);
        }
        if (data.getBankName() == null && data.getBankDetails() == null && data.getIfscCode() == null) {
            data.setAccountNumber(null);
        }
        data.setVendorEmail(cleanEmailValue(firstNonBlank(data.getVendorEmail(), data.getVendorAddress(), data.getBankName())));
        data.setVendorWebsite(cleanWebsiteValue(bestWebsiteSource(data.getVendorWebsite(), data.getVendorAddress())));
        data.setVendorAddress(cleanAddressValue(data.getVendorAddress()));
        data.setBuyerAddress(cleanAddressValue(data.getBuyerAddress()));
        if (sameNormalizedValue(data.getBuyerAddress(), data.getVendorAddress())) {
            data.setBuyerAddress(null);
        }
        if (data.getBuyerName() == null) {
            data.setBuyerName(derivePartyNameFromAddress(data.getBuyerAddress(), false));
        }
        data.setVendorPAN(cleanPanValue(data.getVendorPAN(), data.getVendorGstin()));
        data.setVendorCIN(cleanBusinessIdentifier(data.getVendorCIN(), 30, false));
        data.setMsmeNumber(cleanSoftTextValue(data.getMsmeNumber(), 40));
        data.setPincode(cleanPincodeValue(firstNonBlank(data.getPincode(), data.getBuyerAddress(), data.getVendorAddress())));
        data.setSubTotal(cleanFinancialAmount(data.getSubTotal()));
        data.setTaxableValue(cleanFinancialAmount(data.getTaxableValue()));
        data.setCgst(cleanFinancialAmount(data.getCgst()));
        data.setSgst(cleanFinancialAmount(data.getSgst()));
        data.setIgst(cleanFinancialAmount(data.getIgst()));
        data.setTaxAmount(cleanFinancialAmount(data.getTaxAmount()));
        data.setRoundOff(cleanFinancialAmountAllowZero(data.getRoundOff()));
        data.setTotalAmount(cleanFinancialAmount(data.getTotalAmount()));
        data.setLineItems(sanitizeLineItems(data.getLineItems(), data.getSubTotal(), data.getTotalAmount()));
        reconstructFinancialsFromCandidates(data, zones);
        enforceAmountConsistency(data);
        reconcileExpandedAmounts(data);
        normalizeTaxableValueAgainstSubtotal(data);
        data.setKnownFields(null);
        data.setDynamicFields(null);
        data.setRawText(null);
    }

    private String cleanInvoiceNumberValue(String value) {
        if (value == null) {
            return null;
        }
        String raw = RegexUtil.cleanToken(value).replaceAll("\\s+", "").toUpperCase();
        String normalized = raw.matches("^\\d{1,12}$") ? raw : RegexUtil.repairInvoiceNumberCandidate(value)
                .replaceAll("\\s+", "")
                .toUpperCase();
        if (normalized.length() < 1 || normalized.length() > 20) {
            return null;
        }
        if (!RegexUtil.INVOICE_NUMBER_TOKEN_PATTERN.matcher(normalized).matches() || DateUtil.isValidInvoiceDate(normalized)) {
            return null;
        }
        if (!normalized.matches("[A-Z0-9/-]+")) {
            return null;
        }
        String lookalike = normalized
                .replace('0', 'O')
                .replace('1', 'I')
                .replace('7', 'T')
                .replace('5', 'S')
                .replace('8', 'B');
        if (lookalike.matches("^(INVOICE.*|VOUCHER.*|DATE.*|FOR|ORIGINAL.*|DUPLICATE.*|RECEIVER.*|SUPPLY.*|STORE.*|MATERIAL.*|ENTERPRISE.*|STATE.*|STATION.*|TOTAL.*|AMOUNT.*)$")) {
            return null;
        }
        return normalized;
    }

    private String cleanInvoiceDateValue(String value) {
        return DateUtil.isValidInvoiceDate(value) ? value : null;
    }

    private String cleanOptionalDate(String value) {
        return value == null ? null : cleanInvoiceDateValue(value);
    }

    private String cleanPartyName(String value, boolean vendor) {
        if (!isCleanBusinessText(value, 3, 100)) {
            return null;
        }
        String cleaned = RegexUtil.normalizeLine(value)
                .replaceFirst("(?i)^\\(?\\s*if other than consignee\\s*\\)?\\s*[,:-]*\\s*", "")
                .replaceFirst("(?i)^\\(?\\s*(?:ship(?:ped)? to|bill(?:ed)? to|consignee|buyer(?:\\s*\\(bill to\\))?)\\s*\\)?\\s*[,:-]*\\s*", "")
                .replaceFirst("(?i)^\\(?\\s*(?:details of buyer|details of purchaser|details of receiver|details of recipient|details of consignee)\\s*\\)?\\s*[,:-]*\\s*", "")
                .replaceFirst("(?i)^party\\s*,\\s*", "")
                .replaceFirst("(?i)^(?:name|address|party|party name|customer code)\\s*[:=-]*\\s*", "")
                .replaceFirst("^[^A-Za-z0-9]+", "")
                .replaceAll("^,+\\s*|\\s*,+$", "")
                .replaceAll("(?i)\\bdetails of (?:receiver|recipient|consignee)\\b", "")
                .trim();
        cleaned = collapseRepeatedSegments(cleaned);
        cleaned = cleaned.replaceFirst("(?i)^THE(?=(?:STORES|MATERIALS|MANAGER|DIRECTORATE|DEPARTMENT|NUCLEAR|ATOMIC)\\b)", "THE ");
        // Strip year range suffixes like "(2022-2023)" or "(2022-23)"
        cleaned = cleaned.replaceFirst("\\s*\\(\\d{4}[-/]\\d{2,4}\\)\\s*$", "").trim();
        if (cleaned.isBlank()) {
            return null;
        }
        return vendor ? (isValidVendorName(cleaned) ? cleaned : null) : (isValidBuyerName(cleaned) ? cleaned : null);
    }

    private String derivePartyNameFromAddress(String address, boolean vendor) {
        if (address == null || address.isBlank()) {
            return null;
        }
        String normalizedAddress = RegexUtil.normalizeLine(address)
                .replaceFirst("^:+\\s*", "")
                .replaceAll("(?i)(department of atomic energy stores)\\s+\\1", "$1")
                .replaceAll("(?i)(stores officer)\\s+\\*?\\s*\\1", "$1");
        String[] parts = normalizedAddress.split("\\s*,\\s*");
        for (String part : parts) {
            String cleaned = RegexUtil.normalizeLine(part)
                    .replaceFirst("^:+\\s*", "")
                    .replaceFirst("(?i)\\b(?:buyers?|uyers?)\\s*order\\s*no\\b.*$", "")
                    .replaceFirst("(?i)\\bterms\\s+of\\s+delivery\\b.*$", "")
                    .replaceFirst("(?i)\\bdispatch\\s+doc\\b.*$", "")
                    .replaceFirst("(?i)\\b(?:party\\s+e-?mail|party\\s+mobile|party\\s+pincode|state)\\b.*$", "")
                    .trim();
            if (cleaned.isBlank()) {
                continue;
            }
            String candidate = vendor ? cleanPartyName(cleaned, true) : cleanPartyName(cleaned, false);
            if (candidate != null) {
                return candidate;
            }
        }
        StringBuilder builder = new StringBuilder();
        for (String part : parts) {
            String cleaned = RegexUtil.normalizeLine(part);
            if (cleaned.isBlank()) {
                continue;
            }
            String lower = cleaned.toLowerCase();
            if (lower.contains("hyderabad")
                    || lower.contains("telangana")
                    || lower.contains("rajasthan")
                    || lower.contains("gujarat")
                    || lower.contains("delhi")
                    || lower.contains("road")
                    || lower.contains("floor")
                    || lower.contains("post")
                    || cleaned.matches(".*\\d{5,6}.*")) {
                break;
            }
            if (builder.length() > 0) {
                builder.append(", ");
            }
            builder.append(cleaned);
            if (builder.length() > 80 || lower.startsWith("m/s") || lower.contains("officer") || lower.contains("manager")) {
                break;
            }
        }
        String candidate = builder.toString().trim();
        return vendor ? cleanPartyName(candidate, true) : cleanPartyName(candidate, false);
    }

    private String cleanGstinValue(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.replaceAll("[^A-Za-z0-9]", "").toUpperCase();
        if (RegexUtil.isValidGstin(normalized)) {
            if (shouldPreferRepairedGstin(normalized)) {
                String repaired = RegexUtil.repairGstinCandidate(normalized);
                if (RegexUtil.isValidGstin(repaired)) {
                    return repaired;
                }
            }
            return normalized;
        }
        String repaired = RegexUtil.repairGstinCandidate(normalized);
        return RegexUtil.isValidGstin(repaired) ? repaired : null;
    }

    private boolean shouldPreferRepairedGstin(String value) {
        if (value == null || value.length() != 15) {
            return false;
        }
        char trailing = value.charAt(14);
        return "OQDILR".indexOf(trailing) >= 0 || value.charAt(13) != 'Z';
    }

    private String cleanBusinessIdentifier(String value, int maxLength, boolean requireAlphaAndDigit) {
        if (value == null) {
            return null;
        }
        String normalized = value.replace('|', ' ')
                .replace('_', ' ')
                .replaceAll("\\s+", "")
                .replaceFirst("^[^A-Za-z0-9]+", "")
                .replaceFirst("[^A-Za-z0-9]+$", "")
                .toUpperCase();
        if (normalized.length() < 3 || normalized.length() > maxLength) {
            return null;
        }
        if (!normalized.matches("[A-Z0-9./-]+")) {
            return null;
        }
        if (!normalized.matches(".*\\d.*") && normalized.length() <= 5) {
            return null;
        }
        if (requireAlphaAndDigit && (!normalized.matches(".*\\d.*") || !normalized.matches(".*[A-Z].*"))) {
            return null;
        }
        String lookalike = normalized
                .replace('0', 'O')
                .replace('1', 'I')
                .replace('5', 'S')
                .replace('7', 'T')
                .replace('8', 'B');
        if (lookalike.matches(".*(INVOICE|DATE|TOTAL|AMOUNT|GSTIN|BUYER|VENDOR|TRANSPORT|VEHICLE|BANK).*")) {
            return null;
        }
        return normalized;
    }

    private String cleanSoftTextValue(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        String cleaned = RegexUtil.normalizeLine(value.replace('|', ' ').replace('_', ' '));
        if (cleaned.isBlank() || cleaned.length() > maxLength || cleaned.length() < 2) {
            return null;
        }
        String lower = cleaned.toLowerCase();
        if (lower.matches("^(?:ship(?:ped)? to|bill(?:ed)? to|consignee|buyer|destination|dispatch|transporter?)$")) {
            return null;
        }
        return cleaned;
    }

    private String cleanPhoneValue(String value) {
        String cleaned = cleanSoftTextValue(value, 80);
        if (cleaned == null) {
            return null;
        }
        List<String> numbers = new ArrayList<>();
        java.util.LinkedHashSet<String> seen = new LinkedHashSet<>();
        java.util.regex.Matcher matcher = PRIORITY_PHONE_PATTERN.matcher(cleaned);
        while (matcher.find()) {
            String candidate = RegexUtil.normalizeLine(matcher.group());
            String digits = candidate.replaceAll("\\D", "");
            if (digits.length() < 10 || digits.length() > 12) {
                continue;
            }
            if (digits.startsWith("91") && digits.length() == 12) {
                candidate = "+91 " + digits.substring(2);
            } else if (digits.length() == 11 && digits.startsWith("0")) {
                candidate = digits;
            } else if (digits.length() == 10) {
                candidate = digits;
            }
            if (seen.add(candidate)) {
                numbers.add(candidate);
            }
        }
        return numbers.isEmpty() ? null : String.join(", ", numbers);
    }

    private String cleanReferenceValue(String value, boolean deliveryNote) {
        String cleaned = cleanSoftTextValue(value, 100);
        if (cleaned == null) {
            return null;
        }
        cleaned = truncateAtInvoiceNoise(cleaned)
                .replaceFirst("(?i)^(?:order reference|other references?|reference code|reference|delivery note)\\s*[:=-]?\\s*", "")
                .trim();
        String lower = cleaned.toLowerCase();
        if (cleaned.length() < 3
                || lower.contains("@")
                || lower.contains("mob")
                || lower.contains("phone")
                || lower.contains("mode/")
                || lower.contains("gst")
                || lower.contains("dispatch")
                || lower.contains("invoice")
                || !DateUtil.findCandidateDates(cleaned).isEmpty()) {
            return null;
        }
        if (!deliveryNote && !(cleaned.matches(".*\\d.*") || cleaned.contains("/") || cleaned.contains("-"))) {
            return null;
        }
        return cleaned;
    }

    private String cleanDispatchValue(String value) {
        String cleaned = cleanSoftTextValue(value, 100);
        if (cleaned == null) {
            return null;
        }
        cleaned = truncateAtInvoiceNoise(cleaned)
                .replaceFirst("(?i)^(?:dispatched through|dispatch through)\\s*[:=-]?\\s*", "")
                .replaceFirst("(?i)^(?:destination|destin(?:alion|ation))\\s*[:=-]?\\s*", "")
                .trim();
        String lower = cleaned.toLowerCase();
        if (cleaned.isBlank()
                || lower.equals("destination")
                || lower.contains("@")
                || lower.contains("phone")
                || lower.contains("invoice value")
                || lower.contains("terms of payment")
                || lower.matches(".*\\bwithin\\s+\\d+\\s+days\\b.*")) {
            return null;
        }
        // Reject vehicle number patterns (e.g. HR67B 6749, RJ14GA1234)
        String compactUpper = cleaned.replaceAll("\\s+", "").toUpperCase();
        if (compactUpper.matches("[A-Z]{2}\\d{1,2}[A-Z]{1,3}\\d{3,4}")) {
            return null;
        }
        boolean transportSignal = RegexUtil.containsAnyKeyword(lower, List.of(
                "transport", "transporter", "logistics", "roadline", "roadlines", "roadway", "courier", "cargo", "travels"
        ));
        boolean modeSignal = List.of("by road", "road", "canter", "truck", "lorry", "air", "sea", "rail", "express").contains(lower);
        if (!transportSignal && !modeSignal && cleaned.split("\\s+").length > 3) {
            return null;
        }
        return cleaned;
    }

    private String cleanTransporterNameValue(String value, String dispatchThrough, String transportDetails) {
        for (String candidateValue : new String[]{value, dispatchThrough, transportDetails}) {
            String candidate = cleanTransportDetailsValue(candidateValue);
            if (candidate == null) {
                continue;
            }
            String lower = candidate.toLowerCase();
            if (lower.contains("transport")
                    || lower.contains("logistics")
                    || lower.contains("roadline")
                    || lower.contains("roadway")
                    || lower.contains("courier")
                    || lower.contains("cargo")
                    || lower.contains("travels")) {
                return candidate;
            }
        }
        return null;
    }

    private String cleanBankNameValue(String value, String bankDetails) {
        String primary = value;
        if (isWeakBankName(value) || (value != null && value.length() < 8)) {
            primary = firstNonBlank(bankDetails, value);
        }
        String named = extractNamedBank(firstNonBlank(bankDetails, primary, value));
        if (named != null) {
            return named;
        }
        String cleaned = cleanSoftTextValue(normalizeKnownBankOcr(primary), 120);
        if (cleaned == null) {
            return null;
        }
        cleaned = cleaned
                .replaceFirst("(?i)^(?:bank details|bank name)\\s*[:=-]?\\s*", "")
                .replaceAll("(?i)\\b(?:a/c type|a/c|account(?: no| number)?|ifsc|branch|grand total|amount payable|taxable value|total amount after tax|inv(?:oice)?\\s*value|email|tel\\.?|phone|website)\\b.*$", "")
                .replaceAll("\\s*[-,:;]+\\s*\\d{4,}.*$", "")
                .trim();
        named = extractNamedBank(cleaned);
        if (named != null) {
            return named;
        }
        if ((cleaned.equalsIgnoreCase("s Bank") || cleaned.equalsIgnoreCase("Our Bank")) && bankDetails != null) {
            java.util.regex.Matcher stateBankMatcher = java.util.regex.Pattern.compile("(?i)\\bState Bank of India\\b").matcher(bankDetails);
            if (stateBankMatcher.find()) {
                return "State Bank of India";
            }
        }
        String lower = cleaned.toLowerCase();
        if (lower.contains("details")
                || lower.contains("bank tame")
                || lower.contains("company's bank")
                || lower.contains("companys bank")
                || lower.contains("details of receiver")
                || lower.contains("details of consignee")) {
            return null;
        }
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("(?i)\\b([A-Z][A-Za-z.&]+(?:\\s+[A-Z][A-Za-z.&]+){0,4}\\s+Bank(?:\\s+of\\s+[A-Z][A-Za-z.&]+){0,2})\\b").matcher(cleaned);
        String best = null;
        while (matcher.find()) {
            best = matcher.group(1);
        }
        if (best != null) {
            return RegexUtil.normalizeLine(best);
        }
        return cleaned.toLowerCase().contains("bank") && cleaned.split("\\s+").length >= 2 ? cleaned : null;
    }

    private String cleanLocationValue(String value) {
        String cleaned = cleanSoftTextValue(value, 80);
        if (cleaned == null) {
            return null;
        }
        cleaned = cleaned.replaceFirst("^[>.,\\-\\s]+", "")
                .replaceFirst("^[A-Z]\\s+(?=[A-Z][a-z])", "")
                .replaceAll("\\s*\\(\\d{1,2}\\)\\s*$", "")
                .replaceAll("(?i),?\\s*state\\s*$", "")
                .replaceAll("(?i)\\s*place\\s+of\\s+supply.*$", "")
                .replaceAll("(?i)\\s*state\\s*code.*$", "")
                .replaceAll("[,.;:-]+$", "")
                .trim();
        String lower = cleaned.toLowerCase();
        if (cleaned.isBlank()
                || lower.contains("@")
                || lower.contains("invoice")
                || lower.contains("gstin")
                || cleaned.contains("|")
                || cleaned.contains("_")
                || cleaned.matches(".*[`~^<>\\[\\]].*")
                || lower.matches(".*\\b(?:speed post|past|output|figure|amount|taxable|bank)\\b.*")) {
            return null;
        }
        if (!cleaned.matches("[A-Za-z][A-Za-z .,&()/:-]{1,60}")) {
            return null;
        }
        if (normalizeCityToState(cleaned) == null
                && !List.of(
                "andhra pradesh", "arunachal pradesh", "assam", "bihar", "chhattisgarh", "goa", "gujarat",
                "haryana", "himachal pradesh", "jharkhand", "karnataka", "kerala", "madhya pradesh",
                "maharashtra", "manipur", "meghalaya", "mizoram", "nagaland", "odisha", "punjab",
                "rajasthan", "sikkim", "tamil nadu", "telangana", "tripura", "uttar pradesh",
                "uttarakhand", "west bengal", "delhi", "new delhi", "hyderabad", "secunderabad",
                "kota", "rawatbhata", "rawatbhatta", "mysore", "mysuru", "ahmedabad", "surat",
                "mumbai", "pune", "chennai", "bangalore", "bengaluru", "coimbatore", "warangal"
        ).contains(lower)) {
            String alphaOnly = lower.replaceAll("[^a-z ]", " ").replaceAll("\\s+", " ").trim();
            if (alphaOnly.isBlank() || alphaOnly.split(" ").length > 4) {
                return null;
            }
        }
        return cleaned;
    }

    private String cleanPlaceOfSupplyValue(String value, String state, String stateCode) {
        String cleaned = cleanLocationValue(value);
        if (cleaned != null) {
            String normalized = normalizeCityToState(normalizeStateCase(cleaned));
            return normalized != null ? normalized : normalizeStateCase(cleaned);
        }
        String stateValue = cleanLocationValue(state);
        if (stateValue != null) {
            return normalizeStateCase(stateValue);
        }
        return stateCode == null ? null : normalizeStateCase(STATE_CODE_TO_NAME.get(stateCode));
    }

    /**
     * Map well-known city names to their state names for place-of-supply normalization.
     */
    private String normalizeCityToState(String value) {
        if (value == null) {
            return null;
        }
        String lower = value.toLowerCase().trim();
        return switch (lower) {
            case "hyderabad", "secunderabad", "warangal" -> "Telangana";
            case "kota", "jaipur", "udaipur", "jodhpur", "rawatbhata", "rawatbhatta" -> "Rajasthan";
            case "ahmedabad", "surat", "vadodara", "rajkot", "gandhinagar" -> "Gujarat";
            case "mumbai", "pune", "nagpur", "nashik", "thane" -> "Maharashtra";
            case "bangalore", "bengaluru", "mysore", "mysuru" -> "Karnataka";
            case "chennai", "coimbatore", "madurai" -> "Tamil Nadu";
            case "delhi", "new delhi" -> "Delhi";
            case "kolkata", "howrah" -> "West Bengal";
            case "lucknow", "kanpur", "agra", "varanasi" -> "Uttar Pradesh";
            case "bhopal", "indore" -> "Madhya Pradesh";
            case "chandigarh" -> "Chandigarh";
            case "patna" -> "Bihar";
            case "bhubaneswar" -> "Odisha";
            case "guwahati" -> "Assam";
            case "thiruvananthapuram", "kochi", "cochin" -> "Kerala";
            case "visakhapatnam", "vizag", "vijayawada" -> "Andhra Pradesh";
            default -> null;
        };
    }

    private String cleanStateValue(String value, String stateCode, String placeOfSupply) {
        String cleaned = cleanLocationValue(value);
        if (cleaned != null) {
            return normalizeStateCase(cleaned);
        }
        String place = cleanLocationValue(placeOfSupply);
        if (place != null && !place.matches(".*\\d.*")) {
            return normalizeStateCase(place);
        }
        return stateCode == null ? null : normalizeStateCase(STATE_CODE_TO_NAME.get(stateCode));
    }

    private String cleanAddressValue(String value) {
        String cleaned = cleanSoftTextValue(value, 240);
        if (cleaned == null) {
            return null;
        }
        cleaned = cleaned
                .replaceFirst("(?i)^\\(?\\s*(?:details of (?:receiver|recipient|consignee)\\s*\\([^)]*\\)|ship(?:ped)? to|bill(?:ed)? to|buyer(?:\\s*\\(bill to\\))?)\\s*\\)?\\s*[,:-]*\\s*", "")
                .replaceAll("(?i)\\b(?:www\\.|https?://)\\S+", "")
                .replaceAll("(?i)\\bemail[-: ]*\\S+", "")
                .replaceAll("(?i)\\b(?:invoice no|invoice date|delivery note|dispatch(?:ed)?(?: through)?|mode/terms|despatch document no|dispatch document no|gstin(?:/uin)?|dated|purchase order(?: no)?|po(?: no| number)?|buyer's order|buyers order|order reference|transport(?:er)?|vehicle(?: no| number)?)\\b.*$", "")
                // Strip GSTIN patterns from address
                .replaceAll("(?i)\\b(?:gst\\s*no|gstin)\\s*[:=-]?\\s*\\d{2}[A-Z]{5}\\d{4}[A-Z][A-Z0-9]Z[A-Z0-9]\\b", "")
                .replaceAll("\\b\\d{2}[A-Z]{5}\\d{4}[A-Z][A-Z0-9]Z[A-Z0-9]\\b", "")
                // Strip PAN patterns from address
                .replaceAll("(?i)\\bPAN\\s*[-:=]?\\s*[A-Z]{5}\\d{4}[A-Z]\\b", "")
                // Strip state code metadata
                .replaceAll("(?i),?\\s*State\\s*(?:Code)?\\s*[-:=]?\\s*\\d{1,2}\\b", "")
                .replaceAll("(?i),?\\s*State\\s*[-:=]?\\s*[A-Za-z]+\\s*,?\\s*State\\s*Code\\s*[-:=]?\\s*\\d{1,2}\\b", "")
                .replaceAll(",?\\s*\\b\\d{8,18}\\b\\s*$", "")
                .replaceAll("\\s*,\\s*,+", ", ")
                .trim();
        cleaned = truncateAddressAfterPincode(cleaned);
        cleaned = cleaned.replaceAll("(?i)\\b(?:po(?: no| number)?|purchase order|buyer's order|buyers order|dated|date)\\b\\s*[:=-]?\\s*[A-Z0-9./-]+", "")
                .replaceAll("(?i)\\b\\d{1,2}[-/]\\d{1,2}[-/]\\d{2,4}\\b", "")
                .replaceAll("(?i)\\b(?:dispatch doc(?:ument)? no|delivery note date|bill of lading|lr[-/ ]?rr no|terms of delivery|by\\s+[A-Za-z. ]*roadlines?)\\b.*$", "")
                .replaceAll("(?i)\\b(?:invoice|gstin|vehicle|transport|dispatch|bank details|amount payable|grand total|taxable value)\\b.*$", "")
                .replaceAll("(^|,\\s*)\\d+(\\s*,|$)", "$1")
                .replaceAll("\\s*,\\s*,+", ", ")
                .trim();
        cleaned = collapseRepeatedSegments(cleaned);
        String lower = cleaned.toLowerCase();
        boolean addressSignal = OcrLayoutUtil.isAddressLike(lower)
                || lower.contains("department")
                || lower.contains("atomic energy")
                || lower.contains("nuclear")
                || lower.contains("complex")
                || lower.contains("stores")
                || cleaned.matches(".*\\d{6}.*");
        if (cleaned.length() < 10 || !addressSignal) {
            return null;
        }
        return RegexUtil.normalizeLine(cleaned);
    }

    private String truncateAddressAfterPincode(String value) {
        if (value == null || value.isBlank()) {
            return value;
        }
        java.util.regex.Matcher matcher = PRIORITY_PINCODE_PATTERN.matcher(value);
        if (matcher.find()) {
            return value.substring(0, matcher.end()).trim();
        }
        return value;
    }

    private String bestWebsiteSource(String website, String vendorAddress) {
        if (website != null && website.toLowerCase().contains("www.")) {
            return website;
        }
        if (vendorAddress != null && vendorAddress.toLowerCase().contains("www.")) {
            return vendorAddress;
        }
        return website;
    }

    private String normalizeStateCase(String value) {
        if (value == null || value.isBlank()) {
            return value;
        }
        String trimmed = value.trim();
        if (!trimmed.equals(trimmed.toUpperCase())) {
            return trimmed;
        }
        StringBuilder builder = new StringBuilder(trimmed.length());
        boolean upperNext = true;
        for (char ch : trimmed.toCharArray()) {
            if (!Character.isLetter(ch)) {
                builder.append(ch);
                upperNext = ch == ' ' || ch == '-';
                continue;
            }
            builder.append(upperNext ? Character.toUpperCase(ch) : Character.toLowerCase(ch));
            upperNext = false;
        }
        return builder.toString();
    }

    private String cleanAccountNumber(String value, String bankDetails) {
        String labeledFromValue = extractLabeledAccountNumber(value);
        if (labeledFromValue != null) {
            return labeledFromValue;
        }
        String candidate = cleanSoftTextValue(value, 30);
        if (candidate != null) {
            String digits = candidate.replaceAll("\\D", "");
            if (digits.length() >= 8 && digits.length() <= 18) {
                return digits;
            }
        }
        String labeledFromDetails = extractLabeledAccountNumber(bankDetails);
        if (labeledFromDetails != null) {
            return labeledFromDetails;
        }
        if (value != null) {
            java.util.regex.Matcher valueDigits = java.util.regex.Pattern.compile("\\b\\d{12,18}\\b").matcher(value);
            if (valueDigits.find()) {
                return valueDigits.group();
            }
        }
        if (bankDetails == null) {
            return null;
        }
        java.util.regex.Matcher digitMatcher = java.util.regex.Pattern.compile("\\b\\d{8,18}\\b").matcher(bankDetails);
        String best = null;
        while (digitMatcher.find()) {
            String digits = digitMatcher.group();
            if (best == null || digits.length() > best.length()) {
                best = digits;
            }
        }
        return best;
    }

    private String cleanIfscValue(String value, String bankDetails) {
        String candidate = normalizeIfscCandidate(cleanSoftTextValue(value, 20));
        if (candidate != null) {
            return candidate;
        }
        String labeledFromDetails = extractLabeledIfsc(bankDetails);
        if (labeledFromDetails != null) {
            return labeledFromDetails;
        }
        if (bankDetails == null) {
            return null;
        }
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("(?i)\\b[A-Z0-9]{11}\\b").matcher(bankDetails);
        while (matcher.find()) {
            String normalized = normalizeIfscCandidate(matcher.group());
            if (normalized != null) {
                return normalized;
            }
        }
        return null;
    }

    private String cleanEmailValue(String value) {
        if (value == null) {
            return null;
        }
        String cleaned = cleanSoftTextValue(value, 200);
        if (cleaned == null) {
            return null;
        }
        cleaned = cleaned.replaceAll("(?i)\\bemail[-: ]*", " ");
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("(?i)[\\w.%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}").matcher(cleaned);
        return matcher.find() ? matcher.group().toLowerCase() : null;
    }

    private String cleanWebsiteValue(String value) {
        if (value == null) {
            return null;
        }
        String cleaned = cleanSoftTextValue(value, 200);
        if (cleaned == null) {
            return null;
        }
        cleaned = cleaned.replaceAll("(?i)\\bwebsite[-: ]*", " ");
        int wwwIndex = cleaned.toLowerCase().indexOf("www.");
        boolean hadWww = wwwIndex >= 0;
        if (hadWww) {
            cleaned = cleaned.substring(wwwIndex);
        }
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("(?i)(?:https?://|www\\.)?[A-Z0-9.-]+\\.(?:com|in|org|net|co\\.in|biz|info)").matcher(cleaned);
        if (!matcher.find()) {
            return null;
        }
        String website = matcher.group();
        String lower = website.toLowerCase();
        if (lower.contains("gmail.com") || lower.contains("rediffmail.com") || lower.contains("yahoo.com")
                || lower.contains("hotmail.com") || lower.contains("outlook.com")) {
            return null;
        }
        return hadWww && !website.toLowerCase().startsWith("www.") ? "www." + website : website;
    }

    private void normalizeTaxableValueAgainstSubtotal(InvoiceData data) {
        if (data == null) {
            return;
        }
        Double subtotal = AmountUtil.parseAmount(data.getSubTotal());
        Double taxableValue = AmountUtil.parseAmount(data.getTaxableValue());
        Double totalAmount = AmountUtil.parseAmount(data.getTotalAmount());
        if (subtotal == null) {
            return;
        }
        if (taxableValue == null) {
            data.setTaxableValue(AmountUtil.formatAmount(subtotal));
            return;
        }
        boolean mirrorsGrandTotal = totalAmount != null && AmountUtil.approximatelyEquals(taxableValue, totalAmount) && subtotal < totalAmount;
        boolean suspiciousInflation = taxableValue > subtotal * 1.05;
        if (mirrorsGrandTotal || suspiciousInflation) {
            data.setTaxableValue(AmountUtil.formatAmount(subtotal));
        }
    }

    private String cleanPanValue(String value, String gstin) {
        String cleaned = value == null ? null : value.replaceAll("[^A-Za-z0-9]", "").toUpperCase();
        if (cleaned != null && cleaned.matches("[A-Z]{5}\\d{4}[A-Z]")) {
            return cleaned;
        }
        if (RegexUtil.isValidGstin(gstin)) {
            return gstin.substring(2, 12);
        }
        return null;
    }

    private String cleanStateCodeValue(String value, String buyerGstin) {
        if (value != null && value.matches("\\d{1,2}")) {
            return String.format("%02d", Integer.parseInt(value));
        }
        if (RegexUtil.isValidGstin(buyerGstin)) {
            return buyerGstin.substring(0, 2);
        }
        return null;
    }

    private String cleanPincodeValue(String value) {
        if (value == null) {
            return null;
        }
        // Try to find a pincode in the text, preferring values near address context
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("\\b(\\d{6})\\b").matcher(value);
        String best = null;
        while (matcher.find()) {
            String candidate = matcher.group(1);
            if (candidate.startsWith("0")) {
                continue;
            }
            // Reject 6-digit numbers that look like monetary amounts (e.g. 224200, 100000)
            // Indian pincodes range from 110001 to 855XXX
            int numeric = Integer.parseInt(candidate);
            if (numeric < 100000 || numeric > 899999) {
                continue;
            }
            // Prefer pincodes that appear near address keywords
            int matchStart = matcher.start();
            String preceding = value.substring(Math.max(0, matchStart - 40), matchStart).toLowerCase();
            boolean nearAddress = preceding.contains("pin") || preceding.contains("code")
                    || preceding.contains("post") || preceding.contains("dist")
                    || preceding.contains("state") || preceding.contains(",")
                    || preceding.contains("road") || preceding.contains("near");
            if (nearAddress || best == null) {
                best = candidate;
            }
            if (nearAddress) {
                return best;
            }
        }
        return best;
    }

    private String cleanPaymentTermsValue(String value) {
        if (!isCleanBusinessText(value, 3, 40)) {
            return null;
        }
        String cleaned = truncateAtInvoiceNoise(RegexUtil.normalizeLine(value))
                .replaceFirst("(?i)^\\b(?:mode/terms of payment|terms of payment|payment terms)\\b\\s*[:=-]?\\s*", "")
                .replaceAll("(?i)\\bchallan(?: no(?: & date)?)?\\b.*$", "")
                .replaceAll("(?i)\\b(?:destination|dispatch(?:ed)?|vehicle|bank details|invoice no|gstin)\\b.*$", "")
                .trim();
        String lower = cleaned.toLowerCase();
        if (!(lower.contains("day")
                || lower.contains("credit")
                || lower.contains("advance")
                || lower.contains("immediate")
                || lower.contains("net")
                || lower.contains("due")
                || cleaned.matches(".*\\d+.*"))) {
            return null;
        }
        if (cleaned.contains("|") || cleaned.contains("_")) {
            return null;
        }
        return cleaned.isBlank() ? null : cleaned;
    }

    private String normalizeIfscCandidate(String value) {
        if (value == null) {
            return null;
        }
        String cleaned = value.replaceAll("[^A-Za-z0-9]", "").toUpperCase();
        if (cleaned.length() != 11) {
            return null;
        }
        String prefix = cleaned.substring(0, 4)
                .replace('0', 'O')
                .replace('1', 'I')
                .replace('5', 'S')
                .replace('8', 'B')
                .replace('2', 'Z')
                .replace('6', 'G');
        prefix = switch (prefix) {
            case "HOFC", "HOBI" -> "HDFC";
            case "SBII" -> "SBIN";
            case "BKIO" -> "BKID";
            default -> prefix;
        };
        String suffix = cleaned.substring(4)
                .replace('O', '0')
                .replace('Q', '0')
                .replace('D', '0');
        String normalized = prefix + suffix;
        return normalized.matches("[A-Z]{4}0[A-Z0-9]{6}") ? normalized : null;
    }

    private String normalizeKnownBankOcr(String value) {
        if (value == null) {
            return null;
        }
        return value
                .replaceAll("(?i)\\bHOFC\\b", "HDFC")
                .replaceAll("(?i)\\bHOBI\\b", "HDFC")
                .replaceAll("(?i)\\bSBII\\b", "SBIN");
    }

    private String collapseRepeatedSegments(String value) {
        if (value == null || value.isBlank()) {
            return value;
        }
        String normalized = RegexUtil.normalizeLine(value);
        List<String> deduped = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String part : normalized.split("\\s*,\\s*")) {
            String candidate = RegexUtil.normalizeLine(part);
            if (candidate.isBlank()) {
                continue;
            }
            String key = RegexUtil.normalizeForComparison(candidate);
            if (seen.add(key)) {
                deduped.add(candidate);
            }
        }
        normalized = String.join(", ", deduped);
        String[] words = normalized.split("\\s+");
        if (words.length >= 4 && words.length % 2 == 0) {
            int half = words.length / 2;
            String first = String.join(" ", java.util.Arrays.copyOfRange(words, 0, half));
            String second = String.join(" ", java.util.Arrays.copyOfRange(words, half, words.length));
            if (RegexUtil.normalizeForComparison(first).equals(RegexUtil.normalizeForComparison(second))) {
                normalized = first;
            }
        }
        return normalized;
    }

    private String cleanTransportDetailsValue(String value) {
        if (!isCleanBusinessText(value, 3, 60)) {
            return null;
        }
        String cleaned = truncateAtInvoiceNoise(RegexUtil.normalizeLine(value))
                .replaceFirst("(?i)^(?:dispatched\\s+through|dispatch\\s+through|ed\\s+through|transport(?:er)?(?:\\s+name)?|er\\s*nm\\.?|nm\\.?)\\s*[:=-]?\\s*", "")
                .replaceAll("(?i)\\b(?:destination|vehicle number|vehicle no|delivery note|inv(?:oice)?\\s*value|grand total|amount payable|place of supply)\\b.*$", "")
                .trim();
        String lower = cleaned.toLowerCase();
        if (lower.equals("destination")
                || lower.equals("dispatch")
                || lower.contains("@")
                || lower.contains("phone")
                || lower.matches(".*\\b\\d{1,2}[-/]\\d{1,2}[-/]\\d{2,4}\\b.*")) {
            return null;
        }
        boolean transportSignal = RegexUtil.containsAnyKeyword(lower, List.of("transport", "transporter", "logistics", "roadline", "roadlines", "roadway", "roadways", "courier", "cargo", "express", "travels"));
        boolean modeSignal = List.of("by road", "road", "canter", "truck", "lorry", "air", "sea", "rail").contains(lower);
        return (transportSignal || modeSignal) && cleaned.matches(".*[A-Za-z].*") ? cleaned : null;
    }

    private String cleanVehicleNumberValue(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.replaceAll("[^A-Za-z0-9]", "").toUpperCase();
        return normalized.matches("[A-Z]{2}\\d{1,2}[A-Z]{1,3}\\d{3,4}") ? normalized : null;
    }

    private String cleanBankDetailsValue(String value) {
        if (!isCleanBusinessText(value, 8, 100)) {
            return null;
        }
        String cleaned = RegexUtil.normalizeLine(value)
                .replaceAll("(?i)\\b(?:grand total|amount payable|taxable value|total amount after tax|inv(?:oice)?\\s*value|email|tel\\.?|phone)\\b.*$", "")
                .trim();
        String lower = cleaned.toLowerCase();
        boolean bankSignal = lower.contains("bank") || lower.contains("account") || lower.contains("a/c") || lower.contains("ifsc");
        boolean numericSignal = cleaned.matches(".*\\d{8,18}.*") || cleaned.matches(".*\\b[A-Z]{4}0[A-Z0-9]{6}\\b.*");
        return bankSignal && numericSignal ? cleaned : null;
    }

    private String cleanFinancialAmount(String value) {
        Double amount = AmountUtil.parseAmount(value);
        if (amount == null || amount <= 0) {
            return null;
        }
        return AmountUtil.formatAmount(amount);
    }

    private String cleanFinancialAmountAllowZero(String value) {
        Double amount = AmountUtil.parseAmount(value);
        if (amount == null) {
            return null;
        }
        return AmountUtil.formatAmount(amount);
    }

    private String preferNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private String firstNonBlank(String... values) {
        return preferNonBlank(values);
    }

    private String preferLongerValue(String current, String candidate) {
        if (candidate == null || candidate.isBlank()) {
            return current;
        }
        if (current == null || current.isBlank()) {
            return candidate;
        }
        return candidate.length() > current.length() ? candidate : current;
    }

    private void applyMissingFieldDefaults(InvoiceData data) {
        data.setInvoiceNumber(defaultValue(data.getInvoiceNumber()));
        data.setInvoiceDate(defaultValue(data.getInvoiceDate()));
        data.setVendorName(defaultValue(data.getVendorName()));
        data.setVendorGstin(defaultValue(data.getVendorGstin()));
        data.setBuyerName(defaultValue(data.getBuyerName()));
        data.setBuyerGstin(defaultValue(data.getBuyerGstin()));
        data.setPoNumber(defaultValue(data.getPoNumber()));
        data.setPoDate(defaultValue(data.getPoDate()));
        data.setOrderReference(defaultValue(data.getOrderReference()));
        data.setDeliveryNote(defaultValue(data.getDeliveryNote()));
        data.setDispatchThrough(defaultValue(data.getDispatchThrough()));
        data.setTransporterName(defaultValue(data.getTransporterName()));
        data.setTransportDetails(defaultValue(data.getTransportDetails()));
        data.setVehicleNumber(defaultValue(data.getVehicleNumber()));
        data.setDestination(defaultValue(data.getDestination()));
        data.setPlaceOfSupply(defaultValue(data.getPlaceOfSupply()));
        data.setPaymentTerms(defaultValue(data.getPaymentTerms()));
        data.setBankName(defaultValue(data.getBankName()));
        data.setAccountNumber(defaultValue(data.getAccountNumber()));
        data.setIfscCode(defaultValue(data.getIfscCode()));
        data.setBranch(defaultValue(data.getBranch()));
        data.setIrn(defaultValue(data.getIrn()));
        data.setAckNumber(defaultValue(data.getAckNumber()));
        data.setEwayBill(defaultValue(data.getEwayBill()));
        data.setVendorPhone(defaultValue(data.getVendorPhone()));
        data.setVendorEmail(defaultValue(data.getVendorEmail()));
        data.setVendorWebsite(defaultValue(data.getVendorWebsite()));
        data.setVendorAddress(defaultValue(data.getVendorAddress()));
        data.setBuyerAddress(defaultValue(data.getBuyerAddress()));
        data.setVendorPAN(defaultValue(data.getVendorPAN()));
        data.setVendorCIN(defaultValue(data.getVendorCIN()));
        data.setMsmeNumber(defaultValue(data.getMsmeNumber()));
        data.setState(defaultValue(data.getState()));
        data.setStateCode(defaultValue(data.getStateCode()));
        data.setPincode(defaultValue(data.getPincode()));
        data.setSubTotal(defaultValue(data.getSubTotal()));
        data.setTaxableValue(defaultValue(data.getTaxableValue()));
        data.setCgst(defaultValue(data.getCgst()));
        data.setSgst(defaultValue(data.getSgst()));
        data.setIgst(defaultValue(data.getIgst()));
        data.setTaxAmount(defaultValue(data.getTaxAmount()));
        data.setRoundOff(defaultValue(data.getRoundOff()));
        data.setTotalAmount(defaultValue(data.getTotalAmount()));
        data.setCurrency(data.getCurrency() == null || data.getCurrency().isBlank() ? "INR" : data.getCurrency());
        data.setLineItems(data.getLineItems() == null ? List.of() : data.getLineItems());
    }

    private String defaultValue(String value) {
        return value == null || value.isBlank() ? InvoiceData.NOT_MENTIONED : value;
    }

    private boolean isCleanBusinessText(String value, int minLength, int maxLength) {
        if (value == null) {
            return false;
        }
        String cleaned = RegexUtil.normalizeLine(value);
        if (cleaned.length() < minLength || cleaned.length() > maxLength) {
            return false;
        }
        if (cleaned.contains("|") || cleaned.contains("_") || cleaned.matches(".*[`~^<>].*")) {
            return false;
        }
        int badSymbols = 0;
        for (char ch : cleaned.toCharArray()) {
            if (!Character.isLetterOrDigit(ch) && !Character.isWhitespace(ch) && "/-.,:&()".indexOf(ch) < 0) {
                badSymbols++;
            }
        }
        return badSymbols == 0 && cleaned.matches(".*[A-Za-z0-9].*");
    }

    private void normalizeLineItem(LineItem item) {
        if (item == null) {
            return;
        }
        item.setDescription(normalizeLineItemDescription(item.getDescription()));
        item.setHsn(item.getHsn() != null && item.getHsn().matches("\\d{4,8}") ? item.getHsn() : null);
        Double quantity = AmountUtil.parseAmount(item.getQuantity());
        item.setQuantity(quantity != null && quantity > 0 ? AmountUtil.formatAmount(quantity) : null);
        Double unitPrice = AmountUtil.parseAmount(item.getUnitPrice());
        item.setUnitPrice(unitPrice != null && unitPrice > 0 ? AmountUtil.formatAmount(unitPrice) : null);
        Double amount = AmountUtil.parseAmount(item.getAmount());
        item.setAmount(amount != null && amount > 0 ? AmountUtil.formatAmount(amount) : null);
    }

    private String normalizeLineItemDescription(String value) {
        if (value == null) {
            return null;
        }
        String cleaned = RegexUtil.normalizeLine(value.replace('|', ' ').replace('_', ' '));
        cleaned = cleaned.replaceAll("\\s{2,}", " ").trim();
        cleaned = cleaned.replaceFirst("^[,./:&()\\-]+", "").replaceFirst("[,./:&()\\-]+$", "").trim();
        return cleaned.isBlank() ? null : cleaned;
    }

    private boolean isHighPrecisionLineItem(LineItem item, Double ceiling) {
        String description = item.getDescription();
        Double quantity = AmountUtil.parseAmount(item.getQuantity());
        Double unitPrice = AmountUtil.parseAmount(item.getUnitPrice());
        Double amount = AmountUtil.parseAmount(item.getAmount());
        if (!isCleanLineItemDescription(description) || !description.matches(".*[A-Za-z].*")) {
            return false;
        }
        if (amount == null || amount <= 0) {
            return false;
        }
        String normalizedDescription = RegexUtil.normalizeLine(description);
        boolean hasStructuredNumbers = unitPrice != null && unitPrice > 0
                || item.getHsn() != null
                || quantity != null && quantity > 0 && normalizedDescription.matches("[A-Za-z].*");
        if (!hasStructuredNumbers) {
            return false;
        }
        String lower = description.toLowerCase();
        if (isLineItemNoiseDescription(lower)) {
            return false;
        }
        if (ceiling != null && amount > ceiling * 1.25) {
            return false;
        }
        int punctuation = 0;
        for (char ch : description.toCharArray()) {
            if (!Character.isLetterOrDigit(ch) && !Character.isWhitespace(ch) && "/-.,&()".indexOf(ch) < 0) {
                punctuation++;
            }
        }
        return punctuation <= Math.max(3, description.length() / 6);
    }

    private void enforceAmountConsistency(InvoiceData data) {
        Double subtotal = AmountUtil.parseAmount(data.getSubTotal());
        Double tax = AmountUtil.parseAmount(data.getTaxAmount());
        Double total = AmountUtil.parseAmount(data.getTotalAmount());

        if (total != null && subtotal != null && subtotal >= total) {
            data.setSubTotal(null);
            subtotal = null;
        }
        if (total != null && tax != null && tax >= total) {
            data.setTotalAmount(null);
            total = null;
        }
        if (subtotal != null && tax != null && total != null && !AmountUtil.approximatelyEquals(subtotal + tax, total)) {
            if (tax > total * 0.5) {
                data.setTotalAmount(null);
            } else if (subtotal > total) {
                data.setSubTotal(null);
            }
        }
    }

    private void reconstructFinancialsFromCandidates(InvoiceData data, LineIndexingService.Zones zones) {
        if (data == null || zones == null || zones.allLines.isEmpty()) {
            return;
        }
        Double total = AmountUtil.parseAmount(data.getTotalAmount());
        Double subtotal = AmountUtil.parseAmount(data.getSubTotal());
        Double tax = AmountUtil.parseAmount(data.getTaxAmount());
        if (total == null || (subtotal != null && tax != null && AmountUtil.approximatelyEquals(subtotal + tax, total))) {
            return;
        }

        List<LineIndexingService.IndexedLine> candidateLines = new ArrayList<>();
        candidateLines.addAll(zones.bottomZone);
        candidateLines.addAll(zones.tableZone);
        candidateLines.addAll(zones.allLines);

        List<AmountUtil.AmountCandidate> amountCandidates = AmountUtil.extractCandidates(candidateLines);
        ReconstructionChoice best = null;
        for (AmountUtil.AmountCandidate subtotalCandidate : amountCandidates) {
            if (subtotalCandidate.getValue() >= total || subtotalCandidate.isPercentToken()) {
                continue;
            }
            for (AmountUtil.AmountCandidate taxCandidate : amountCandidates) {
                if (subtotalCandidate == taxCandidate || taxCandidate.getValue() <= 0 || taxCandidate.isPercentToken()) {
                    continue;
                }
                if (subtotalCandidate.getValue() + taxCandidate.getValue() > total * 1.02
                        || subtotalCandidate.getValue() + taxCandidate.getValue() < total * 0.98) {
                    continue;
                }
                double ratio = taxCandidate.getValue() / subtotalCandidate.getValue();
                if (!looksLikeSupportedGstRatio(ratio)) {
                    continue;
                }
                double score = reconstructionScore(subtotalCandidate, taxCandidate, total);
                if (best == null || score > best.score()) {
                    best = new ReconstructionChoice(subtotalCandidate.getValue(), taxCandidate.getValue(), score);
                }
            }
        }

        if (best != null) {
            if (subtotal == null || !AmountUtil.approximatelyEquals(subtotal, best.subtotal())) {
                data.setSubTotal(AmountUtil.formatAmount(best.subtotal()));
            }
            if (data.getTaxableValue() == null || AmountUtil.parseAmount(data.getTaxableValue()) == null) {
                data.setTaxableValue(AmountUtil.formatAmount(best.subtotal()));
            }
            if (tax == null || !AmountUtil.approximatelyEquals(tax, best.tax())) {
                data.setTaxAmount(AmountUtil.formatAmount(best.tax()));
            }
        }
    }

    private boolean looksLikeSupportedGstRatio(double ratio) {
        for (double supported : List.of(0.05, 0.12, 0.18, 0.28)) {
            if (Math.abs(ratio - supported) <= 0.025) {
                return true;
            }
        }
        return false;
    }

    private double reconstructionScore(AmountUtil.AmountCandidate subtotalCandidate,
                                       AmountUtil.AmountCandidate taxCandidate,
                                       double total) {
        double score = 0.0;
        String subtotalLine = subtotalCandidate.getLine().getText().toLowerCase();
        String taxLine = taxCandidate.getLine().getText().toLowerCase();
        score += RegexUtil.containsAnyKeyword(subtotalLine, AmountUtil.SUBTOTAL_KEYWORDS) ? 50 : 0;
        score += RegexUtil.containsAnyKeyword(taxLine, AmountUtil.TAX_KEYWORDS) ? 55 : 0;
        score += subtotalCandidate.getLine().getLineNumber() * 0.35;
        score += taxCandidate.getLine().getLineNumber() * 0.40;
        score -= subtotalLine.contains("bank") ? 120 : 0;
        score -= taxLine.contains("bank") ? 120 : 0;
        score -= subtotalCandidate.getValue() >= total ? 90 : 0;
        score -= taxCandidate.getValue() >= total ? 120 : 0;
        score -= containsOcrGarbage(subtotalLine) ? 18 : 0;
        score -= containsOcrGarbage(taxLine) ? 18 : 0;
        score -= Math.abs(subtotalCandidate.getLine().getY() - taxCandidate.getLine().getY()) > 160 ? 16 : 0;
        score += Math.max(0, 18 - Math.abs(subtotalCandidate.getLine().getLineNumber() - taxCandidate.getLine().getLineNumber()));
        return score;
    }

    private boolean containsOcrGarbage(String value) {
        if (value == null) {
            return false;
        }
        return value.contains("|") || value.contains("_") || value.matches(".*[`~^<>].*");
    }

    private record ReconstructionChoice(double subtotal, double tax, double score) {
    }

    private record ProcessedTextContext(InvoiceOcrDocument document,
                                        LineIndexingService.Zones zones,
                                        List<String> redactedTokens) {
    }

    private void reconcileExpandedAmounts(InvoiceData data) {
        Double cgst = AmountUtil.parseAmount(data.getCgst());
        Double sgst = AmountUtil.parseAmount(data.getSgst());
        Double igst = AmountUtil.parseAmount(data.getIgst());
        Double tax = AmountUtil.parseAmount(data.getTaxAmount());
        double taxFromComponents = (cgst == null ? 0.0 : cgst) + (sgst == null ? 0.0 : sgst) + (igst == null ? 0.0 : igst);
        if (tax == null && taxFromComponents > 0) {
            data.setTaxAmount(AmountUtil.formatAmount(taxFromComponents));
            tax = taxFromComponents;
        }

        Double subtotal = AmountUtil.parseAmount(data.getSubTotal());
        Double total = AmountUtil.parseAmount(data.getTotalAmount());
        if (subtotal == null && data.getTaxableValue() != null) {
            subtotal = AmountUtil.parseAmount(data.getTaxableValue());
            if (subtotal != null) {
                data.setSubTotal(AmountUtil.formatAmount(subtotal));
            }
        }
        if (data.getTaxableValue() == null && subtotal != null) {
            data.setTaxableValue(AmountUtil.formatAmount(subtotal));
        }
        Double taxableValue = AmountUtil.parseAmount(data.getTaxableValue());
        if (subtotal != null && taxableValue != null && Math.abs(subtotal - taxableValue) <= 1.0) {
            subtotal = taxableValue;
            data.setSubTotal(AmountUtil.formatAmount(taxableValue));
        }
        if (subtotal != null && tax != null) {
            Double recomputedTotal = subtotal + tax;
            if (total == null || total <= tax || total <= subtotal || !AmountUtil.approximatelyEquals(total, recomputedTotal)) {
                data.setTotalAmount(AmountUtil.formatAmount(recomputedTotal));
            }
        }
    }

    private String truncateAtInvoiceNoise(String value) {
        if (value == null || value.isBlank()) {
            return value;
        }
        return value.replaceAll("(?i)\\b(?:destination|dispatch(?:ed)?(?: through)?|delivery note(?: date)?|bill of lading|lr[- /]?rr|motor vehicle(?: no)?|vehicle no|bank details|bank name|account(?: no| number)?|ifsc|invoice no|invoice date|invoice value|inv value(?: \\(in fig\\))?|invoice amt|gstin(?:/uin)?|bill to|billed to|ship(?:ped)? to|consignee|buyer|amount payable|grand total|taxable value|sub\\s*total|cgst|sgst|igst|irn|ack(?:nowledgement)?(?: no)?|e[ -]?way bill(?: no)?|email|tel\\.?|phone|website)\\b.*$", "").trim();
    }

    private boolean isLineItemNoiseDescription(String lower) {
        return lower.startsWith("total")
                || lower.startsWith("subtotal")
                || lower.startsWith("sub total")
                || lower.contains("grand total")
                || lower.contains("amount payable")
                || lower.contains("taxable value")
                || lower.contains("tax amount")
                || lower.contains("invoice value")
                || lower.contains("terms of payment")
                || lower.contains("bank details")
                || lower.contains("cgst")
                || lower.contains("sgst")
                || lower.contains("igst")
                || lower.contains("remarks")
                || lower.contains("seal nos")
                || lower.contains("batch no");
    }

    private boolean isCleanLineItemDescription(String value) {
        if (value == null) {
            return false;
        }
        String cleaned = normalizeLineItemDescription(value);
        if (cleaned == null) {
            return false;
        }
        if (cleaned.length() < 3 || cleaned.length() > 120) {
            return false;
        }
        if (cleaned.matches(".*[`~^<>].*")) {
            return false;
        }
        int badSymbols = 0;
        for (char ch : cleaned.toCharArray()) {
            if (!Character.isLetterOrDigit(ch) && !Character.isWhitespace(ch) && "'/-.,:&()*+%".indexOf(ch) < 0) {
                badSymbols++;
            }
        }
        return badSymbols == 0 && cleaned.matches(".*[A-Za-z0-9].*");
    }


    private static class GenericExtraction {
        private FieldExtractionResult<String> invoiceNumber;
        private FieldExtractionResult<String> invoiceDate;
        private FieldExtractionResult<String> vendorName;
        private FieldExtractionResult<String> buyerName;
        private FieldExtractionResult<String> poNumber;
        private FieldExtractionResult<String> vendorPhone;
        private FieldExtractionResult<String> pincode;
        private FieldExtractionResult<String> vehicleNumber;
        private FieldExtractionResult<String> subTotal;
        private FieldExtractionResult<String> taxAmount;
        private FieldExtractionResult<String> totalAmount;
        private GstinExtractor.Result gstins;
        private List<LineItem> lineItems;
        private Map<String, TemplateField> templateFields = new HashMap<>();
    }
}
