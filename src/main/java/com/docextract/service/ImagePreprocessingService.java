package com.docextract.service;

import lombok.extern.slf4j.Slf4j;
import org.bytedeco.opencv.opencv_core.*;
import org.bytedeco.opencv.opencv_imgproc.CLAHE;
import org.springframework.stereotype.Service;

import static org.bytedeco.opencv.global.opencv_core.*;
import static org.bytedeco.opencv.global.opencv_imgcodecs.*;
import static org.bytedeco.opencv.global.opencv_imgproc.*;
import static org.bytedeco.opencv.global.opencv_photo.fastNlMeansDenoising;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * ImagePreprocessingService – OpenCV-based image enhancement pipeline.
 *
 * Steps:
 *  1. Decode image bytes → OpenCV Mat
 *  2. Auto-rotate (OrientationCorrectionService)
 *  3. Grayscale
 *  4. Upscale if too small
 *  5. FastNlMeans denoise
 *  6. CLAHE contrast
 *  7. Adaptive threshold
 *  8. Unsharp-mask sharpen
 *  9. Morphological cleanup
 * 10. Write to temp PNG → read bytes → return
 */
@Slf4j
@Service
public class ImagePreprocessingService {

    private final OrientationCorrectionService orientationService;

    public ImagePreprocessingService(OrientationCorrectionService orientationService) {
        this.orientationService = orientationService;
    }

    public byte[] preprocess(byte[] imageBytes) {
        Path tempOut = null;
        try {
            // Step 1: Decode
            Mat raw = new Mat(imageBytes);
            Mat src = imdecode(raw, IMREAD_COLOR);
            raw.close();

            if (src == null || src.empty()) {
                log.warn("imdecode failed; returning original bytes");
                return imageBytes;
            }
            log.debug("Decoded image: {}×{}", src.cols(), src.rows());

            // Step 2: Auto-rotate
            src = orientationService.correctOrientation(src);

            // Step 3: Grayscale
            Mat gray = new Mat();
            cvtColor(src, gray, COLOR_BGR2GRAY);
            src.close();

            // Step 4: Upscale if too small
            gray = ensureMinWidth(gray, 1200);

            // Step 5: Denoise
            Mat denoised = new Mat();
            fastNlMeansDenoising(gray, denoised, 10, 7, 21);
            gray.close();

            // Step 6: CLAHE
            Mat clahe = applyClahe(denoised);
            denoised.close();

            // Step 7: Adaptive threshold
            Mat thresh = new Mat();
            adaptiveThreshold(clahe, thresh, 255,
                    ADAPTIVE_THRESH_GAUSSIAN_C, THRESH_BINARY, 11, 2);
            clahe.close();

            // Step 8: Sharpen
            Mat sharp = sharpen(thresh);
            thresh.close();

            // Step 9: Morph cleanup
            Mat cleaned = morphClean(sharp);
            sharp.close();

            // Step 10: Write to temp file → read bytes (most reliable encode method)
            tempOut = Files.createTempFile("ocr_out_", ".png");
            imwrite(tempOut.toString(), cleaned);
            cleaned.close();

            byte[] result = Files.readAllBytes(tempOut);
            log.debug("Preprocessing complete: {} bytes output", result.length);
            return result.length > 0 ? result : imageBytes;

        } catch (Exception e) {
            log.error("Preprocessing error (returning original): {}", e.getMessage(), e);
            return imageBytes;
        } finally {
            if (tempOut != null) {
                try { Files.deleteIfExists(tempOut); } catch (Exception ignored) {}
            }
        }
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private Mat ensureMinWidth(Mat m, int minW) {
        if (m.cols() >= minW) return m;
        double s  = (double) minW / m.cols();
        Mat out   = new Mat();
        resize(m, out, new Size((int)(m.cols() * s), (int)(m.rows() * s)), 0, 0, INTER_CUBIC);
        log.debug("Upscaled {}×{} → {}×{}", m.cols(), m.rows(), out.cols(), out.rows());
        m.close();
        return out;
    }

    private Mat applyClahe(Mat gray) {
        CLAHE c  = createCLAHE(2.0, new Size(8, 8));
        Mat   dst = new Mat();
        c.apply(gray, dst);
        c.close();
        return dst;
    }

    private Mat sharpen(Mat src) {
        Mat blurred  = new Mat();
        GaussianBlur(src, blurred, new Size(0, 0), 3);
        Mat out = new Mat();
        addWeighted(src, 1.5, blurred, -0.5, 0, out);
        blurred.close();
        return out;
    }

    private Mat morphClean(Mat src) {
        Mat kernel = getStructuringElement(MORPH_RECT, new Size(2, 2));
        Mat dst    = new Mat();
        morphologyEx(src, dst, MORPH_OPEN, kernel);
        kernel.close();
        return dst;
    }
}
