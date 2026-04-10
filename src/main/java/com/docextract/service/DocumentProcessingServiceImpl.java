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

    // ─── PDF → image rendering (PDFBox) ──────────────────────────────────────

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
        addOcrPass(ocrPasses, imageBytes, 11);
        addOcrPass(ocrPasses, preprocessed, null);

        for (byte[] regionVariant : buildRegionVariants(imageBytes)) {
            addOcrPass(ocrPasses, regionVariant, 6);
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

        if (aspectRatio > 2.2d) {
            variants.add(bufferedImageToPng(source.getSubimage(0, 0, width, Math.max(1, (int) (height * 0.62)))));

            int bottomStart = Math.min(height - 1, (int) (height * 0.55));
            BufferedImage bottom = source.getSubimage(0, bottomStart, width, height - bottomStart);
            variants.add(bufferedImageToPng(scaleImage(bottom, 3.0d)));

            int infoStart = Math.min(height - 1, (int) (height * 0.68));
            int infoHeight = Math.max(1, Math.min(height - infoStart, (int) (height * 0.22)));
            BufferedImage frontInfo = source.getSubimage(0, infoStart, width, infoHeight);
            variants.add(bufferedImageToPng(scaleImage(frontInfo, 4.0d)));
            return variants;
        }

        if (aspectRatio < 0.9d) {
            int upperX = Math.max(0, (int) (width * 0.12));
            int upperY = Math.max(0, (int) (height * 0.10));
            int upperWidth = Math.max(1, Math.min(width - upperX, (int) (width * 0.62)));
            int upperHeight = Math.max(1, Math.min(height - upperY, (int) (height * 0.18)));
            BufferedImage upperBand = source.getSubimage(upperX, upperY, upperWidth, upperHeight);
            variants.add(bufferedImageToPng(scaleImage(upperBand, 4.0d)));

            int centerX = Math.max(0, (int) (width * 0.14));
            int centerY = Math.max(0, (int) (height * 0.17));
            int centerWidth = Math.max(1, Math.min(width - centerX, (int) (width * 0.60)));
            int centerHeight = Math.max(1, Math.min(height - centerY, (int) (height * 0.46)));
            BufferedImage centerBand = source.getSubimage(centerX, centerY, centerWidth, centerHeight);
            variants.add(bufferedImageToPng(scaleImage(centerBand, 3.5d)));

            int detailsY = Math.max(0, (int) (height * 0.44));
            int detailsWidth = Math.max(1, Math.min(width, (int) (width * 0.62)));
            int detailsHeight = Math.max(1, Math.min(height - detailsY, (int) (height * 0.48)));
            BufferedImage detailsBand = source.getSubimage(0, detailsY, detailsWidth, detailsHeight);
            variants.add(bufferedImageToPng(scaleImage(detailsBand, 3.5d)));

            int lowerY = Math.max(0, (int) (height * 0.68));
            int lowerWidth = Math.max(1, Math.min(width, (int) (width * 0.52)));
            int lowerHeight = Math.max(1, Math.min(height - lowerY, (int) (height * 0.25)));
            BufferedImage lowerBand = source.getSubimage(0, lowerY, lowerWidth, lowerHeight);
            variants.add(bufferedImageToPng(scaleImage(lowerBand, 4.0d)));
        }

        return variants;
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
