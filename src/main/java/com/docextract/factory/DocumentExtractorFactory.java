package com.docextract.factory;

import com.docextract.extractor.AadhaarExtractor;
import com.docextract.extractor.DrivingLicenseExtractor;
import com.docextract.extractor.PanExtractor;
import com.docextract.extractor.UnknownDocumentExtractor;
import com.docextract.model.DocumentType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * DocumentExtractorFactory – Factory Pattern implementation.
 *
 * Returns the appropriate DocumentExtractor based on the detected DocumentType.
 *
 * Usage:
 *   DocumentExtractor extractor = factory.getExtractor(DocumentType.AADHAAR);
 *   ExtractionResult  result    = extractor.extract(cleanedText);
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DocumentExtractorFactory {

    private final AadhaarExtractor        aadhaarExtractor;
    private final PanExtractor            panExtractor;
    private final DrivingLicenseExtractor drivingLicenseExtractor;
    private final UnknownDocumentExtractor unknownDocumentExtractor;

    /**
     * Return the correct extractor for the given document type.
     *
     * @param documentType  detected type
     * @return              matching DocumentExtractor (never null)
     */
    public DocumentExtractor getExtractor(DocumentType documentType) {
        if (documentType == null) {
            log.warn("Null document type supplied to factory; using UNKNOWN extractor");
            return unknownDocumentExtractor;
        }

        return switch (documentType) {
            case AADHAAR          -> {
                log.debug("Factory → AadhaarExtractor");
                yield aadhaarExtractor;
            }
            case PAN              -> {
                log.debug("Factory → PanExtractor");
                yield panExtractor;
            }
            case DRIVING_LICENSE  -> {
                log.debug("Factory → DrivingLicenseExtractor");
                yield drivingLicenseExtractor;
            }
            default               -> {
                log.warn("Unknown document type '{}'; using UnknownDocumentExtractor", documentType);
                yield unknownDocumentExtractor;
            }
        };
    }
}
