package com.docextract.factory;

import com.docextract.model.ExtractionResult;

/**
 * DocumentExtractor – Factory Pattern interface.
 *
 * Every concrete extractor (Aadhaar, PAN, Driving License) implements this
 * interface so that DocumentExtractorFactory can return any of them
 * polymorphically without the caller knowing the concrete type.
 */
public interface DocumentExtractor {

    /**
     * Extract structured fields from cleaned OCR text.
     *
     * @param cleanedText  normalised, uppercase OCR text
     * @return             populated ExtractionResult (never null)
     */
    ExtractionResult extract(String cleanedText);
}
