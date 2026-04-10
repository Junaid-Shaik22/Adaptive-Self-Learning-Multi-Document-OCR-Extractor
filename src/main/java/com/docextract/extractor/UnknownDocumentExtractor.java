package com.docextract.extractor;

import com.docextract.factory.DocumentExtractor;
import com.docextract.model.DocumentType;
import com.docextract.model.ExtractionResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * UnknownDocumentExtractor – fallback extractor when document type cannot be determined.
 *
 * Returns an ExtractionResult with document_type = UNKNOWN and no field data,
 * allowing callers to handle unrecognized documents gracefully.
 */
@Slf4j
@Component
public class UnknownDocumentExtractor implements DocumentExtractor {

    @Override
    public ExtractionResult extract(String cleanedText) {
        log.warn("UnknownDocumentExtractor invoked – document type could not be determined");

        return ExtractionResult.builder()
                .documentType(DocumentType.UNKNOWN.name())
                .confidence("LOW")
                .validationErrors(List.of(
                        "Document type could not be determined from the uploaded image.",
                        "Ensure the image contains a valid Aadhaar Card, PAN Card, or Driving License.",
                        "Try uploading a higher quality, non-rotated, well-lit image."
                ))
                .build();
    }
}
