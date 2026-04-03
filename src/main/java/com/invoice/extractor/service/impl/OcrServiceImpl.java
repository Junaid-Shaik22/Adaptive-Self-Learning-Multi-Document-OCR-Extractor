package com.invoice.extractor.service.impl;

import com.invoice.extractor.service.OcrService;
import com.invoice.extractor.util.DateUtil;
import com.invoice.extractor.util.RegexUtil;
import com.invoice.extractor.util.TextUtil;
import net.sourceforge.tess4j.ITesseract;
import net.sourceforge.tess4j.Tesseract;
import net.sourceforge.tess4j.TesseractException;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
@Service
public class OcrServiceImpl implements OcrService {
    private static final int DPI = 300;
    private static final int UPSCALE_FACTOR = 2;
    private static final int[] PAGE_SEGMENTATION_MODES = {11, 6, 4};

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(OcrServiceImpl.class);

    @Override
    public String extractText(MultipartFile file) {
        try {
            String fileName = file.getOriginalFilename();
            String text;
            if (fileName != null && fileName.toLowerCase().endsWith(".pdf")) {
                text = extractTextFromPdf(file);
            } else {
                text = extractTextFromImage(file);
            }
            log.info("OCR raw text:\n{}", text);
            return TextUtil.cleanOcrText(text);
        } catch (Exception e) {
            log.error("OCR extraction failed", e);
            return "";
        }
    }

    private String extractTextFromPdf(MultipartFile file) throws IOException, TesseractException {
        List<BufferedImage> images = pdfToImages(file);
        StringBuilder sb = new StringBuilder();
        for (BufferedImage img : images) {
            sb.append(extractBestText(img)).append("\n");
        }
        return sb.toString();
    }

    private List<BufferedImage> pdfToImages(MultipartFile file) throws IOException {
        List<BufferedImage> images = new ArrayList<>();
        try (PDDocument document = PDDocument.load(file.getInputStream())) {
            PDFRenderer pdfRenderer = new PDFRenderer(document);
            for (int page = 0; page < document.getNumberOfPages(); ++page) {
                BufferedImage bim = pdfRenderer.renderImageWithDPI(page, DPI);
                images.add(bim);
            }
        }
        return images;
    }

    private String extractTextFromImage(MultipartFile file) throws IOException, TesseractException {
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(file.getBytes()));
        return extractBestText(img);
    }

    private String extractBestText(BufferedImage image) throws TesseractException {
        String bestText = "";
        double bestScore = Double.NEGATIVE_INFINITY;
        for (BufferedImage variant : buildOcrVariants(image)) {
            for (int pageSegMode : PAGE_SEGMENTATION_MODES) {
                ITesseract tesseract = createTesseract(pageSegMode);
                String candidate = tesseract.doOCR(variant);
                double score = scoreOcrText(candidate);
                if (score > bestScore) {
                    bestScore = score;
                    bestText = candidate;
                }
            }
        }
        log.debug("Selected OCR text with score {}", bestScore);
        return bestText;
    }

    private List<BufferedImage> buildOcrVariants(BufferedImage image) {
        List<BufferedImage> variants = new ArrayList<>();
        variants.add(upscale(image, UPSCALE_FACTOR));
        BufferedImage grayscale = toGrayscale(image);
        variants.add(upscale(grayscale, UPSCALE_FACTOR));
        variants.add(upscale(preprocessImage(image), UPSCALE_FACTOR));
        return variants;
    }

    private ITesseract createTesseract(int pageSegMode) {
        Tesseract tesseract = new Tesseract();
        tesseract.setDatapath("C:\\Program Files\\Tesseract-OCR\\tessdata");
        tesseract.setLanguage("eng");
        tesseract.setPageSegMode(pageSegMode);
        tesseract.setVariable("preserve_interword_spaces", "1");
        tesseract.setVariable("user_defined_dpi", String.valueOf(DPI));
        return tesseract;
    }

    private double scoreOcrText(String text) {
        String cleaned = TextUtil.cleanOcrText(text);
        String lower = cleaned.toLowerCase(Locale.ROOT);
        double score = 0.0;
        score += countKeyword(lower, "invoice") * 10;
        score += countKeyword(lower, "invoice no") * 20;
        score += countKeyword(lower, "dated") * 12;
        score += countKeyword(lower, "gstin") * 15;
        score += countKeyword(lower, "bill to") * 10;
        score += countKeyword(lower, "ship to") * 10;
        score += countKeyword(lower, "amount") * 6;
        score += countPattern(RegexUtil.GSTIN_PATTERN, cleaned) * 35;
        score += DateUtil.findCandidateDates(cleaned).size() * 8;
        score += countPattern(RegexUtil.AMOUNT_PATTERN, cleaned) * 1.5;
        score -= countGarbageLines(cleaned) * 6;
        return score;
    }

    private BufferedImage preprocessImage(BufferedImage img) {
        BufferedImage gray = toGrayscale(img);
        BufferedImage thresholded = adaptiveThreshold(gray);
        BufferedImage denoised = medianFilter(thresholded);
        BufferedImage deskewed = deskew(denoised);
        BufferedImage dpiImg = ensureDpi(deskewed, DPI);
        BufferedImage borderless = removeBorders(dpiImg);
        return borderless;
    }

    private BufferedImage toGrayscale(BufferedImage img) {
        BufferedImage gray = new BufferedImage(img.getWidth(), img.getHeight(), BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D g = gray.createGraphics();
        g.drawImage(img, 0, 0, null);
        g.dispose();
        return gray;
    }

    private BufferedImage upscale(BufferedImage img, int factor) {
        BufferedImage upscaled = new BufferedImage(img.getWidth() * factor, img.getHeight() * factor, img.getType());
        Graphics2D graphics = upscaled.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        graphics.drawImage(img, 0, 0, upscaled.getWidth(), upscaled.getHeight(), null);
        graphics.dispose();
        return upscaled;
    }

    private BufferedImage adaptiveThreshold(BufferedImage img) {
        int w = img.getWidth();
        int h = img.getHeight();
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_BYTE_BINARY);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int pixel = img.getRaster().getSample(x, y, 0);
                int threshold = 128; // Simple threshold, can be improved
                out.getRaster().setSample(x, y, 0, pixel < threshold ? 0 : 1);
            }
        }
        return out;
    }

    private BufferedImage medianFilter(BufferedImage img) {
        int w = img.getWidth();
        int h = img.getHeight();
        BufferedImage out = new BufferedImage(w, h, img.getType());
        int[] window = new int[9];
        for (int y = 1; y < h - 1; y++) {
            for (int x = 1; x < w - 1; x++) {
                int k = 0;
                for (int j = -1; j <= 1; j++) {
                    for (int i = -1; i <= 1; i++) {
                        window[k++] = img.getRaster().getSample(x + i, y + j, 0);
                    }
                }
                java.util.Arrays.sort(window);
                out.getRaster().setSample(x, y, 0, window[4]);
            }
        }
        return out;
    }

    private BufferedImage deskew(BufferedImage img) {
        // Placeholder: real deskewing would use Hough transform or similar
        // For now, return as is
        return img;
    }

    private BufferedImage ensureDpi(BufferedImage img, int dpi) {
        // DPI is metadata, not pixel data; for OCR, image is already at correct DPI if rendered from PDFBox
        return img;
    }

    private BufferedImage removeBorders(BufferedImage img) {
        // Simple border removal: crop 2px from each side if border is black
        int w = img.getWidth();
        int h = img.getHeight();
        int border = 2;
        if (w > 2 * border && h > 2 * border) {
            return img.getSubimage(border, border, w - 2 * border, h - 2 * border);
        }
        return img;
    }

    private int countPattern(java.util.regex.Pattern pattern, String text) {
        int count = 0;
        java.util.regex.Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            count++;
        }
        return count;
    }

    private int countKeyword(String text, String keyword) {
        int count = 0;
        int index = 0;
        while ((index = text.indexOf(keyword, index)) != -1) {
            count++;
            index += keyword.length();
        }
        return count;
    }

    private int countGarbageLines(String text) {
        int garbage = 0;
        for (String line : text.split("\\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            int letterOrDigit = 0;
            for (char ch : trimmed.toCharArray()) {
                if (Character.isLetterOrDigit(ch)) {
                    letterOrDigit++;
                }
            }
            if (letterOrDigit < Math.max(3, trimmed.length() / 3)) {
                garbage++;
            }
        }
        return garbage;
    }
}
