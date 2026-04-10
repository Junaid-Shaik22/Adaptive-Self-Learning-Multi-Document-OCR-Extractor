package com.docextract.detector;

import com.docextract.model.DocumentType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/**
 * DocumentTypeDetector – classifies OCR text into a DocumentType.
 *
 * Strategy: keyword scoring (multi-signal voting) + pattern matching.
 * Returns the type with the highest score (minimum threshold required).
 */
@Slf4j
@Component
public class DocumentTypeDetector {

    // ─── Aadhaar keywords / patterns ─────────────────────────────────────────
    private static final String[] AADHAAR_KEYWORDS = {
        "UNIQUE IDENTIFICATION AUTHORITY OF INDIA",
        "UIDAI",
        "AADHAAR",
        "AADHAR",
        "ADHAR",
        "GOVERNMENT OF INDIA",
        "ENROLLMENT NO",
        "ENROLMENT NO",
        "VID"
    };
    // 12-digit Aadhaar number (with optional spaces every 4 digits)
    private static final Pattern AADHAAR_NUMBER_PATTERN =
            Pattern.compile("\\b[2-9]\\d{3}\\s?\\d{4}\\s?\\d{4}\\b");

    // ─── PAN keywords / patterns ──────────────────────────────────────────────
    private static final String[] PAN_KEYWORDS = {
        "INCOME TAX DEPARTMENT",
        "PERMANENT ACCOUNT NUMBER",
        "GOVT OF INDIA",
        "INCOME TAX",
        "PAN"
    };
    // PAN pattern: 5 letters, 4 digits, 1 letter
    private static final Pattern PAN_NUMBER_PATTERN =
            Pattern.compile("\\b[A-Z]{5}[0-9]{4}[A-Z]\\b");

    // ─── Driving License keywords / patterns ─────────────────────────────────
    private static final String[] DL_KEYWORDS = {
        "DRIVING LICENCE",
        "DRIVING LICENSE",
        "DL NO",
        "DL NUMBER",
        "VALID FROM",
        "VALID TILL",
        "VALID TO",
        "TRANSPORT DEPARTMENT",
        "MOTOR VEHICLES",
        "LICENSE NO",
        "LICENCE NO",
        "COV",            // Class of Vehicle
        "BLOOD GROUP"
    };
    // DL pattern: state code + RTO + year + number
    private static final Pattern DL_NUMBER_PATTERN =
            Pattern.compile("\\b[A-Z]{2}[-\\s]?\\d{2}[-\\s]?\\d{4}[-\\s]?\\d{7}\\b");

    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Detect document type from cleaned OCR text.
     *
     * @param text  cleaned, uppercase OCR text
     * @return      detected DocumentType (never null; falls back to UNKNOWN)
     */
    public DocumentType detect(String text) {
        if (text == null || text.isBlank()) {
            log.warn("Empty text provided for document type detection");
            return DocumentType.UNKNOWN;
        }

        int aadhaarScore = 0;
        int panScore     = 0;
        int dlScore      = 0;

        // ── Keyword scoring ───────────────────────────────────────────────
        for (String kw : AADHAAR_KEYWORDS) {
            if (text.contains(kw)) {
                aadhaarScore += (kw.length() > 10) ? 3 : 1;  // longer match = stronger signal
            }
        }
        for (String kw : PAN_KEYWORDS) {
            if (text.contains(kw)) {
                panScore += (kw.length() > 10) ? 3 : 1;
            }
        }
        for (String kw : DL_KEYWORDS) {
            if (text.contains(kw)) {
                dlScore += (kw.length() > 10) ? 3 : 1;
            }
        }

        // ── Pattern matching (strong bonus) ──────────────────────────────
        if (AADHAAR_NUMBER_PATTERN.matcher(text).find()) aadhaarScore += 5;
        if (PAN_NUMBER_PATTERN.matcher(text).find())     panScore     += 5;
        if (DL_NUMBER_PATTERN.matcher(text).find())      dlScore      += 5;

        log.debug("Document type scores → AADHAAR={} PAN={} DL={}", aadhaarScore, panScore, dlScore);

        // ── Determine winner ──────────────────────────────────────────────
        int maxScore = Math.max(aadhaarScore, Math.max(panScore, dlScore));

        if (maxScore == 0) {
            log.warn("No document type signals found. Returning UNKNOWN.");
            return DocumentType.UNKNOWN;
        }

        if (aadhaarScore == maxScore) {
            log.info("Detected: AADHAAR (score={})", aadhaarScore);
            return DocumentType.AADHAAR;
        } else if (panScore == maxScore) {
            log.info("Detected: PAN (score={})", panScore);
            return DocumentType.PAN;
        } else {
            log.info("Detected: DRIVING_LICENSE (score={})", dlScore);
            return DocumentType.DRIVING_LICENSE;
        }
    }
}
