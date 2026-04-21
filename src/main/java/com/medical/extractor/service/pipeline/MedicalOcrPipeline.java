package com.medical.extractor.service.pipeline;

import com.medical.extractor.config.MedicalOcrConfig;
import com.medical.extractor.model.*;
import com.medical.extractor.service.ocr.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.awt.image.BufferedImage;

/**
 * Main orchestrator for Medical OCR hybrid pipeline
 * 
 * Pipeline:
 * Image → PaddleOCR → TextCleaner → Correction → Field Extraction → Validation → JSON Output
 * (With Tesseract fallback if PaddleOCR fails)
 */
@Service
public class MedicalOcrPipeline {
    private static final Logger logger = LoggerFactory.getLogger(MedicalOcrPipeline.class);
    
    private final MedicalOcrConfig config;
    private final PaddleOcrClient paddleOcrClient;
    private final TesseractOcrService tesseractOcrService;
    private final OcrTextCleaner textCleaner;
    private final OcrCorrectionEngine correctionEngine;

    
    public MedicalOcrPipeline(MedicalOcrConfig config,
                             PaddleOcrClient paddleOcrClient,
                             TesseractOcrService tesseractOcrService,
                             OcrTextCleaner textCleaner,
                             OcrCorrectionEngine correctionEngine) {
        this.config = config;
        this.paddleOcrClient = paddleOcrClient;
        this.tesseractOcrService = tesseractOcrService;
        this.textCleaner = textCleaner;
        this.correctionEngine = correctionEngine;
    }

    /**
     * Process image through OCR pipeline and return cleaned text
     */
    public OcrResult processImage(BufferedImage image) {
        if (image == null) {
            logger.error("Image is null");
            return OcrResult.failure("Image is null");
        }

        try {
            // Step 1: OCR extraction (PaddleOCR first, fallback to Tesseract)
            String ocrText = extractTextWithFallback(image);

            if (ocrText == null || ocrText.isBlank()) {
                logger.error("OCR extraction failed - no text returned");
                return OcrResult.failure("OCR extraction failed - no text returned");
            }

            logger.debug("OCR text extracted. Length: {}", ocrText.length());

            // Step 2: Text cleaning
            String cleanedText = textCleaner.cleanText(ocrText);
            logger.debug("Text cleaned. Length: {}", cleanedText.length());

            // Step 3: OCR correction
            String correctedText = correctionEngine.correctText(cleanedText);
            logger.debug("Text corrected");

            return OcrResult.success(correctedText);

        } catch (Exception ex) {
            logger.error("Error in OCR pipeline", ex);
            return OcrResult.failure("Pipeline error: " + ex.getMessage());
        }
    }

    /**
     * Extract text with PaddleOCR fallback to Tesseract
     */
    private String extractTextWithFallback(BufferedImage image) {
        OcrMode mode = OcrMode.fromString(config.getMode());

        logger.info("Starting OCR extraction with mode: {}", mode);

        if (mode == OcrMode.PADDLE || mode == OcrMode.HYBRID) {
            try {
                OcrResult paddleResult = paddleOcrClient.extractText(image);

                if (paddleResult.isSuccess() && paddleResult.getText() != null && !paddleResult.getText().isBlank()) {
                    if (config.getLogging().isEnableOcrTypeLogging()) {
                        logger.info("PaddleOCR successful. Text length: {}, Confidence: {}", 
                                paddleResult.getText().length(), paddleResult.getConfidence());
                    }

                    return paddleResult.getText();
                }

                logger.warn("PaddleOCR failed: {}", paddleResult.getErrorMessage());

            } catch (Exception ex) {
                logger.warn("PaddleOCR call failed: {}", ex.getMessage());
            }

            // If mode is PADDLE only, don't fallback
            if (mode == OcrMode.PADDLE) {
                logger.error("PaddleOCR-only mode used, fallback disabled");
                return null;
            }
        }

        // Fallback to Tesseract
        if (mode == OcrMode.TESSERACT || mode == OcrMode.HYBRID) {

            try {
                OcrResult tesseractResult = tesseractOcrService.extractText(image);

                if (tesseractResult.isSuccess() && tesseractResult.getText() != null && !tesseractResult.getText().isBlank()) {
                    if (config.getLogging().isEnableFallbackLogging()) {
                        logger.warn("Tesseract fallback used. Text length: {}", tesseractResult.getText().length());
                    }

                    return tesseractResult.getText();
                }

                logger.error("Tesseract fallback also failed: {}", tesseractResult.getErrorMessage());

            } catch (Exception ex) {
                logger.error("Tesseract fallback failed: {}", ex.getMessage());
            }
        }

        return null;
    }
}
