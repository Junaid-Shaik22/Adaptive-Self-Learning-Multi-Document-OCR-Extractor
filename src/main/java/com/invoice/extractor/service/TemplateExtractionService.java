package com.invoice.extractor.service;

import com.invoice.extractor.model.InvoiceData;
import com.invoice.extractor.template.Template;
import java.util.Map;

public interface TemplateExtractionService {
    InvoiceData extract(String rawText, Template template);

    class Impl implements TemplateExtractionService {
        @Override
        public InvoiceData extract(String rawText, Template template) {
            String[] lines = rawText.split("\\n");
            Map<String, ?> pos = template.getFieldPositions();
            InvoiceData data = new InvoiceData();
            // For each field, search within ±2 lines of stored position
            data.setInvoiceNumber(extractNear(lines, (Integer) pos.get("invoiceNumberLine")));
            data.setInvoiceDate(extractNear(lines, (Integer) pos.get("invoiceDateLine")));
            data.setVendorName(extractNear(lines, (Integer) pos.get("vendorNameLine")));
            data.setVendorGstin(extractNear(lines, (Integer) pos.get("vendorGstinLine")));
            data.setBuyerName(extractNear(lines, (Integer) pos.get("buyerStartLine")));
            data.setBuyerGstin(extractNear(lines, (Integer) pos.get("buyerGstinLine")));
            data.setSubTotal(extractNear(lines, (Integer) pos.get("subtotalLine")));
            data.setTaxAmount(extractNear(lines, (Integer) pos.get("taxLine")));
            data.setTotalAmount(extractNear(lines, (Integer) pos.get("totalLine")));
            data.setTemplateId(template.getTemplateId());
            // Line items and other fields can be handled similarly
            return data;
        }

        private String extractNear(String[] lines, Integer idx) {
            if (idx == null) return null;
            int start = Math.max(0, idx - 3);
            int end = Math.min(lines.length - 1, idx + 2);
            for (int i = start; i <= end; i++) {
                String val = lines[i].replaceAll("[^a-zA-Z0-9:/.-]", "").trim();
                if (!val.isEmpty()) return val;
            }
            return null;
        }
    }
}
