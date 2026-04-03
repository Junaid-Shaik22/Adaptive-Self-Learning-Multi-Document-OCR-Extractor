package com.invoice.extractor.service.impl;

import com.invoice.extractor.extractor.BuyerExtractor;
import com.invoice.extractor.extractor.InvoiceDateExtractor;
import com.invoice.extractor.extractor.InvoiceNumberExtractor;
import com.invoice.extractor.extractor.LineItemExtractor;
import com.invoice.extractor.extractor.SubtotalExtractor;
import com.invoice.extractor.extractor.TaxExtractor;
import com.invoice.extractor.extractor.TotalExtractor;
import com.invoice.extractor.extractor.VendorExtractor;
import com.invoice.extractor.model.InvoiceData;
import com.invoice.extractor.service.TemplateExtractionService;
import com.invoice.extractor.template.Template;
import com.invoice.extractor.template.TemplateField;
import com.invoice.extractor.util.AmountUtil;
import com.invoice.extractor.util.RegexUtil;
import org.springframework.stereotype.Service;


import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;

@Service
public class TemplateExtractionServiceImpl implements TemplateExtractionService {
    private final InvoiceNumberExtractor invoiceNumberExtractor = new InvoiceNumberExtractor();
    private final InvoiceDateExtractor invoiceDateExtractor = new InvoiceDateExtractor();
    private final VendorExtractor vendorExtractor = new VendorExtractor();
    private final BuyerExtractor buyerExtractor = new BuyerExtractor();
    private final TotalExtractor totalExtractor = new TotalExtractor();
    private final TaxExtractor taxExtractor = new TaxExtractor();
    private final SubtotalExtractor subtotalExtractor = new SubtotalExtractor();
    private final LineItemExtractor lineItemExtractor = new LineItemExtractor();

    @Override
    public InvoiceData extract(String rawText, Template template) {
        InvoiceData data = new InvoiceData();
        if (template == null) {
            return data;
        }

        LineIndexingService.Zones zones = LineIndexingService.indexLinesAndZones(rawText);
        Map<String, TemplateField> fields = template.getFieldPositions();
        if (fields == null) {
            data.setTemplateId(template.getTemplateId());
            return data;
        }

        String vendorGstin = extractGstin(scopedLines(zones, fields.get("vendorGstin")));
        String buyerGstin = extractGstin(scopedLines(zones, fields.get("buyerGstin")));

        TemplateField invoiceNumberField = fields.get("invoiceNumber");
        TemplateField invoiceDateField = fields.get("invoiceDate");
        TemplateField vendorNameField = fields.get("vendorName");
        TemplateField buyerNameField = fields.get("buyerName");
        TemplateField subtotalField = fields.get("subTotal");
        TemplateField taxField = fields.get("taxAmount");
        TemplateField totalField = fields.get("totalAmount");

        data.setInvoiceNumber(invoiceNumberField == null ? null : invoiceNumberExtractor.extract(scopedZones(zones, invoiceNumberField)));
        data.setInvoiceDate(invoiceDateField == null ? null : invoiceDateExtractor.extract(scopedZones(zones, invoiceDateField), invoiceDateField.getLineNumber()));
        data.setVendorGstin(vendorGstin);
        data.setBuyerGstin(buyerGstin);
        data.setVendorName(vendorNameField == null ? null : vendorExtractor.extract(scopedZones(zones, vendorNameField), vendorGstin));
        data.setBuyerName(buyerNameField == null ? null : buyerExtractor.extract(scopedZones(zones, buyerNameField), buyerGstin));

        Double total = totalField == null ? null : AmountUtil.parseAmount(totalExtractor.extract(scopedZones(zones, totalField), null));
        String directSubtotal = subtotalField == null ? null : subtotalExtractor.extract(scopedZones(zones, subtotalField), null, null);
        Double subtotal = AmountUtil.parseAmount(directSubtotal);
        String taxValue = taxField == null ? null : taxExtractor.extract(scopedZones(zones, taxField), total, subtotal);
        Double tax = AmountUtil.parseAmount(taxValue);
        if (subtotal == null && subtotalField != null) {
            directSubtotal = subtotalExtractor.extract(scopedZones(zones, subtotalField), total, tax);
        }

        data.setSubTotal(directSubtotal);
        data.setTaxAmount(taxValue);
        data.setTotalAmount(AmountUtil.formatAmount(total));
        data.setLineItems(lineItemExtractor.extract(zones));
        data.setTemplateId(template.getTemplateId());
        return data;
    }

    private String extractGstin(List<LineIndexingService.IndexedLine> lines) {
        for (LineIndexingService.IndexedLine line : lines) {
            Matcher matcher = RegexUtil.GSTIN_PATTERN.matcher(line.getText().replaceAll("\\s+", ""));
            if (matcher.find()) {
                String gstin = matcher.group().toUpperCase();
                if (RegexUtil.isValidGstin(gstin)) {
                    return gstin;
                }
            }
            Matcher tokenMatcher = RegexUtil.GSTIN_TOKEN_PATTERN.matcher(line.getText().replaceAll("\\s+", ""));
            while (tokenMatcher.find()) {
                String repaired = RegexUtil.repairGstinCandidate(tokenMatcher.group());
                if (RegexUtil.isValidGstin(repaired)) {
                    return repaired;
                }
            }
        }
        return null;
    }

    private LineIndexingService.Zones scopedZones(LineIndexingService.Zones zones, TemplateField field) {
        LineIndexingService.Zones scoped = new LineIndexingService.Zones();
        scoped.allLines.addAll(zones.allLines);
        List<LineIndexingService.IndexedLine> scopedLines = scopedLines(zones, field);
        if (field == null || field.getZone() == null) {
            scoped.topZone.addAll(scopedLines);
            return scoped;
        }
        switch (field.getZone().toUpperCase()) {
            case "TOP" -> scoped.topZone.addAll(scopedLines);
            case "MIDDLE" -> scoped.middleZone.addAll(scopedLines);
            case "TABLE" -> scoped.tableZone.addAll(scopedLines);
            case "BOTTOM" -> scoped.bottomZone.addAll(scopedLines);
            default -> scoped.topZone.addAll(scopedLines);
        }
        return scoped;
    }

    private List<LineIndexingService.IndexedLine> scopedLines(LineIndexingService.Zones zones, TemplateField field) {
        if (field == null) {
            return new ArrayList<>();
        }
        List<LineIndexingService.IndexedLine> zoneLines = zones.getZone(field.getZone());
        List<LineIndexingService.IndexedLine> window = new ArrayList<>();
        for (int i = 0; i < zoneLines.size(); i++) {
            LineIndexingService.IndexedLine line = zoneLines.get(i);
            boolean relativeMatch = Math.abs(i - field.getRelativePosition()) <= 2 || Math.abs(line.getLineNumber() - field.getLineNumber()) <= 2;
            boolean keywordMatch = field.getKeyword() != null && line.getText().toLowerCase().contains(field.getKeyword().toLowerCase());
            if (relativeMatch || keywordMatch) {
                window.add(line);
                if (keywordMatch) {
                    if (i > 0) {
                        window.add(zoneLines.get(i - 1));
                    }
                    if (i + 1 < zoneLines.size()) {
                        window.add(zoneLines.get(i + 1));
                    }
                }
            }
        }
        if (window.isEmpty()) {
            window.addAll(zoneLines);
        }
        return window.stream().distinct().toList();
    }
}
