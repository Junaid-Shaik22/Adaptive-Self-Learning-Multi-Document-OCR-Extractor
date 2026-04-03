package com.invoice.extractor.template;

import java.util.List;

public interface TemplateRepository {
    List<Template> loadTemplates();
    void saveTemplates(List<Template> templates);
}
