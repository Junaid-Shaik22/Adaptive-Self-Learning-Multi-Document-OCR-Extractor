package com.invoice.extractor.service;

import com.invoice.extractor.template.Template;

public interface TemplateService {
    String generateSignature(String rawText);
    String generateLayoutSignature(String rawText);
    Template findTemplate(String signature);
    Template findTemplate(String signature, String rawText);
}
