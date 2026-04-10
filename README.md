# Adaptive Self-Learning Invoice Extraction System

A production-ready Java 17 Spring Boot REST API for extracting invoice data from any PDF or image using advanced OCR, multi-strategy field extraction, and self-learning template logic.

## Features
- Accepts PDF/image uploads via REST API
- Advanced OCR preprocessing (grayscale, threshold, noise removal, deskew, DPI, border removal)
- OCR text cleaning and normalization
- Line indexing and document zoning
- Multi-strategy, zone-based field extraction with validation
- Self-learning template system (no code generation)
- Confidence scoring and error handling
- Logging of all key steps
- Modular, extensible, and production-ready

## Technologies
- Java 17, Spring Boot, Maven
- Tess4J (Tesseract OCR), PyMuPDF-backed PDF text/page extraction
- Jackson, Lombok, Commons Codec

## API
`POST /api/invoice/extract` (multipart/form-data, file=PDF/image)

## Output
Structured JSON with invoice fields, confidence score, templateId, and status.
