package com.invoice.extractor.service.impl;

import com.invoice.extractor.service.OcrService;
import com.invoice.extractor.util.DateUtil;
import com.invoice.extractor.util.RegexUtil;
import com.invoice.extractor.util.TextUtil;
import net.sourceforge.tess4j.ITesseract;
import net.sourceforge.tess4j.Tesseract;
import net.sourceforge.tess4j.TesseractException;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
@Service
public class OcrServiceImpl implements OcrService {
    private static final int DEFAULT_DPI = 300;
    private static final int UPSCALE_FACTOR = 2;
    private static final int[] PAGE_SEGMENTATION_MODES = {11, 6, 4};

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(OcrServiceImpl.class);
    private final int dpi;

    public OcrServiceImpl() {
        this(DEFAULT_DPI);
    }

    public OcrServiceImpl(int dpi) {
        this.dpi = dpi <= 0 ? DEFAULT_DPI : dpi;
    }

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
        Path tempPdf = Files.createTempFile("invoice-", ".pdf");
        Path renderDir = Files.createTempDirectory("invoice-pages-");
        try {
            Files.write(tempPdf, file.getBytes());
            String embeddedText = extractEmbeddedPdfText(tempPdf);
            if (scoreOcrText(embeddedText) >= 60) {
                return embeddedText;
            }

            List<BufferedImage> images = renderPdfPages(tempPdf, renderDir);
            StringBuilder sb = new StringBuilder();
            for (BufferedImage img : images) {
                sb.append(extractBestText(img)).append("\n");
            }
            return sb.toString();
        } finally {
            deleteQuietly(tempPdf);
            deleteTreeQuietly(renderDir);
        }
    }

    private List<BufferedImage> renderPdfPages(Path pdfPath, Path renderDir) throws IOException {
        List<BufferedImage> images = new ArrayList<>();
        String script = """
                import fitz
                import sys
                from pathlib import Path

                pdf = Path(sys.argv[1])
                out_dir = Path(sys.argv[2])
                out_dir.mkdir(parents=True, exist_ok=True)
                doc = fitz.open(pdf)
                scale = float(sys.argv[3]) / 72
                matrix = fitz.Matrix(scale, scale)
                for index in range(doc.page_count):
                    page = doc.load_page(index)
                    pix = page.get_pixmap(matrix=matrix, alpha=False)
                    output = out_dir / f"page_{index + 1}.png"
                    pix.save(output.as_posix())
                    print(output.as_posix())
                """;
        List<String> paths = runPythonScript(script, pdfPath.toString(), renderDir.toString(), Integer.toString(dpi));
        paths.sort(Comparator.naturalOrder());
        for (String path : paths) {
            if (path == null || path.isBlank()) {
                continue;
            }
            BufferedImage image = ImageIO.read(Path.of(path.trim()).toFile());
            if (image != null) {
                images.add(image);
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
        tesseract.setVariable("user_defined_dpi", String.valueOf(dpi));
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
        BufferedImage dpiImg = ensureDpi(deskewed, dpi);
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
        // DPI is metadata, not pixel data; rendered PDF pages are already scaled for OCR.
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

    private String extractEmbeddedPdfText(Path pdfPath) {
        String script = """
                import fitz
                import sys
                from pathlib import Path

                pdf = Path(sys.argv[1])
                doc = fitz.open(pdf)
                parts = []
                for index in range(doc.page_count):
                    parts.append(doc.load_page(index).get_text("text"))
                print("\\n".join(parts))
                """;
        try {
            return String.join("\n", runPythonScript(script, pdfPath.toString()));
        } catch (IOException ex) {
            log.warn("Embedded PDF text extraction failed for {}", pdfPath, ex);
            return "";
        }
    }

    private List<String> runPythonScript(String script, String... args) throws IOException {
        Path tempScript = Files.createTempFile("invoice-pdf-", ".py");
        try {
            Files.writeString(tempScript, script, StandardCharsets.UTF_8);
            List<String> command = new ArrayList<>();
            command.add("python");
            command.add(tempScript.toString());
            for (String arg : args) {
                command.add(arg);
            }
            Process process = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .start();
            List<String> output = new ArrayList<>();
            try (var reader = process.inputReader(StandardCharsets.UTF_8)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.add(line);
                }
            }
            try {
                int exitCode = process.waitFor();
                if (exitCode != 0) {
                    throw new IOException("Python script failed with exit code " + exitCode + ": " + String.join("\n", output));
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IOException("Python execution interrupted", ex);
            }
            return output;
        } finally {
            deleteQuietly(tempScript);
        }
    }

    private void deleteTreeQuietly(Path path) {
        if (path == null || Files.notExists(path)) {
            return;
        }
        try (var walk = Files.walk(path)) {
            walk.sorted(Comparator.reverseOrder()).forEach(this::deleteQuietly);
        } catch (IOException ex) {
            log.debug("Unable to delete temp directory {}", path, ex);
        }
    }

    private void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException ex) {
            log.debug("Unable to delete temp path {}", path, ex);
        }
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
