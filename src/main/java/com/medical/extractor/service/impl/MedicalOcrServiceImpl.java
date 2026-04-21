package com.medical.extractor.service.impl;

import com.invoice.extractor.service.ConvertedPdfDocument;
import com.invoice.extractor.service.PdfPageConverter;
import com.medical.extractor.model.MedicalOcrDocument;
import com.medical.extractor.model.MedicalOcrPage;
import com.medical.extractor.model.OcrResult;
import com.medical.extractor.service.MedicalOcrService;
import com.medical.extractor.service.pipeline.MedicalOcrPipeline;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;

@Service
public class MedicalOcrServiceImpl implements MedicalOcrService {
    private static final Logger logger = LoggerFactory.getLogger(MedicalOcrServiceImpl.class);
    
    private static final Set<String> SUPPORTED_IMAGE_EXTENSIONS = Set.of(
            ".jpg", ".jpeg", ".png", ".tif", ".tiff", ".bmp"
    );
    private static final Set<String> SUPPORTED_IMAGE_CONTENT_TYPES = Set.of(
            "image/jpeg", "image/png", "image/tiff", "image/bmp", "image/x-ms-bmp"
    );

    private final PdfPageConverter pdfPageConverter;
    private final MedicalOcrPipeline ocrPipeline;

    public MedicalOcrServiceImpl(PdfPageConverter pdfPageConverter,
                               MedicalOcrPipeline ocrPipeline) {
        this.pdfPageConverter = pdfPageConverter;
        this.ocrPipeline = ocrPipeline;
    }

    @Override
    public MedicalOcrDocument extractDocument(MultipartFile file) {
        validate(file);
        try {
            List<MedicalOcrPage> pages = isPdf(file) 
                    ? extractPdfDocument(file) 
                    : extractImageDocument(file);
            return new MedicalOcrDocument(pages);
        } catch (IllegalArgumentException ex) {
            throw ex;
        } catch (Exception ex) {
            logger.error("Medical OCR extraction failed", ex);
            throw new IllegalStateException(resolveMedicalFailureMessage(file, ex), ex);
        }
    }

    private boolean isPdf(MultipartFile file) {
        String filename = file.getOriginalFilename();
        String contentType = file.getContentType();
        return (filename != null && filename.toLowerCase(Locale.ROOT).endsWith(".pdf"))
                || (contentType != null && contentType.toLowerCase(Locale.ROOT).contains("pdf"));
    }

    private List<MedicalOcrPage> extractPdfDocument(MultipartFile file) throws IOException {
        List<MedicalOcrPage> pages = new ArrayList<>();
        try (ConvertedPdfDocument convertedPdf = pdfPageConverter.convert(file)) {
            int pageNumber = 1;
            for (Path imagePath : convertedPdf.getImagePaths()) {
                BufferedImage image = ImageIO.read(imagePath.toFile());
                if (image != null) {
                    String extractedText = processImageWithPipeline(image);
                    MedicalOcrPage page = new MedicalOcrPage(pageNumber++, imagePath.getFileName().toString(), extractedText);
                    pages.add(page);
                }
            }
        }
        return pages;
    }

    private List<MedicalOcrPage> extractImageDocument(MultipartFile file) throws IOException {
        List<BufferedImage> images = readInputImages(file);
        if (images.isEmpty()) {
            images = List.of(new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB));
        }
        List<MedicalOcrPage> pages = new ArrayList<>();
        String sourceName = file.getOriginalFilename() == null ? "image" : file.getOriginalFilename();
        
        for (int i = 0; i < images.size(); i++) {
            String extractedText = processImageWithPipeline(images.get(i));
            MedicalOcrPage page = new MedicalOcrPage(i + 1, sourceName, extractedText);
            pages.add(page);
        }
        
        return pages;
    }

    private List<BufferedImage> readInputImages(MultipartFile file) throws IOException {
        byte[] bytes = file.getBytes();
        List<BufferedImage> images = new ArrayList<>();
        
        try (ImageInputStream imageInputStream = ImageIO.createImageInputStream(
                new ByteArrayInputStream(bytes))) {
            if (imageInputStream != null) {
                Iterator<ImageReader> readers = ImageIO.getImageReaders(imageInputStream);
                if (readers.hasNext()) {
                    ImageReader reader = readers.next();
                    try {
                        reader.setInput(imageInputStream);
                        int count = reader.getNumImages(true);
                        for (int i = 0; i < count; i++) {
                            BufferedImage img = reader.read(i);
                            if (img != null) images.add(img);
                        }
                    } finally {
                        reader.dispose();
                    }
                }
            }
        }
        
        if (images.isEmpty()) {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
            if (image != null) images.add(image);
        }
        
        return images;
    }

    private String processImageWithPipeline(BufferedImage image) {
        if (image == null) {
            logger.error("Image is null for pipeline processing");
            return "";
        }

        try {
            logger.debug("Processing image with hybrid OCR pipeline");
            OcrResult result = ocrPipeline.processImage(image);
            if (result.isSuccess()) {
                logger.debug("Pipeline processing complete");
                return result.getText();
            } else {
                logger.warn("Pipeline processing failed: {}", result.getErrorMessage());
                return "";
            }
        } catch (Exception ex) {
            logger.error("Error processing image with pipeline", ex);
            return "";
        }
    }

    private void validate(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("Uploaded medical certificate file is empty");
        }
        String contentType = file.getContentType();
        String filename = file.getOriginalFilename();
        boolean pdf = contentType != null && contentType.toLowerCase(Locale.ROOT).contains("pdf");
        boolean image = contentType != null && SUPPORTED_IMAGE_CONTENT_TYPES.contains(contentType.toLowerCase(Locale.ROOT));
        boolean extensionMatch = false;
        if (filename != null) {
            String lower = filename.toLowerCase(Locale.ROOT);
            extensionMatch = lower.endsWith(".pdf") || SUPPORTED_IMAGE_EXTENSIONS.stream().anyMatch(lower::endsWith);
        }
        if (!pdf && !image && !extensionMatch) {
            throw new IllegalArgumentException("Unsupported medical certificate file type. Upload PDF, JPG, JPEG, PNG, TIFF, or BMP.");
        }
    }

    private String resolveMedicalFailureMessage(MultipartFile file, Exception ex) {
        String detail = rootCauseMessage(ex);
        if (isPdf(file) && detail != null) {
            String lower = detail.toLowerCase(Locale.ROOT);
            if (lower.contains("pdftoppm") || lower.contains("poppler")) {
                return "Medical PDF OCR requires Poppler pdftoppm. Install Poppler and set 'invoice.pdf.poppler.command' to the full executable path.";
            }
        }
        if (detail == null || detail.isBlank()) {
            return "Medical OCR extraction failed";
        }
        if (detail.equalsIgnoreCase("invoice OCR extraction failed")
                || detail.equalsIgnoreCase("OCR extraction failed")) {
            return "Medical OCR extraction failed";
        }
        if (detail.toLowerCase(Locale.ROOT).startsWith("ocr extraction failed:")) {
            detail = detail.substring("ocr extraction failed:".length()).trim();
        }
        return "Medical OCR extraction failed: " + detail;
    }

    private String rootCauseMessage(Throwable throwable) {
        Throwable current = throwable;
        String best = null;
        while (current != null) {
            if (current.getMessage() != null && !current.getMessage().isBlank()) {
                best = current.getMessage().trim();
            }
            current = current.getCause();
        }
        return best;
    }
}
