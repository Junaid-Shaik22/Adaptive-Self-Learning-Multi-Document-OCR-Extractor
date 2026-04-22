package com.invoice.extractor.service.impl;

import com.invoice.extractor.model.InvoiceOcrDocument;
import com.invoice.extractor.model.InvoiceOcrPage;
import com.invoice.extractor.service.ConvertedPdfDocument;
import com.invoice.extractor.service.OcrService;
import com.invoice.extractor.service.PdfPageConverter;
import com.invoice.extractor.service.InvoiceTextCleaningService;
import com.invoice.extractor.util.DateUtil;
import com.invoice.extractor.util.OcrLayoutUtil;
import com.invoice.extractor.util.RegexUtil;
import com.invoice.extractor.util.TextUtil;
import net.sourceforge.tess4j.ITesseract;
import net.sourceforge.tess4j.Tesseract;
import net.sourceforge.tess4j.TesseractException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Service
public class OcrServiceImpl implements OcrService {
    private static final int DEFAULT_DPI = 300;
    private static final int UPSCALE_FACTOR = 2;
    private static final int[] PAGE_SEGMENTATION_MODES = {11, 6, 4};
    private static final long PAGE_TIMEOUT_MILLIS = 180_000L;
    private static final long OCR_ATTEMPT_TIMEOUT_MILLIS = 25_000L;
    private static final long FALLBACK_TIMEOUT_MILLIS = 12_000L;
    private static final int MIN_FALLBACK_DPI = 150;
    private static final Set<String> SUPPORTED_IMAGE_EXTENSIONS = Set.of(".jpg", ".jpeg", ".png", ".tif", ".tiff", ".bmp");
    private static final Set<String> SUPPORTED_IMAGE_CONTENT_TYPES = Set.of(
            "image/jpeg",
            "image/png",
            "image/tiff",
            "image/bmp",
            "image/x-ms-bmp"
    );

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(OcrServiceImpl.class);

    private final int dpi;
    private final PdfPageConverter pdfPageConverter;
    private final InvoiceTextCleaningService textCleaningService;

    @Autowired
    public OcrServiceImpl(PdfPageConverter pdfPageConverter,
                          InvoiceTextCleaningService textCleaningService,
                          @Value("${invoice.pdf.render.dpi:300}") int dpi) {
        this(pdfPageConverter, textCleaningService, dpi, true);
    }

    public OcrServiceImpl(int dpi) {
        this(new PopplerPdfPageConverter("pdftoppm", dpi, true), new InvoiceTextCleaningService(), dpi, false);
    }

    OcrServiceImpl(PdfPageConverter pdfPageConverter,
                   InvoiceTextCleaningService textCleaningService,
                   int dpi,
                   boolean ignored) {
        this.pdfPageConverter = pdfPageConverter;
        this.textCleaningService = textCleaningService;
        this.dpi = dpi <= 0 ? DEFAULT_DPI : dpi;
    }

    @Override
    public String extractText(MultipartFile file) {
        return extractDocument(file).getCombinedText();
    }

    @Override
    public InvoiceOcrDocument extractDocument(MultipartFile file) {
        try {
            validateInput(file);
            InvoiceOcrDocument document = isPdf(file)
                    ? extractPdfDocument(file)
                    : extractImageDocument(file);
            log.info("Invoice OCR processed {} page/image item(s)", document.getPageCount());
            log.info("OCR raw text:\n{}", document.getCombinedText());
            return document;
        } catch (IllegalArgumentException ex) {
            log.warn("OCR validation failed: {}", ex.getMessage());
            throw ex;
        } catch (Exception ex) {
            log.error("OCR extraction failed", ex);
            throw new IllegalStateException(resolveFailureMessage(file, ex), ex);
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
        String filename = file.getOriginalFilename();
        String contentType = file.getContentType();
        if (contentType != null && SUPPORTED_IMAGE_CONTENT_TYPES.contains(contentType.toLowerCase(Locale.ROOT))) {
            return true;
        }
        if (filename == null) {
            return false;
        }
        String lowerName = filename.toLowerCase(Locale.ROOT);
        for (String extension : SUPPORTED_IMAGE_EXTENSIONS) {
            if (lowerName.endsWith(extension)) {
                return true;
            }
        }
        return false;
    }

    private InvoiceOcrDocument extractPdfDocument(MultipartFile file) throws IOException, TesseractException {
        List<InvoiceOcrPage> pages = new ArrayList<>();
        try (ConvertedPdfDocument convertedPdf = pdfPageConverter.convert(file)) {
            int pageNumber = 1;
            for (Path imagePath : convertedPdf.getImagePaths()) {
                BufferedImage image = ImageIO.read(imagePath.toFile());
                if (image == null) {
                    log.warn("Skipping unreadable rendered page {}", imagePath);
                    continue;
                }
                pages.add(new InvoiceOcrPage(
                        pageNumber++,
                        imagePath.getFileName().toString(),
                        cleanPageText(extractBestText(image))
                ));
            }
        }
        if (pages.isEmpty()) {
            throw new IOException("No readable images were produced from the uploaded PDF");
        }
        return new InvoiceOcrDocument(pages);
    }

    private InvoiceOcrDocument extractImageDocument(MultipartFile file) throws IOException, TesseractException {
        List<BufferedImage> images = readInputImages(file);
        if (images.isEmpty()) {
            throw new IOException("Unable to read the uploaded image");
        }

        List<InvoiceOcrPage> pages = new ArrayList<>();
        String sourceName = file.getOriginalFilename() == null ? "image" : file.getOriginalFilename();
        for (int i = 0; i < images.size(); i++) {
            pages.add(new InvoiceOcrPage(
                    i + 1,
                    sourceName,
                    cleanPageText(extractBestText(images.get(i)))
            ));
        }
        return new InvoiceOcrDocument(pages);
    }

    private List<BufferedImage> readInputImages(MultipartFile file) throws IOException {
        byte[] bytes = file.getBytes();
        List<BufferedImage> images = readImageSequence(bytes);
        if (images.isEmpty()) {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
            if (image != null) {
                images.add(image);
            }
        }
        return images;
    }

    private List<BufferedImage> readImageSequence(byte[] bytes) throws IOException {
        List<BufferedImage> images = new ArrayList<>();
        try (ImageInputStream imageInputStream = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            if (imageInputStream == null) {
                return images;
            }
            Iterator<ImageReader> readers = ImageIO.getImageReaders(imageInputStream);
            if (!readers.hasNext()) {
                return images;
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(imageInputStream);
                int imageCount = 1;
                try {
                    imageCount = reader.getNumImages(true);
                } catch (IOException ignored) {
                }
                for (int index = 0; index < imageCount; index++) {
                    BufferedImage image = reader.read(index);
                    if (image != null) {
                        images.add(image);
                    }
                }
            } finally {
                reader.dispose();
            }
        }
        return images;
    }

    private String cleanPageText(String rawText) {
        String cleaned = textCleaningService.clean(rawText);
        return cleaned == null || cleaned.isBlank() ? TextUtil.cleanOcrText(rawText) : cleaned;
    }

    private String extractBestText(BufferedImage image) throws TesseractException {
        String bestText = "";
        double bestScore = Double.NEGATIVE_INFINITY;
        long deadline = System.currentTimeMillis() + PAGE_TIMEOUT_MILLIS;
        int timeoutCount = 0;
        for (BufferedImage variant : buildOcrVariants(image)) {
            if (System.currentTimeMillis() >= deadline) {
                log.warn("Stopping invoice OCR page early after {} ms and returning best partial text", PAGE_TIMEOUT_MILLIS);
                break;
            }
            for (int pageSegMode : PAGE_SEGMENTATION_MODES) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) {
                    break;
                }
                ITesseract tesseract = createTesseract(pageSegMode);
                OcrAttemptResult attempt = doOcrWithTimeoutResult(tesseract, variant, Math.min(OCR_ATTEMPT_TIMEOUT_MILLIS, remaining));
                if (attempt.timedOut()) {
                    timeoutCount++;
                }
                String candidate = attempt.text();
                if (candidate == null || candidate.isBlank()) {
                    continue;
                }
                double score = scoreOcrText(candidate);
                if (score > bestScore) {
                    bestScore = score;
                    bestText = candidate;
                }
            }
        }
        if ((bestText == null || bestText.isBlank()) && System.currentTimeMillis() < deadline) {
            String fallback = extractFastFallbackText(image, deadline - System.currentTimeMillis());
            if (fallback != null && !fallback.isBlank()) {
                bestText = fallback;
                bestScore = scoreOcrText(fallback);
            }
        }
        if ((bestText == null || bestText.isBlank()) && timeoutCount >= 2 && System.currentTimeMillis() < deadline) {
            String lowerDpiFallback = extractLowerDpiFallbackText(image, deadline - System.currentTimeMillis());
            if (lowerDpiFallback != null && !lowerDpiFallback.isBlank()) {
                bestText = lowerDpiFallback;
                bestScore = scoreOcrText(lowerDpiFallback);
            }
        }
        log.debug("Selected OCR text with score {}", bestScore);
        return bestText;
    }

    private String extractFastFallbackText(BufferedImage image, long remainingMillis) throws TesseractException {
        if (remainingMillis <= 0) {
            return "";
        }
        ITesseract tesseract = createTesseract(6);
        BufferedImage fallbackVariant = upscale(toGrayscale(image), 1);
        return doOcrWithTimeout(tesseract, fallbackVariant, Math.min(FALLBACK_TIMEOUT_MILLIS, remainingMillis));
    }

    private String extractLowerDpiFallbackText(BufferedImage image, long remainingMillis) throws TesseractException {
        if (remainingMillis <= 0) {
            return "";
        }
        int fallbackDpi = Math.max(MIN_FALLBACK_DPI, dpi / 2);
        ITesseract tesseract = createTesseract(6, fallbackDpi);
        BufferedImage grayscale = toGrayscale(image);
        BufferedImage reduced = downscaleIfLarge(grayscale, 1800);
        return doOcrWithTimeout(tesseract, reduced, Math.min(FALLBACK_TIMEOUT_MILLIS, remainingMillis));
    }

    private String doOcrWithTimeout(ITesseract tesseract, BufferedImage image, long timeoutMillis) throws TesseractException {
        return doOcrWithTimeoutResult(tesseract, image, timeoutMillis).text();
    }

    private OcrAttemptResult doOcrWithTimeoutResult(ITesseract tesseract, BufferedImage image, long timeoutMillis) throws TesseractException {
        java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            java.util.concurrent.Future<String> future = executor.submit(() -> tesseract.doOCR(image));
            return new OcrAttemptResult(future.get(Math.max(1L, timeoutMillis), java.util.concurrent.TimeUnit.MILLISECONDS), false);
        } catch (java.util.concurrent.TimeoutException timeoutException) {
            log.warn("Invoice OCR attempt timed out after {} ms", timeoutMillis);
            return new OcrAttemptResult("", true);
        } catch (java.util.concurrent.ExecutionException executionException) {
            Throwable cause = executionException.getCause();
            if (cause instanceof TesseractException tesseractException) {
                throw tesseractException;
            }
            throw new TesseractException(cause == null ? executionException.getMessage() : cause.getMessage());
        } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
            throw new TesseractException("Invoice OCR interrupted");
        } finally {
            executor.shutdownNow();
        }
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
        return createTesseract(pageSegMode, dpi);
    }

    private ITesseract createTesseract(int pageSegMode, int requestedDpi) {
        Tesseract tesseract = new Tesseract();
        tesseract.setDatapath("C:\\Program Files\\Tesseract-OCR\\tessdata");
        tesseract.setLanguage("eng");
        tesseract.setPageSegMode(pageSegMode);
        tesseract.setVariable("preserve_interword_spaces", "1");
        tesseract.setVariable("user_defined_dpi", String.valueOf(Math.max(72, requestedDpi)));
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
        score += countKeyword(lower, "hsn") * 8;
        score += countKeyword(lower, "qty") * 7;
        score += countKeyword(lower, "quantity") * 7;
        score += countKeyword(lower, "rate") * 5;
        score += OcrLayoutUtil.looksLikeTableHeader(lower) ? 24 : 0;
        score += countKeyword(lower, "medical certificate") * 18;
        score += countKeyword(lower, "recommended for leave") * 22;
        score += countKeyword(lower, "communication of leave") * 18;
        score += countKeyword(lower, "absence from duty") * 22;
        score += countKeyword(lower, "with effect from") * 16;
        score += countKeyword(lower, "advised rest") * 18;
        score += countKeyword(lower, "fitness to return to duty") * 10;
        score += countKeyword(lower, "fit to resume duty") * 10;
        score += countKeyword(lower, "medical officer") * 8;
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
        BufferedImage dpiImg = ensureDpi(deskewed, dpi);
        return removeBorders(dpiImg);
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

    private BufferedImage downscaleIfLarge(BufferedImage img, int maxDimension) {
        if (img.getWidth() <= maxDimension && img.getHeight() <= maxDimension) {
            return img;
        }
        double scale = Math.min((double) maxDimension / img.getWidth(), (double) maxDimension / img.getHeight());
        int width = Math.max(1, (int) Math.round(img.getWidth() * scale));
        int height = Math.max(1, (int) Math.round(img.getHeight() * scale));
        BufferedImage resized = new BufferedImage(width, height, img.getType());
        Graphics2D graphics = resized.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        graphics.drawImage(img, 0, 0, width, height, null);
        graphics.dispose();
        return resized;
    }

    private BufferedImage adaptiveThreshold(BufferedImage img) {
        int w = img.getWidth();
        int h = img.getHeight();
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_BYTE_BINARY);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int pixel = img.getRaster().getSample(x, y, 0);
                int threshold = 128;
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
        return img;
    }

    private BufferedImage ensureDpi(BufferedImage img, int dpi) {
        return img;
    }

    private BufferedImage removeBorders(BufferedImage img) {
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

    private String resolveFailureMessage(MultipartFile file, Exception ex) {
        String detail = rootCauseMessage(ex);
        if (isPdf(file) && detail != null) {
            String lower = detail.toLowerCase(Locale.ROOT);
            if (lower.contains("pdftoppm") || lower.contains("poppler")) {
                return "PDF OCR requires Poppler pdftoppm. Install Poppler and set 'invoice.pdf.poppler.command' to the full executable path.";
            }
        }
        if (detail != null && !detail.isBlank()) {
            return "OCR extraction failed: " + detail;
        }
        return "OCR extraction failed";
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

    private record OcrAttemptResult(String text, boolean timedOut) {
    }
}
