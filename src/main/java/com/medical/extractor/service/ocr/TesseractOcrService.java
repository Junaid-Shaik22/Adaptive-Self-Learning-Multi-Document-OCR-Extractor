package com.medical.extractor.service.ocr;

import com.medical.extractor.config.MedicalOcrConfig;
import com.medical.extractor.model.OcrResult;
import net.sourceforge.tess4j.Tesseract;
import net.sourceforge.tess4j.TesseractException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.awt.image.BufferedImage;

/**
 * Tesseract OCR fallback service for Medical OCR pipeline
 */
@Service
public class TesseractOcrService {
    private static final Logger logger = LoggerFactory.getLogger(TesseractOcrService.class);
    
    private final MedicalOcrConfig config;
    private final Tesseract tesseract;

    public TesseractOcrService(MedicalOcrConfig config,
                               @Value("${tesseract.data.path:}") String tessDataPath,
                               @Value("${tesseract.language:eng}") String language,
                               @Value("${tesseract.psm:6}") int psm) {
        this.config = config;
        this.tesseract = new Tesseract();
        
        if (tessDataPath != null && !tessDataPath.isEmpty()) {
            this.tesseract.setDatapath(tessDataPath);
        }
        
        this.tesseract.setLanguage(language);
        this.tesseract.setPageSegMode(psm);
    }

    /**
     * Extract text from image using Tesseract
     */
    public OcrResult extractText(BufferedImage image) {
        long startTime = System.currentTimeMillis();
        
        if (!config.getTesseract().isEnabled()) {
            return OcrResult.failure("TESSERACT", "Tesseract is disabled in configuration");
        }

        try {
            if (image == null) {
                return OcrResult.failure("TESSERACT", "Image is null");
            }

            logger.debug("Starting Tesseract OCR extraction");
            
            String extractedText = tesseract.doOCR(image);
            long processingTime = System.currentTimeMillis() - startTime;
            
            if (extractedText == null || extractedText.isBlank()) {
                logger.warn("Tesseract returned empty text");
                return OcrResult.failure("TESSERACT", "Tesseract returned empty text");
            }
            
            logger.info("Tesseract extraction successful. Text length: {}, Time: {}ms", 
                    extractedText.length(), processingTime);
            
            return OcrResult.success(extractedText, "TESSERACT", 0.75, processingTime);
            
        } catch (TesseractException ex) {
            logger.error("Tesseract extraction failed: {}", ex.getMessage(), ex);
            return OcrResult.failure("TESSERACT", "Tesseract error: " + ex.getMessage());
        } catch (Exception ex) {
            logger.error("Unexpected error during Tesseract extraction: {}", ex.getMessage(), ex);
            return OcrResult.failure("TESSERACT", "Unexpected error: " + ex.getMessage());
        }
    }
}
