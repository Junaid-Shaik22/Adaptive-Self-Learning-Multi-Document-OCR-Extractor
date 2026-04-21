package com.medical.extractor.service.impl;

import com.invoice.extractor.service.ConvertedPdfDocument;
import com.invoice.extractor.service.PdfPageConverter;
import com.medical.extractor.model.MedicalOcrDocument;
import com.medical.extractor.model.MedicalOcrPage;
import net.sourceforge.tess4j.Tesseract;
import net.sourceforge.tess4j.TesseractException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.awt.image.RescaleOp;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.*;

@Service
public class MedicalHybridOcrService {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(MedicalHybridOcrService.class);
    private static final int TARGET_DPI = 350;
    private static final Set<String> SUPPORTED_IMAGE_EXTENSIONS = Set.of(".jpg", ".jpeg", ".png", ".tif", ".tiff", ".bmp");
    private static final Set<String> SUPPORTED_IMAGE_CONTENT_TYPES = Set.of(
            "image/jpeg", "image/png", "image/tiff", "image/bmp", "image/x-ms-bmp"
    );

    private final PdfPageConverter pdfPageConverter;
    private final int configuredDpi;

  
    public MedicalHybridOcrService(PdfPageConverter pdfPageConverter,
                                   @Value("${invoice.pdf.render.dpi:300}") int configuredDpi) {
        this.pdfPageConverter = pdfPageConverter;
        this.configuredDpi = configuredDpi;
    }

    public MedicalOcrDocument extractDocument(MultipartFile file) {
        validateInput(file);
        try {
            List<MedicalOcrPage> pages = isPdf(file) ? extractPdfDocument(file) : extractImageDocument(file);
            return new MedicalOcrDocument(pages);
        } catch (Exception ex) {
            log.error("Medical Hybrid OCR failed", ex);
            throw new IllegalStateException("Medical OCR extraction failed: " + ex.getMessage(), ex);
        }
    }

    private void validateInput(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("Uploaded file is empty");
        }
        if (!isPdf(file) && !isSupportedImage(file)) {
            throw new IllegalArgumentException("Unsupported file type. Upload PDF, JPG, JPEG, PNG, TIFF, or BMP.");
        }
    }

    private boolean isPdf(MultipartFile file) {
        String filename = file.getOriginalFilename();
        String contentType = file.getContentType();
        return (filename != null && filename.toLowerCase(Locale.ROOT).endsWith(".pdf"))
                || (contentType != null && contentType.toLowerCase(Locale.ROOT).contains("pdf"));
    }

    private boolean isSupportedImage(MultipartFile file) {
        String contentType = file.getContentType();
        if (contentType != null && SUPPORTED_IMAGE_CONTENT_TYPES.contains(contentType.toLowerCase(Locale.ROOT))) {
            return true;
        }
        String filename = file.getOriginalFilename();
        if (filename == null) return false;
        String lowerName = filename.toLowerCase(Locale.ROOT);
        return SUPPORTED_IMAGE_EXTENSIONS.stream().anyMatch(lowerName::endsWith);
    }

    private List<MedicalOcrPage> extractPdfDocument(MultipartFile file) throws IOException, TesseractException {
        List<MedicalOcrPage> pages = new ArrayList<>();
        try (ConvertedPdfDocument convertedPdf = pdfPageConverter.convert(file)) {
            int pageNumber = 1;
            for (Path imagePath : convertedPdf.getImagePaths()) {
                BufferedImage image = ImageIO.read(imagePath.toFile());
                if (image != null) {
                    pages.add(new MedicalOcrPage(pageNumber++, imagePath.getFileName().toString(), processHybridOcr(image)));
                }
            }
        }
        return pages;
    }

    private List<MedicalOcrPage> extractImageDocument(MultipartFile file) throws IOException, TesseractException {
        List<BufferedImage> images = readInputImages(file);
        List<MedicalOcrPage> pages = new ArrayList<>();
        String sourceName = file.getOriginalFilename() == null ? "image" : file.getOriginalFilename();
        for (int i = 0; i < images.size(); i++) {
            pages.add(new MedicalOcrPage(i + 1, sourceName, processHybridOcr(images.get(i))));
        }
        return pages;
    }

    private List<BufferedImage> readInputImages(MultipartFile file) throws IOException {
        byte[] bytes = file.getBytes();
        List<BufferedImage> images = new ArrayList<>();
        try (ImageInputStream imageInputStream = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
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
                    } catch (Exception ignored) {
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

    private String processHybridOcr(BufferedImage image) throws TesseractException {
        // Step 1: Preprocessing base image
        BufferedImage resized = resizeToTargetDpi(image);
        BufferedImage basePreprocessed = applyBasePreprocessing(resized);
        
        // Step 2: Region splitting
        int h = basePreprocessed.getHeight();
        int topY = 0, topH = (int)(h * 0.3);
        int midY = topH, midH = (int)(h * 0.4);
        int botY = midY + midH, botH = h - (topH + midH);
        
        BufferedImage topRegion = basePreprocessed.getSubimage(0, topY, basePreprocessed.getWidth(), topH);
        BufferedImage midRegion = basePreprocessed.getSubimage(0, midY, basePreprocessed.getWidth(), midH);
        BufferedImage botRegion = basePreprocessed.getSubimage(0, botY, basePreprocessed.getWidth(), botH);

        // Step 3: Stronger preprocessing for Middle & Bottom (Handwriting strengthening)
        BufferedImage enhancedMid = strengthenHandwriting(midRegion);
        BufferedImage enhancedBot = strengthenHandwriting(botRegion);

        // Step 4: Two-pass OCR per region (Config 1 and Config 2)
        String topText1 = doOcr(topRegion, 3, 6); // Printed
        String topText2 = doOcr(topRegion, 1, 11); // Handwriting config on normal image

        String midText1 = doOcr(midRegion, 3, 6);
        String midText2 = doOcr(enhancedMid, 1, 11); 

        String botText1 = doOcr(botRegion, 3, 6);
        String botText2 = doOcr(enhancedBot, 1, 11);

        // Intelligently Combine Outputs -> Providing both versions for downstream extraction
        // We output a block for normal OCR, and a block for handwritten OCR, separated by a distinct marker so Extractor can choose best.
        
        StringBuilder combined = new StringBuilder();
        combined.append("--- OCR PASS 1 (PRINTED) ---\n");
        combined.append(topText1).append("\n").append(midText1).append("\n").append(botText1).append("\n");
        combined.append("--- OCR PASS 2 (HANDWRITTEN) ---\n");
        combined.append(topText2).append("\n").append(midText2).append("\n").append(botText2).append("\n");
        
        return combined.toString();
    }

    private BufferedImage resizeToTargetDpi(BufferedImage img) {
        double scale = (double) TARGET_DPI / configuredDpi;
        if(scale <= 1.05 && scale >= 0.95) return img; // Close enough, skip resize
        int width = (int) (img.getWidth() * scale);
        int height = (int) (img.getHeight() * scale);
        BufferedImage resized = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = resized.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.drawImage(img, 0, 0, width, height, null);
        g.dispose();
        return resized;
    }

    private BufferedImage applyBasePreprocessing(BufferedImage img) {
        BufferedImage gray = toGrayscale(img);
        BufferedImage contrasted = increaseContrast(gray);
        BufferedImage thresholded = fastAdaptiveThreshold(contrasted);
        return fastMedianFilter(thresholded);
    }

    private BufferedImage strengthenHandwriting(BufferedImage img) {
        // Dilation + Erosion to strengthen text lines (closing text gaps).
        // Since text is black (0) and bg is white (1) in thresholded image,
        // Dilation of text = shrinking white = minimum filter.
        // Erosion of text = expanding white = maximum filter.
        BufferedImage dilated = morphologicalMin(img);
        return morphologicalMax(dilated);
    }

    private BufferedImage toGrayscale(BufferedImage img) {
        BufferedImage gray = new BufferedImage(img.getWidth(), img.getHeight(), BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D g = gray.createGraphics();
        g.drawImage(img, 0, 0, null);
        g.dispose();
        return gray;
    }

    private BufferedImage increaseContrast(BufferedImage img) {
        RescaleOp op = new RescaleOp(1.3f, -30, null);
        return op.filter(img, null);
    }

    private BufferedImage fastAdaptiveThreshold(BufferedImage img) {
        int w = img.getWidth(), h = img.getHeight();
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_BYTE_BINARY);
        int window = 21;
        int half = window / 2;
        int thresholdOffset = 15;
        
        int[] pixels = img.getRaster().getPixels(0, 0, w, h, (int[])null);
        int[] outPixels = new int[w * h];

        // Using a moving average for faster adaptive thresholding
        for (int y = 0; y < h; y++) {
            int sum = 0;
            int count = 0;
            for (int x = 0; x < w; x++) {
                if (x == 0) {
                    for(int i = Math.max(0, x - half); i <= Math.min(w - 1, x + half); i++) {
                        sum += pixels[y * w + i];
                        count++;
                    }
                } else {
                    if (x - half - 1 >= 0) {
                        sum -= pixels[y * w + (x - half - 1)];
                        count--;
                    }
                    if (x + half < w) {
                        sum += pixels[y * w + (x + half)];
                        count++;
                    }
                }
                int threshold = (sum / count) - thresholdOffset;
                int val = pixels[y * w + x];
                outPixels[y * w + x] = val < threshold ? 0 : 1; 
            }
        }
        out.getRaster().setPixels(0, 0, w, h, outPixels);
        return out;
    }

    private BufferedImage fastMedianFilter(BufferedImage img) {
        // Instead of full O(N^2) median, doing a lightweight cross median or just passing through.
        // Since Tesseract handles binary noise relatively well, we do a quick cross-pass to remove speckles.
        int w = img.getWidth(), h = img.getHeight();
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_BYTE_BINARY);
        for(int y=1; y<h-1; y++) {
            for(int x=1; x<w-1; x++) {
                int sum = img.getRaster().getSample(x, y, 0) +
                          img.getRaster().getSample(x-1, y, 0) +
                          img.getRaster().getSample(x+1, y, 0) +
                          img.getRaster().getSample(x, y-1, 0) +
                          img.getRaster().getSample(x, y+1, 0);
                out.getRaster().setSample(x, y, 0, sum >= 3 ? 1 : 0);
            }
        }
        return out;
    }

    private BufferedImage morphologicalMin(BufferedImage img) { // Dilation of black text
        int w = img.getWidth(), h = img.getHeight();
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_BYTE_BINARY);
        for(int y=1; y<h-1; y++) {
            for(int x=1; x<w-1; x++) {
                if(img.getRaster().getSample(x, y, 0) == 0 ||
                   img.getRaster().getSample(x-1, y, 0) == 0 ||
                   img.getRaster().getSample(x+1, y, 0) == 0 ||
                   img.getRaster().getSample(x, y-1, 0) == 0 ||
                   img.getRaster().getSample(x, y+1, 0) == 0) {
                    out.getRaster().setSample(x, y, 0, 0);
                } else {
                    out.getRaster().setSample(x, y, 0, 1);
                }
            }
        }
        return out;
    }

    private BufferedImage morphologicalMax(BufferedImage img) { // Erosion of black text
        int w = img.getWidth(), h = img.getHeight();
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_BYTE_BINARY);
        for(int y=1; y<h-1; y++) {
            for(int x=1; x<w-1; x++) {
                if(img.getRaster().getSample(x, y, 0) == 1 ||
                   img.getRaster().getSample(x-1, y, 0) == 1 ||
                   img.getRaster().getSample(x+1, y, 0) == 1 ||
                   img.getRaster().getSample(x, y-1, 0) == 1 ||
                   img.getRaster().getSample(x, y+1, 0) == 1) {
                    out.getRaster().setSample(x, y, 0, 1);
                } else {
                    out.getRaster().setSample(x, y, 0, 0);
                }
            }
        }
        return out;
    }

    private String doOcr(BufferedImage image, int oem, int psm) {
        Tesseract tesseract = new Tesseract();
        tesseract.setDatapath("C:\\Program Files\\Tesseract-OCR\\tessdata");
        tesseract.setLanguage("eng");
        tesseract.setOcrEngineMode(oem);
        tesseract.setPageSegMode(psm);
        tesseract.setVariable("preserve_interword_spaces", "1");
        
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<String> future = executor.submit(() -> tesseract.doOCR(image));
            return future.get(30, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            log.warn("OCR region timed out (OEM: {}, PSM: {})", oem, psm);
            return "";
        } catch (Exception e) {
            log.error("OCR extraction error", e);
            return "";
        } finally {
            executor.shutdownNow();
        }
    }
}
