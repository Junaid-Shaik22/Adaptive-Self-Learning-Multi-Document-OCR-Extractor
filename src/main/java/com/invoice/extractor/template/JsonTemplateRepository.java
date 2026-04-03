package com.invoice.extractor.template;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Repository;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

@Repository
public class JsonTemplateRepository implements TemplateRepository {
    private final ObjectMapper objectMapper;
    private final Path templatePath;

    public JsonTemplateRepository() {
        this(Paths.get("templates.json"));
    }

    public JsonTemplateRepository(Path templatePath) {
        this.objectMapper = new ObjectMapper();
        this.templatePath = templatePath;
    }

    @Override
    public List<Template> loadTemplates() {
        if (!Files.exists(templatePath)) {
            return new ArrayList<>();
        }
        try {
            String raw = Files.readString(templatePath).trim();
            if (raw.isEmpty()) {
                return new ArrayList<>();
            }
            return objectMapper.readValue(raw, new TypeReference<List<Template>>() {
            });
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to load templates from " + templatePath, ex);
        }
    }

    @Override
    public void saveTemplates(List<Template> templates) {
        try {
            if (templatePath.getParent() != null) {
                Files.createDirectories(templatePath.getParent());
            }
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(templatePath.toFile(), templates);
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to save templates to " + templatePath, ex);
        }
    }
}
