# 🚀 Multi-Document Intelligent OCR Extraction Engine

A production-ready **Java 17 Spring Boot REST API** for extracting structured data from multiple document types such as **Invoices, Aadhaar, PAN, Driving License, and Medical Certificates** using advanced OCR, intelligent extraction, and validation logic.

---

## 🌟 Overview

This project is designed to handle **real-world documents with different formats**, not just fixed templates. It uses OCR combined with smart extraction techniques to convert unstructured documents into clean JSON output.

---

## 📄 Supported Document Types

- 🧾 Invoice Extraction (Adaptive & Template-based)
- 🪪 Aadhaar OCR Extraction
- 🆔 PAN Card Extraction
- 🚗 Driving License Extraction
- 🏥 Medical Certificate Extraction (NEW)

The system automatically detects the document type and applies the correct extraction logic.

---

## 🔍 OCR & Preprocessing

- OCR using **Tesseract (Tess4J)**
- Image preprocessing:
  - Grayscale conversion
  - Adaptive thresholding
  - Noise removal
  - Deskew correction
  - DPI enhancement
  - Border removal

---

## 🧠 Intelligent Extraction Engine

- Zone-based processing (Top / Middle / Bottom)
- Multi-strategy extraction:
  - Keyword-based
  - Regex-based
  - Positional extraction
- Field validation for improved accuracy

---

## 🧾 Invoice Extraction Features

- Works with **any invoice format**
- Self-learning template system
- Extracts:
  - Invoice Number & Date
  - Vendor & Buyer details
  - GSTIN (validated)
  - Subtotal, Tax, Total
  - Line Items (table extraction)

---

## 🪪 Aadhaar Extraction

- Name
- Masked Aadhaar Number
- Date of Birth
- Gender
- Address & Pincode

---

## 🆔 PAN Card Extraction

- Name
- PAN Number
- Date of Birth

---

## 🚗 Driving License Extraction

- Name
- License Number
- Date of Birth
- Validity

---

## 🏥 Medical Certificate Extraction (NEW)

Designed to handle both **printed and handwritten medical certificates**.

### Extracts:
- Organization Name
- Applicant / Patient Name
- From Date
- To Date
- Total Absent Days

### Features:
- Handles mixed handwritten + printed text
- Context-based extraction (e.g., "rest from ___ to ___")
- Date correction and validation
- Intelligent fallback logic

---

## 🏗️ Architecture

- Factory Design Pattern for extractor selection
- Modular extractor system
- Independent logic per document type
- Shared OCR pipeline

---

## 🎯 Accuracy & Reliability

- Confidence scoring system
- Field validation rules
- Error handling and fallback mechanisms
- Detailed logging

---

## 🌐 API Endpoints

### Invoice OCR
POST /api/invoice/extract

### Aadhaar OCR
POST /api/aadhaar/extract

### PAN OCR
POST /api/pan/extract

### Driving License OCR
POST /api/license/extract

### Medical Certificate OCR
POST /api/medical/extract

---

## ⚙️ Tech Stack

- Java 17
- Spring Boot
- Maven
- Tess4J (Tesseract OCR)
- Apache PDFBox
- OpenCV
- Regex & Text Processing
- JSON-based Template Learning
- Swagger (OpenAPI)
- JUnit Testing

---

## 🔄 Workflow

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

---

## 🚀 How to Run

```bash
mvn clean install
mvn spring-boot:run
