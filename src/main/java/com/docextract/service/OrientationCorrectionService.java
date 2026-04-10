package com.docextract.service;

import lombok.extern.slf4j.Slf4j;
import org.bytedeco.opencv.opencv_core.*;
import org.springframework.stereotype.Service;

import static org.bytedeco.opencv.global.opencv_core.*;
import static org.bytedeco.opencv.global.opencv_imgproc.*;

/**
 * OrientationCorrectionService – detects and corrects image skew / rotation.
 *
 * Algorithm:
 *  1. Grayscale + Otsu threshold (inverted so text = white)
 *  2. findNonZero  → all foreground pixel coordinates
 *  3. minAreaRect  → minimum bounding rectangle of text blob
 *  4. Derive skew angle from rectangle
 *  5. Rotate with warpAffine (BORDER_REPLICATE keeps edges clean)
 *
 * Only corrects angles in the range (-45°, +45°) to avoid 90°/180° flips
 * on documents that genuinely have portrait/landscape differences.
 */
@Slf4j
@Service
public class OrientationCorrectionService {

    private static final double MIN_CORRECTION_DEG = 0.5;
    private static final double MAX_CORRECTION_DEG = 45.0;

    /**
     * Detect and correct image orientation.
     *
     * @param src  BGR or grayscale Mat (will not be closed by this method)
     * @return     corrected Mat (caller must close); returns same Mat on failure
     */
    public Mat correctOrientation(Mat src) {
        if (src == null || src.empty()) return src;

        try {
            double angle = detectSkewAngle(src);
            log.debug("Detected skew angle: {:.2f}°", angle);

            if (Math.abs(angle) < MIN_CORRECTION_DEG) {
                log.debug("Skew negligible – skipping rotation");
                return src;
            }
            if (Math.abs(angle) > MAX_CORRECTION_DEG) {
                log.debug("Skew angle too large ({:.2f}°) – skipping to avoid flip", angle);
                return src;
            }

            return rotate(src, angle);

        } catch (Exception e) {
            log.warn("Orientation correction failed: {} – using original", e.getMessage());
            return src;
        }
    }

    // ─── Private helpers ──────────────────────────────────────────────────────

    /**
     * Detect skew angle using minAreaRect on foreground pixel coordinates.
     * Works for small skews (-45° to +45°).
     */
    private double detectSkewAngle(Mat src) {
        // Convert to grayscale if color
        Mat gray = new Mat();
        if (src.channels() == 3 || src.channels() == 4) {
            cvtColor(src, gray, COLOR_BGR2GRAY);
        } else {
            gray = src.clone();
        }

        // Otsu threshold (inverted: text becomes white)
        Mat thresh = new Mat();
        threshold(gray, thresh, 0, 255, THRESH_BINARY_INV | THRESH_OTSU);
        gray.close();

        // Collect all non-zero pixel locations (text pixels)
        Mat points = new Mat();
        findNonZero(thresh, points);
        thresh.close();

        if (points.empty() || points.rows() < 20) {
            log.debug("Not enough foreground pixels for skew detection");
            points.close();
            return 0.0;
        }

        // minAreaRect gives the smallest rotated rectangle enclosing all text
        RotatedRect rect  = minAreaRect(points);
        points.close();

        double angle = rect.angle();

        // OpenCV angle convention: [-90, 0)
        // If width < height, angle is already the skew; otherwise subtract 90
        if (rect.size().width() < rect.size().height()) {
            angle += 90.0;
        }

        // Normalise to [-45, 45]
        if (angle > 45)  angle -= 90;
        if (angle < -45) angle += 90;

        return angle;
    }

    /**
     * Rotate the image by the given angle around its centre.
     */
    private Mat rotate(Mat src, double angle) {
        Point2f centre = new Point2f(src.cols() / 2.0f, src.rows() / 2.0f);
        Mat rotMat     = getRotationMatrix2D(centre, angle, 1.0);
        Mat rotated    = new Mat();

        warpAffine(src, rotated, rotMat,
                   new Size(src.cols(), src.rows()),
                   INTER_CUBIC,
                   BORDER_REPLICATE,
                   new Scalar());

        rotMat.close();
        src.close();

        log.info("Image rotated by {:.2f}° to correct skew", angle);
        return rotated;
    }
}
