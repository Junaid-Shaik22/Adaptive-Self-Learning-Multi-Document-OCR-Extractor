package com.invoice.extractor.service.impl;

import com.invoice.extractor.service.TemplateService;
import com.invoice.extractor.template.Template;
import com.invoice.extractor.template.TemplateRepository;
import com.invoice.extractor.template.TemplateSignatureGenerator;
import org.springframework.stereotype.Service;

@Service
public class TemplateServiceImpl implements TemplateService {
    public static final int TEMPLATE_VERSION = 3;

    private final TemplateRepository templateRepository;

    public TemplateServiceImpl(TemplateRepository templateRepository) {
        this.templateRepository = templateRepository;
    }

    @Override
    public String generateSignature(String rawText) {
        return TemplateSignatureGenerator.generateSignature(rawText);
    }

    @Override
    public String generateLayoutSignature(String rawText) {
        return TemplateSignatureGenerator.generateLayoutSignature(rawText);
    }

    @Override
    public Template findTemplate(String signature) {
        return findTemplate(signature, null);
    }

    @Override
    public Template findTemplate(String signature, String rawText) {
        String layoutSignature = rawText == null ? null : generateLayoutSignature(rawText);
        Template best = null;
        for (Template template : templateRepository.loadTemplates()) {
            if (template.getVersion() != TEMPLATE_VERSION) {
                continue;
            }
            if (signature != null && signature.equals(template.getSignature())) {
                return template;
            }
            if (layoutSignature != null
                    && layoutSignature.equals(template.getLayoutSignature())
                    && (best == null || template.getQualityScore() > best.getQualityScore())) {
                best = template;
            }
        }
        return best;
    }
}
