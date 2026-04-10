package com.docextract.service;

import com.docextract.model.ExtractionResult;
import org.springframework.web.multipart.MultipartFile;

/**
 * Top-level orchestration service.
 * Coordinates the entire pipeline:
 *   PDF/Image → Preprocessing → OCR → Cleaning → Detection → Extraction → Validation → Result
 */
public interface DocumentProcessingService {
    ExtractionResult process(MultipartFile file, boolean isPdf) throws Exception;
}
