package com.invoice.extractor.service;

import com.invoice.extractor.model.InvoiceData;
import com.invoice.extractor.template.Template;
import com.invoice.extractor.template.TemplateField;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.File;
import java.util.*;

public interface TemplateLearningService {
    Template learnTemplate(String rawText, InvoiceData data, String signature, Map<String, TemplateField> fieldLines);

    class Impl implements TemplateLearningService {
        private static final String TEMPLATE_FILE = "templates.json";

        @Override
        public Template learnTemplate(String rawText, InvoiceData data, String signature, Map<String, TemplateField> fieldLines) {
            try {
                ObjectMapper mapper = new ObjectMapper();
                List<Template> templates = new ArrayList<>();
                File file = new File(TEMPLATE_FILE);
                if (file.exists()) {
                    templates = Arrays.asList(mapper.readValue(file, Template[].class));
                }
                Template t = new Template();
                t.setTemplateId(UUID.randomUUID().toString());
                t.setSignature(signature);
                t.setVendorName(data.getVendorName());
                t.setVendorGstin(data.getVendorGstin());
                t.setFieldPositions(new HashMap<>(fieldLines));
                templates = new ArrayList<>(templates);
                templates.add(t);
                mapper.writerWithDefaultPrettyPrinter().writeValue(file, templates);
                return t;
            } catch (Exception e) {
                e.printStackTrace();
            }
            return null;
        }
    }
}
