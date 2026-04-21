### Multi-Document Intelligent OCR Extraction Engine

A production-ready **Java 17 Spring Boot REST API** for extracting structured data from multiple document types such as **Invoices, Aadhaar, PAN, and Driving License** using advanced OCR, zone-based extraction, and self-learning template logic.

---

##  Features

###  Multi-Document Support

* Supports:

  * 🧾 Invoice Extraction (Adaptive & Template-based)
  * 🪪 Aadhaar OCR Extraction
  * 🆔 PAN Card Extraction
  * 🚗 Driving License Extraction
* Automatic document type detection using intelligent classification

###  OCR & Preprocessing

* Advanced OCR using Tesseract (Tess4J)
* Image preprocessing:

  * Grayscale conversion
  * Adaptive thresholding
  * Noise removal
  * Deskew correction
  * DPI enhancement
  * Border removal

###  Intelligent Extraction Engine

* Zone-based document understanding (Top / Middle / Bottom)
* Multi-strategy field extraction:

  * Keyword-based
  * Regex-based
  * Positional extraction
* Field validation logic for high accuracy

###  Invoice-Specific Intelligence

* Self-learning template system (no code generation)
* Template reuse for repeated invoice formats
* Extraction of:

  * Invoice Number & Date
  * Vendor & Buyer details
  * GSTIN (validated)
  * Subtotal, Tax, Total
  * Line items

###  Aadhaar-Specific Extraction

* Name extraction
* Aadhaar number (masked)
* Date of Birth
* Gender detection
* Address & Pincode extraction

###  Scalable Architecture

* Factory Design Pattern for extractor selection
* Modular extractor system
* Independent logic per document type
* Shared OCR pipeline

###  Accuracy & Reliability

* Confidence scoring system
* Validation rules for extracted data
* Error handling and fallback mechanisms
* Detailed logging of processing pipeline

###  API Features

* REST API using Spring Boot
* Swagger UI for testing
* JSON structured output
* Supports PDF and image uploads

---

## Technologies

* Java 17
* Spring Boot
* Maven
* Tess4J (Tesseract OCR)
* Apache PDFBox
* OpenCV (Image Processing)
* Regex & Text Processing
* JSON-based Template Learning
* Swagger (OpenAPI)
* JUnit Testing

---

## Core Concept

```text
Upload Document
        ↓
OCR + Preprocessing
        ↓
Text Cleaning
        ↓
Document Type Detection
        ↓
Extractor Factory
        ↓
Specific Extractor Execution
        ↓
Validation + Confidence Score
        ↓
Structured JSON Output
```

---

## Highlights

* Works with **any invoice format**
* Supports **multiple document types**
* **Self-learning template system**
* **Factory-based scalable design**
* No database required
* Production-ready REST API
