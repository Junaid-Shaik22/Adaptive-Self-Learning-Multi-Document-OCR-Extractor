package com.invoice.extractor.service.impl;

import com.invoice.extractor.extractor.BuyerExtractor;
import com.invoice.extractor.extractor.FieldExtractionResult;
import com.invoice.extractor.extractor.GstinExtractor;
import com.invoice.extractor.extractor.InvoiceDateExtractor;
import com.invoice.extractor.extractor.InvoiceNumberExtractor;
import com.invoice.extractor.extractor.LineItemExtractor;
import com.invoice.extractor.extractor.SubtotalExtractor;
import com.invoice.extractor.extractor.TaxExtractor;
import com.invoice.extractor.extractor.TotalExtractor;
import com.invoice.extractor.extractor.VendorExtractor;
import com.invoice.extractor.model.InvoiceData;
import com.invoice.extractor.model.LineItem;
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
import com.invoice.extractor.util.RegexUtil;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;


import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

@Service
public class InvoiceServiceImpl implements InvoiceService {
    private static final List<String> INVOICE_KEYWORDS = List.of("invoice no", "invoice number", "invoice #", "inv no", "bill no", "bill #");
    private static final List<String> DATE_KEYWORDS = List.of("date", "invoice date");
    private static final List<String> BUYER_KEYWORDS = List.of("bill to", "ship to", "consignee", "buyer");
    private static final List<String> GSTIN_KEYWORDS = List.of("gstin");

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
        String rawText = ocrService.extractText(file);
        LineIndexingService.Zones zones = LineIndexingService.indexLinesAndZones(rawText);
        GenericExtraction generic = extractGeneric(zones);

        String signature = templateService.generateSignature(rawText);
        Template template = templateService.findTemplate(signature, rawText);
        InvoiceData templateData = template != null ? templateExtractionService.extract(rawText, template) : new InvoiceData();

        Map<String, String> extractionMethod = new HashMap<>();
        InvoiceData data = new InvoiceData();
        data.setInvoiceNumber(resolveField("invoiceNumber", templateData.getInvoiceNumber(), this::isValidInvoiceNumber, generic.invoiceNumber, extractionMethod));
        data.setInvoiceDate(resolveField("invoiceDate", templateData.getInvoiceDate(), DateUtil::isValidInvoiceDate, generic.invoiceDate, extractionMethod));
        data.setVendorName(resolveField("vendor", templateData.getVendorName(), this::isValidName, generic.vendorName, extractionMethod));
        data.setBuyerName(resolveField("buyer", templateData.getBuyerName(), this::isValidName, generic.buyerName, extractionMethod));
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

        normalizeAmounts(data, extractionMethod, generic, zones);

        double confidence = ConfidenceCalculator.calculate(data, extractionMethod);
        data.setConfidenceScore(confidence);
        data.setStatus(shouldRequireReview(data, rawText, confidence) ? "REVIEW_REQUIRED" : "SUCCESS");
        if (shouldLearnTemplate(template, data, confidence, zones, generic.templateFields)) {
            templateLearningService.learnTemplate(rawText, data, signature, generic.templateFields);
        }
        return data;
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
        generic.vendorName = vendorExtractor.extractResult(zones, generic.gstins.getVendorGstin());
        generic.buyerName = buyerExtractor.extractResult(zones, generic.gstins.getBuyerGstin());
        generic.invoiceDate = dateExtractor.extractResult(zones, generic.invoiceNumber.getLineNumber());
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
        if (templateItems != null && !templateItems.isEmpty()) {
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

        if (total == null || !isLargestAmountInBottomZone(total, zones)) {
            data.setTotalAmount(generic.totalAmount.getValue());
            extractionMethod.put("total", generic.totalAmount.getMethod());
            total = AmountUtil.parseAmount(data.getTotalAmount());
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
            }
        }
        if ((tax == null || tax <= 0) && total != null && subtotal != null && subtotal < total) {
            data.setTaxAmount(AmountUtil.formatAmount(total - subtotal));
            extractionMethod.put("tax", "fallback");
            tax = AmountUtil.parseAmount(data.getTaxAmount());
        }
        if (total != null && subtotal != null && tax != null && !isAmountConsistent(subtotal, tax, total)) {
            Double recomputedTotal = subtotal + tax;
            if (isLargestAmountInBottomZone(total, zones) && isLargestAmountInBottomZone(recomputedTotal, zones)) {
                data.setTotalAmount(AmountUtil.formatAmount(recomputedTotal));
                extractionMethod.put("total", "fallback");
                total = recomputedTotal;
            }
        }
    }

    private FieldExtractionResult<String> resultOf(String value, String method, Integer lineNumber) {
        return new FieldExtractionResult<>(value, method == null ? "fallback" : normalizeMethod(method), lineNumber);
    }

    private boolean isValidInvoiceNumber(String value) {
        if (value == null) {
            return false;
        }
        String normalized = RegexUtil.repairInvoiceNumberCandidate(value);
        return RegexUtil.INVOICE_NUMBER_TOKEN_PATTERN.matcher(normalized).matches()
                && !DateUtil.isValidInvoiceDate(normalized);
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

    private boolean isValidTotal(String value, LineIndexingService.Zones zones) {
        Double amount = AmountUtil.parseAmount(value);
        return amount != null && isLargestAmountInBottomZone(amount, zones);
    }

    private boolean isValidTax(String value, String totalValue) {
        Double tax = AmountUtil.parseAmount(value);
        Double total = AmountUtil.parseAmount(totalValue);
        return tax != null && total != null && tax < total;
    }

    private boolean isValidSubtotal(String value, String totalValue) {
        Double subtotal = AmountUtil.parseAmount(value);
        Double total = AmountUtil.parseAmount(totalValue);
        return subtotal != null && total != null && subtotal < total;
    }

    private boolean isLargestAmountInBottomZone(Double amount, LineIndexingService.Zones zones) {
        double max = 0.0;
        for (var candidate : AmountUtil.extractCandidates(zones.bottomZone)) {
            String lineText = candidate.getLine().getText();
            boolean lineHasCurrencyToken = AmountUtil.extractRawNumericTokens(lineText).stream().anyMatch(AmountUtil::looksLikeCurrencyToken);
            if (candidate.isPercentToken() || AmountUtil.isIgnoredAmountLine(lineText)) {
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

    private boolean shouldRequireReview(InvoiceData data, String rawText, double confidence) {
        if (confidence < 0.5) {
            return true;
        }
        if (!isValidInvoiceNumber(data.getInvoiceNumber())) {
            return true;
        }
        if (!isValidName(data.getVendorName()) || !isValidGstin(data.getVendorGstin())) {
            return true;
        }
        if (!isValidTotal(data.getTotalAmount(), LineIndexingService.indexLinesAndZones(rawText))) {
            return true;
        }
        if (!isValidName(data.getBuyerName()) && !isValidGstin(data.getBuyerGstin())) {
            return true;
        }
        String lower = rawText == null ? "" : rawText.toLowerCase();
        if (lower.contains("tax invoice") && (data.getSubTotal() == null || data.getTaxAmount() == null)) {
            return true;
        }
        Double subtotal = AmountUtil.parseAmount(data.getSubTotal());
        Double tax = AmountUtil.parseAmount(data.getTaxAmount());
        Double total = AmountUtil.parseAmount(data.getTotalAmount());
        if (subtotal != null && tax != null && total != null && !isAmountConsistent(subtotal, tax, total)) {
            return true;
        }
        return false;
    }

    private boolean shouldLearnTemplate(Template template,
                                        InvoiceData data,
                                        double confidence,
                                        LineIndexingService.Zones zones,
                                        Map<String, TemplateField> templateFields) {
        boolean anchorIdentity = isValidName(data.getVendorName()) || isValidGstin(data.getVendorGstin());
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

    private static class GenericExtraction {
        private FieldExtractionResult<String> invoiceNumber;
        private FieldExtractionResult<String> invoiceDate;
        private FieldExtractionResult<String> vendorName;
        private FieldExtractionResult<String> buyerName;
        private FieldExtractionResult<String> subTotal;
        private FieldExtractionResult<String> taxAmount;
        private FieldExtractionResult<String> totalAmount;
        private GstinExtractor.Result gstins;
        private List<LineItem> lineItems;
        private Map<String, TemplateField> templateFields = new HashMap<>();
    }
}
