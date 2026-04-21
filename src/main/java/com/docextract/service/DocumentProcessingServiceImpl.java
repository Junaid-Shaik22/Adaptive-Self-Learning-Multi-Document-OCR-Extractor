package com.docextract.service;

import com.docextract.detector.DocumentTypeDetector;
import com.docextract.factory.DocumentExtractor;
import com.docextract.factory.DocumentExtractorFactory;
import com.docextract.model.DocumentType;
import com.docextract.model.ExtractionResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.icepdf.core.pobjects.Document;
import org.icepdf.core.pobjects.Page;
import org.icepdf.core.util.GraphicsRenderingHints;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * DocumentProcessingServiceImpl – orchestrates the full pipeline.
 *
 * Pipeline:
 *   1. If PDF  → render each page to 300 DPI BufferedImage (ICEpdf)
 *   2. If Image → load directly
 *   3. For each image: preprocess (OpenCV) → OCR → clean text
 *   4. Merge cleaned text from all pages
 *   5. Detect document type
 *   6. Run appropriate extractor (Factory Pattern)
 *   7. Return ExtractionResult
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentProcessingServiceImpl implements DocumentProcessingService {

    private final ImagePreprocessingService preprocessingService;
    private final OCRService               ocrService;
    private final TextCleaningService      textCleaningService;
    private final DocumentTypeDetector     documentTypeDetector;
    private final DocumentExtractorFactory extractorFactory;

    @Value("${pdf.render.dpi:300}")
    private int pdfRenderDpi;

    // ─────────────────────────────────────────────────────────────────────────

    @Override
    public ExtractionResult process(MultipartFile file, boolean isPdf) throws Exception {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("Uploaded file is empty");
        }

        List<byte[]> imageBytesList = isPdf ? renderPdfToImages(file) : List.of(file.getBytes());
        int pagesProcessed = imageBytesList.size();

        if (pagesProcessed == 0) {
            throw new IllegalArgumentException("No readable pages found in the uploaded document");
        }

        log.info("Processing {} file '{}' with {} page/image item(s)",
                isPdf ? "PDF" : "image",
                file.getOriginalFilename() != null ? file.getOriginalFilename() : "unknown",
                pagesProcessed);

        // ── 2. Preprocess + OCR every page, then merge text ──────────────────
        StringBuilder combinedText = new StringBuilder();
        for (int i = 0; i < imageBytesList.size(); i++) {
            log.debug("Processing page/image {} of {}", i + 1, imageBytesList.size());
            String cleanedText = extractPageText(imageBytesList.get(i));
            log.debug("Page {} cleaned text length: {}", i + 1, cleanedText.length());
            combinedText.append(cleanedText).append("\n");
        }

        String finalText = combinedText.toString().trim();
        log.debug("Combined OCR text ({} chars):\n{}", finalText.length(),
                  finalText.length() > 500 ? finalText.substring(0, 500) + "..." : finalText);

        // ── 3. Detect document type ───────────────────────────────────────────
        DocumentType docType = documentTypeDetector.detect(finalText);
        log.info("Detected document type: {}", docType);

        // ── 4. Extract using Factory pattern ─────────────────────────────────
        DocumentExtractor extractor = extractorFactory.getExtractor(docType);
        ExtractionResult  result    = extractor.extract(finalText);

        // ── 5. Attach meta fields ─────────────────────────────────────────────
        result.setPagesProcessed(pagesProcessed);

        return result;
    }

    // ─── PDF -> image rendering (ICEpdf) ─────────────────────────────────────

    private List<byte[]> renderPdfToImages(MultipartFile file) throws Exception {
        Document document = new Document();
        List<byte[]> images = new ArrayList<>();

        try {
            byte[] pdfBytes = file.getBytes();
            document.setByteArray(pdfBytes, 0, pdfBytes.length, null);

            float scale = pdfRenderDpi / 72.0f;
            for (int pageIndex = 0; pageIndex < document.getNumberOfPages(); pageIndex++) {
                Image pageImage = document.getPageImage(
                        pageIndex,
                        GraphicsRenderingHints.SCREEN,
                        Page.BOUNDARY_CROPBOX,
                        0.0f,
                        scale
                );

                BufferedImage bufferedImage = toBufferedImage(pageImage);
                try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
                    ImageIO.write(bufferedImage, "png", baos);
                    images.add(baos.toByteArray());
                }
                bufferedImage.flush();
                pageImage.flush();
                log.debug("Rendered PDF page {} at {} DPI", pageIndex + 1, pdfRenderDpi);
            }

            return images;
        } catch (Exception e) {
            throw new IllegalArgumentException("Unable to render the uploaded PDF file", e);
        } finally {
            document.dispose();
        }
    }

    private BufferedImage toBufferedImage(Image image) {
        if (image instanceof BufferedImage bufferedImage) {
            return bufferedImage;
        }

        BufferedImage bufferedImage = new BufferedImage(
                image.getWidth(null),
                image.getHeight(null),
                BufferedImage.TYPE_INT_RGB
        );
        Graphics2D graphics = bufferedImage.createGraphics();
        graphics.drawImage(image, 0, 0, null);
        graphics.dispose();
        return bufferedImage;
    }

    private String extractPageText(byte[] imageBytes) throws Exception {
        List<String> ocrPasses = new ArrayList<>();
        byte[] preprocessed = preprocessingService.preprocess(imageBytes);

        addOcrPass(ocrPasses, imageBytes, null);
        addOcrPass(ocrPasses, imageBytes, 4);
        addOcrPass(ocrPasses, imageBytes, 11);
        addOcrPass(ocrPasses, preprocessed, null);
        addOcrPass(ocrPasses, preprocessed, 4);
        addOcrPass(ocrPasses, preprocessed, 11);

        for (byte[] regionVariant : buildRegionVariants(imageBytes)) {
            addOcrPass(ocrPasses, regionVariant, 6);
            addOcrPass(ocrPasses, regionVariant, 4);
            addOcrPass(ocrPasses, regionVariant, 11);
        }

        return textCleaningService.mergeCleanedTexts(ocrPasses);
    }

    private void addOcrPass(List<String> ocrPasses, byte[] imageBytes, Integer pageSegMode) {
        String rawText = pageSegMode == null
                ? ocrService.extractText(imageBytes)
                : ocrService.extractText(imageBytes, pageSegMode);

        if (rawText != null && !rawText.isBlank()) {
            ocrPasses.add(rawText);
        }
    }

    private List<byte[]> buildRegionVariants(byte[] imageBytes) throws Exception {
        List<byte[]> variants = new ArrayList<>();
        BufferedImage source = ImageIO.read(new ByteArrayInputStream(imageBytes));
        if (source == null || source.getWidth() == 0) {
            return variants;
        }

        int width = source.getWidth();
        int height = source.getHeight();
        double aspectRatio = (double) height / width;

        if (aspectRatio > 1.35d) {
            addScaledVariant(variants, crop(source, 0.0d, 0.00d, 1.00d, 0.56d), 2.6d);
            addScaledVariant(variants, crop(source, 0.0d, 0.42d, 1.00d, 0.58d), 2.8d);
            addScaledVariant(variants, crop(source, 0.18d, 0.60d, 0.74d, 0.18d), 4.0d);
            addScaledVariant(variants, crop(source, 0.08d, 0.76d, 0.84d, 0.14d), 4.2d);
        }

        if (aspectRatio < 1.20d) {
            addScaledVariant(variants, crop(source, 0.00d, 0.00d, 1.00d, 0.24d), 4.0d);
            addScaledVariant(variants, crop(source, 0.08d, 0.14d, 0.84d, 0.42d), 3.6d);
            addScaledVariant(variants, crop(source, 0.30d, 0.16d, 0.62d, 0.30d), 4.0d);
            addScaledVariant(variants, crop(source, 0.05d, 0.60d, 0.90d, 0.28d), 4.0d);
        }

        return variants;
    }

    private void addScaledVariant(List<byte[]> variants, BufferedImage image, double scale) throws Exception {
        if (image == null || image.getWidth() <= 0 || image.getHeight() <= 0) {
            return;
        }
        variants.add(bufferedImageToPng(scaleImage(image, scale)));
    }

    private BufferedImage crop(BufferedImage source, double xRatio, double yRatio, double widthRatio, double heightRatio) {
        int x = Math.max(0, Math.min(source.getWidth() - 1, (int) Math.round(source.getWidth() * xRatio)));
        int y = Math.max(0, Math.min(source.getHeight() - 1, (int) Math.round(source.getHeight() * yRatio)));
        int width = Math.max(1, Math.min(source.getWidth() - x, (int) Math.round(source.getWidth() * widthRatio)));
        int height = Math.max(1, Math.min(source.getHeight() - y, (int) Math.round(source.getHeight() * heightRatio)));
        return source.getSubimage(x, y, width, height);
    }

    private byte[] bufferedImageToPng(BufferedImage image) throws Exception {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            ImageIO.write(image, "png", baos);
            return baos.toByteArray();
        }
    }

    private BufferedImage scaleImage(BufferedImage source, double factor) {
        int scaledWidth = Math.max(1, (int) Math.round(source.getWidth() * factor));
        int scaledHeight = Math.max(1, (int) Math.round(source.getHeight() * factor));

        BufferedImage scaled = new BufferedImage(scaledWidth, scaledHeight, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = scaled.createGraphics();
        graphics.drawImage(source, 0, 0, scaledWidth, scaledHeight, null);
        graphics.dispose();
        return scaled;
    }
}
