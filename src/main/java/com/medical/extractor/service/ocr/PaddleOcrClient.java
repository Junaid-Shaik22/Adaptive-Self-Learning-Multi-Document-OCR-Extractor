package com.medical.extractor.service.ocr;

import com.medical.extractor.config.MedicalOcrConfig;
import com.medical.extractor.model.OcrResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Base64;
import java.util.Map;

/**
 * PaddleOCR client for calling external Python FastAPI service
 */
@Service
public class PaddleOcrClient {
    private static final Logger logger = LoggerFactory.getLogger(PaddleOcrClient.class);
    
    private final MedicalOcrConfig config;
    private final RestTemplate restTemplate;

    
    public PaddleOcrClient(MedicalOcrConfig config, RestTemplate restTemplate) {
        this.config = config;
        this.restTemplate = restTemplate;
    }

    /**
     * Extract text from image using PaddleOCR service
     */
    public OcrResult extractText(BufferedImage image) {
        long startTime = System.currentTimeMillis();
        
        if (!config.getPaddle().isEnabled()) {
            return OcrResult.failure("PADDLE", "PaddleOCR is disabled in configuration");
        }

        try {
            String imageBase64 = encodeImageToBase64(image);
            
            String fullUrl = config.getPaddle().getFullUrl();
            if (fullUrl == null) {
                return OcrResult.failure("PADDLE", "PaddleOCR URL is not configured");
            }
            logger.debug("Calling PaddleOCR service at: {}", fullUrl);
            
            // Call PaddleOCR endpoint
            Map<String, String> payload = Map.of("image", imageBase64);
            
            @SuppressWarnings("unchecked")
            Map<String, Object> response = restTemplate.postForObject(
                    fullUrl,
                    payload,
                    Map.class
            );
            
            if (response == null || !response.containsKey("text")) {
                return OcrResult.failure("PADDLE", "Invalid response format from PaddleOCR service");
            }
            
            String extractedText = response.get("text").toString();
            double confidence = extractConfidenceFromResponse(response);
            long processingTime = System.currentTimeMillis() - startTime;
            
            if (extractedText == null || extractedText.isBlank()) {
                logger.warn("PaddleOCR returned empty text");
                return OcrResult.failure("PADDLE", "PaddleOCR returned empty text");
            }
            
            logger.info("PaddleOCR extraction successful. Text length: {}, Confidence: {}, Time: {}ms", 
                    extractedText.length(), confidence, processingTime);
            
            return OcrResult.success(extractedText, "PADDLE", confidence, processingTime);
            
        } catch (RestClientException ex) {
            logger.error("PaddleOCR service call failed: {}", ex.getMessage(), ex);
            return OcrResult.failure("PADDLE", "PaddleOCR service unavailable: " + ex.getMessage());
        } catch (IOException ex) {
            logger.error("Error encoding image for PaddleOCR: {}", ex.getMessage(), ex);
            return OcrResult.failure("PADDLE", "Image encoding error: " + ex.getMessage());
        } catch (Exception ex) {
            logger.error("Unexpected error during PaddleOCR extraction: {}", ex.getMessage(), ex);
            return OcrResult.failure("PADDLE", "Unexpected error: " + ex.getMessage());
        }
    }

    /**
     * Encode BufferedImage to Base64 string
     */
    private String encodeImageToBase64(BufferedImage image) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(image, "png", baos);
        byte[] imageBytes = baos.toByteArray();
        return Base64.getEncoder().encodeToString(imageBytes);
    }

    /**
     * Extract confidence score from PaddleOCR response
     */
    private double extractConfidenceFromResponse(Map<String, Object> response) {
        try {
            Object confObj = response.get("confidence");
            if (confObj instanceof Number) {
                return ((Number) confObj).doubleValue();
            }
        } catch (Exception ex) {
            logger.debug("Could not extract confidence from response", ex);
        }
        return 0.85; // Default confidence
    }
}
