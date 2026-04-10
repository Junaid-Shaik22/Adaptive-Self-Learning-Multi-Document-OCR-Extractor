package com.invoice.extractor.service.impl;

import com.invoice.extractor.model.InvoiceData;
import com.invoice.extractor.service.TemplateLearningService;
import com.invoice.extractor.template.Template;
import com.invoice.extractor.template.TemplateField;
import com.invoice.extractor.template.TemplateRepository;
import com.invoice.extractor.template.TemplateSignatureGenerator;
import com.invoice.extractor.util.AmountUtil;
import com.invoice.extractor.util.DateUtil;
import com.invoice.extractor.util.OcrLayoutUtil;
import com.invoice.extractor.util.RegexUtil;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class TemplateLearningServiceImpl implements TemplateLearningService {
    private final TemplateRepository templateRepository;

    public TemplateLearningServiceImpl(TemplateRepository templateRepository) {
        this.templateRepository = templateRepository;
    }

    @Override
    public Template learnTemplate(String rawText, InvoiceData data, String signature, Map<String, TemplateField> fieldLines) {
        List<Template> templates = new ArrayList<>(templateRepository.loadTemplates());
        String layoutSignature = TemplateSignatureGenerator.generateLayoutSignature(rawText);
        double qualityScore = calculateQualityScore(data, fieldLines);
        for (Template existing : templates) {
            boolean exactMatch = signature != null && signature.equals(existing.getSignature());
            boolean layoutMatch = layoutSignature != null && layoutSignature.equals(existing.getLayoutSignature());
            boolean familyMatch = sameLayoutFamily(existing, data, fieldLines);
            if (exactMatch || layoutMatch || familyMatch) {
                if (shouldRefresh(existing, qualityScore, fieldLines)) {
                    existing.setSignature(signature);
                    existing.setLayoutSignature(layoutSignature);
                    existing.setQualityScore(qualityScore);
                    existing.setVendorName(data.getVendorName());
                    existing.setVendorGstin(data.getVendorGstin());
                    existing.setFieldPositions(fieldLines);
                }
                if (existing.getTemplateId() == null || existing.getTemplateId().isBlank()) {
                    existing.setTemplateId(UUID.randomUUID().toString());
                }
                existing.setVersion(TemplateServiceImpl.TEMPLATE_VERSION);
                templateRepository.saveTemplates(templates);
                return existing;
            }
        }

        Template template = new Template();
        template.setTemplateId(UUID.randomUUID().toString());
        template.setSignature(signature);
        template.setLayoutSignature(layoutSignature);
        template.setVersion(TemplateServiceImpl.TEMPLATE_VERSION);
        template.setQualityScore(qualityScore);
        template.setVendorName(data.getVendorName());
        template.setVendorGstin(data.getVendorGstin());
        template.setFieldPositions(fieldLines);
        templates.add(template);
        templateRepository.saveTemplates(templates);
        return template;
    }

    private boolean shouldRefresh(Template existing, double qualityScore, Map<String, TemplateField> fieldLines) {
        int existingFieldCount = existing.getFieldPositions() == null ? 0 : existing.getFieldPositions().size();
        int currentFieldCount = fieldLines == null ? 0 : fieldLines.size();
        return qualityScore >= existing.getQualityScore()
                || currentFieldCount > existingFieldCount
                || existing.getVendorGstin() == null
                || existing.getVendorName() == null;
    }

    private boolean sameLayoutFamily(Template existing, InvoiceData data, Map<String, TemplateField> fieldLines) {
        if (existing == null || existing.getFieldPositions() == null || fieldLines == null) {
            return false;
        }
        if (existing.getVendorGstin() == null || data.getVendorGstin() == null
                || !existing.getVendorGstin().equalsIgnoreCase(data.getVendorGstin())) {
            return false;
        }
        int score = 0;
        if (sameFieldWindow(existing.getFieldPositions().get("tableHeader"), fieldLines.get("tableHeader"), 3)) {
            score += 2;
        }
        if (sameFieldWindow(existing.getFieldPositions().get("totalAmount"), fieldLines.get("totalAmount"), 3)
                || sameFieldWindow(existing.getFieldPositions().get("totalLine"), fieldLines.get("totalLine"), 3)) {
            score += 2;
        }
        if (sameFieldWindow(existing.getFieldPositions().get("buyerName"), fieldLines.get("buyerName"), 4)) {
            score += 1;
        }
        if (sameFieldWindow(existing.getFieldPositions().get("vendorGstin"), fieldLines.get("vendorGstin"), 4)) {
            score += 1;
        }
        return score >= 2;
    }

    private boolean sameFieldWindow(TemplateField left, TemplateField right, int tolerance) {
        if (left == null || right == null) {
            return false;
        }
        if (left.getZone() == null || right.getZone() == null || !left.getZone().equalsIgnoreCase(right.getZone())) {
            return false;
        }
        return Math.abs(left.getRelativePosition() - right.getRelativePosition()) <= tolerance
                || Math.abs(left.getLineNumber() - right.getLineNumber()) <= tolerance;
    }

    private double calculateQualityScore(InvoiceData data, Map<String, TemplateField> fieldLines) {
        double score = 0.0;
        if (looksLikeInvoiceNumber(data.getInvoiceNumber())) {
            score += 0.14;
        }
        if (DateUtil.isValidInvoiceDate(data.getInvoiceDate())) {
            score += 0.10;
        }
        if (looksLikeName(data.getVendorName())) {
            score += 0.12;
        }
        if (RegexUtil.isValidGstin(data.getVendorGstin())) {
            score += 0.14;
        }
        if (looksLikeName(data.getBuyerName())) {
            score += 0.08;
        }
        if (RegexUtil.isValidGstin(data.getBuyerGstin())) {
            score += 0.10;
        }
        Double subtotal = AmountUtil.parseAmount(data.getSubTotal());
        Double tax = AmountUtil.parseAmount(data.getTaxAmount());
        Double total = AmountUtil.parseAmount(data.getTotalAmount());
        if (total != null) {
            score += 0.14;
        }
        if (subtotal != null) {
            score += 0.06;
        }
        if (tax != null) {
            score += 0.06;
        }
        if (subtotal != null && tax != null && total != null && AmountUtil.approximatelyEquals(subtotal + tax, total)) {
            score += 0.04;
        }
        if (data.getLineItems() != null && !data.getLineItems().isEmpty()) {
            score += 0.06;
        }
        score += Math.min(0.10, (fieldLines == null ? 0 : fieldLines.size()) * 0.01);
        return Math.min(1.0, score);
    }

    private boolean looksLikeInvoiceNumber(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        String normalized = RegexUtil.cleanToken(value);
        String lookalike = normalized.toUpperCase()
                .replace('0', 'O')
                .replace('1', 'I')
                .replace('7', 'T')
                .replace('5', 'S')
                .replace('8', 'B');
        if (lookalike.matches("^(INVOICE.*|VOUCHER.*|DATE.*|FOR|ORIGINAL.*|DUPLICATE.*|RECEIVER.*|SUPPLY.*|STORE.*|MATERIAL.*|ENTERPRISE.*|STATE.*|STATION.*|TOTAL.*|AMOUNT.*)$")) {
            return false;
        }
        return normalized.matches("(?i)(?=.{3,20}$)(?:\\d{3,12}|[a-z]{1,4}\\d{2,10}[a-z]?|[a-z0-9/-]*[/-][a-z0-9/-]+)");
    }

    private boolean looksLikeName(String value) {
        if (value == null || !value.matches(".*[A-Za-z].*") || value.matches("\\d+")) {
            return false;
        }
        String lower = value.toLowerCase();
        return !lower.contains("invoice")
                && !lower.contains("description")
                && !lower.contains("gstin")
                && !lower.contains("bank")
                && !lower.contains("ifsc")
                && !lower.contains("account")
                && !lower.contains("delivery note")
                && !lower.contains("mode/terms")
                && !lower.contains("reference no")
                && !OcrLayoutUtil.isLogisticsLike(lower);
    }
}
