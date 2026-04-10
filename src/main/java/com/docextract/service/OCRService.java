package com.docextract.service;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import net.sourceforge.tess4j.Tesseract;
import net.sourceforge.tess4j.TesseractException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * OCRService – wraps Tess4J (Tesseract) for text extraction.
 *
 * Configuration (application.properties):
 *   tesseract.data.path  → path to tessdata folder
 *   tesseract.language   → eng
 *   tesseract.oem        → 1  (LSTM engine)
 *   tesseract.psm        → 6  (Uniform block of text)
 */
@Slf4j
@Service
public class OCRService {

    private static final List<String> COMMON_TESSDATA_PATHS = List.of(
            "C:/Program Files/Tesseract-OCR/tessdata",
            "C:/Program Files (x86)/Tesseract-OCR/tessdata",
            "src/main/resources/tessdata"
    );

    @Value("${tesseract.data.path:C:/Program Files/Tesseract-OCR/tessdata}")
    private String tessDataPath;

    @Value("${tesseract.language:eng}")
    private String language;

    @Value("${tesseract.oem:1}")
    private int oem;

    @Value("${tesseract.psm:6}")
    private int psm;

    private String resolvedDataPath;

    @PostConstruct
    public void init() {
        resolvedDataPath = resolveTessDataPath();
        log.info("Tesseract datapath set to: {}", resolvedDataPath);
        log.info("Tesseract initialized with lang={} OEM={} PSM={}", language, oem, psm);
    }

    /**
     * Extract text from preprocessed image bytes (PNG).
     *
     * @param imageBytes  PNG image bytes
     * @return            raw OCR text
     */
    public String extractText(byte[] imageBytes) {
        return extractText(imageBytes, null);
    }

    public String extractText(byte[] imageBytes, Integer pageSegMode) {
        Path tempFile = null;
        try {
            tempFile = Files.createTempFile("ocr_input_", ".png");
            Files.write(tempFile, imageBytes);

            String text = createTesseract(pageSegMode).doOCR(tempFile.toFile());
            log.debug("OCR extracted {} chars", text != null ? text.length() : 0);
            return text != null ? text : "";
        } catch (TesseractException e) {
            log.error("Tesseract OCR failed: {}", e.getMessage(), e);
            return "";
        } catch (Exception e) {
            log.error("OCR processing failed: {}", e.getMessage(), e);
            return "";
        } finally {
            if (tempFile != null) {
                try {
                    Files.deleteIfExists(tempFile);
                } catch (IOException ignored) {
                    log.debug("Could not delete temporary OCR image {}", tempFile);
                }
            }
        }
    }

    private Tesseract createTesseract(Integer pageSegMode) {
        if (resolvedDataPath == null) {
            resolvedDataPath = resolveTessDataPath();
        }

        Tesseract tesseract = new Tesseract();
        tesseract.setDatapath(resolvedDataPath);
        tesseract.setLanguage(language);
        tesseract.setOcrEngineMode(oem);
        tesseract.setPageSegMode(pageSegMode != null ? pageSegMode : psm);
        tesseract.setVariable("preserve_interword_spaces", "1");
        tesseract.setVariable("user_defined_dpi", "300");
        return tesseract;
    }

    private String resolveTessDataPath() {
        for (Path candidate : buildCandidates(tessDataPath)) {
            if (containsLanguageData(candidate)) {
                return candidate.toAbsolutePath().normalize().toString();
            }
        }

        String envPath = System.getenv("TESSDATA_PREFIX");
        for (Path candidate : buildCandidates(envPath)) {
            if (containsLanguageData(candidate)) {
                return candidate.toAbsolutePath().normalize().toString();
            }
        }

        for (String candidatePath : COMMON_TESSDATA_PATHS) {
            for (Path candidate : buildCandidates(candidatePath)) {
                if (containsLanguageData(candidate)) {
                    return candidate.toAbsolutePath().normalize().toString();
                }
            }
        }

        return extractBundledTessdata().toAbsolutePath().normalize().toString();
    }

    private List<Path> buildCandidates(String configuredPath) {
        List<Path> candidates = new ArrayList<>();
        if (configuredPath == null || configuredPath.isBlank()) {
            return candidates;
        }

        Path rawPath = Path.of(configuredPath);
        if (!rawPath.isAbsolute()) {
            rawPath = Path.of(System.getProperty("user.dir")).resolve(rawPath);
        }

        candidates.add(rawPath);
        if (!rawPath.getFileName().toString().equalsIgnoreCase("tessdata")) {
            candidates.add(rawPath.resolve("tessdata"));
        }
        return candidates;
    }

    private boolean containsLanguageData(Path directory) {
        if (directory == null) {
            return false;
        }
        return Files.isDirectory(directory)
                && Files.isRegularFile(directory.resolve(language + ".traineddata"));
    }

    private Path extractBundledTessdata() {
        try {
            Path tempDir = Files.createTempDirectory("docextract-tessdata-");
            copyResource("tessdata/" + language + ".traineddata", tempDir.resolve(language + ".traineddata"));
            copyOptionalResource("tessdata/osd.traineddata", tempDir.resolve("osd.traineddata"));
            tempDir.toFile().deleteOnExit();
            return tempDir;
        } catch (IOException e) {
            throw new IllegalStateException("Unable to prepare tessdata for Tesseract", e);
        }
    }

    private void copyResource(String resourcePath, Path outputPath) throws IOException {
        ClassPathResource resource = new ClassPathResource(java.util.Objects.requireNonNull(resourcePath, "resourcePath must not be null"));
        if (!resource.exists()) {
            throw new IllegalStateException("Missing required tessdata resource: " + resourcePath);
        }

        try (InputStream inputStream = resource.getInputStream()) {
            Files.copy(inputStream, outputPath, StandardCopyOption.REPLACE_EXISTING);
        }
        outputPath.toFile().deleteOnExit();
    }

    private void copyOptionalResource(String resourcePath, Path outputPath) throws IOException {
        ClassPathResource resource = new ClassPathResource(java.util.Objects.requireNonNull(resourcePath, "resourcePath must not be null"));
        if (!resource.exists()) {
            return;
        }

        try (InputStream inputStream = resource.getInputStream()) {
            Files.copy(inputStream, outputPath, StandardCopyOption.REPLACE_EXISTING);
        }
        outputPath.toFile().deleteOnExit();
    }
}
