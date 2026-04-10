# 📄 Multi-Document OCR Extraction System

## 🚀 Overview

This project is an intelligent **Multi-Document OCR Extraction System** built using **Java Spring Boot**.
It automatically extracts structured data from different types of documents such as:

* 🧾 Invoices
* 🪪 Aadhaar Cards
* 🆔 PAN Cards
* 🚗 Driving Licenses

The system uses **OCR + Rule-based Extraction + Template Learning + Factory Design Pattern** to accurately process documents of varying formats.

Unlike traditional systems, this solution is **generic, scalable, and self-improving**.

---

## 🧠 Key Features

### 🔍 OCR & Preprocessing

* Tesseract OCR integration
* Image preprocessing (grayscale, thresholding, noise removal)
* Text cleaning and normalization

### 📊 Invoice Extraction

* Invoice Number & Date extraction
* Vendor & Buyer details
* GSTIN extraction (validated)
* Subtotal, Tax, and Total extraction
* Line item detection
* Template learning for repeated formats
* Confidence scoring

### 🪪 Aadhaar Extraction

* Name extraction
* Aadhaar number (masked)
* Date of Birth
* Gender detection
* Address extraction
* Pincode detection

### 🧩 Multi-Document Support

* Automatic document type detection
* Supports:

  * Invoice
  * Aadhaar
  * PAN Card
  * Driving License
* Easily extendable to new document types

### 🏗️ Scalable Architecture

* Factory Design Pattern for extractor selection
* Modular extractor system
* Independent logic for each document type
* Shared OCR infrastructure

### ⚡ API Features

* REST API using Spring Boot
* Swagger UI for testing
* JSON-based output
* High-performance processing pipeline

---

## 🏗️ System Architecture

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
 ├── InvoiceExtractor
 ├── AadhaarExtractor
 ├── PanExtractor
 ├── DrivingLicenseExtractor
 └── UnknownExtractor
        ↓
Validation + Confidence Score
        ↓
Structured JSON Output
```

---

## 🔄 Extraction Flow

```text
OCR → Text Cleaning → Zone Detection → Template Extraction → 
Keyword Extraction → Regex Extraction → Validation → 
Confidence Score → JSON Output
```

---

## 🧪 Sample Output (Invoice)

```json
{
  "invoiceNumber": "B584",
  "invoiceDate": "20-Jan-24",
  "vendorName": "Ranco Industries",
  "vendorGstin": "24AAEFR7351M1ZW",
  "buyerName": "NFC KOTA",
  "buyerGstin": "08AAAGN1030Q1Z8",
  "subTotal": "260680.51",
  "taxAmount": "46922.49",
  "totalAmount": "307603.00",
  "currency": "INR",
  "lineItems": [],
  "confidenceScore": 0.85
}
```

---

## ⚙️ Technology Stack

* Java
* Spring Boot
* Maven
* Tesseract OCR
* Apache PDFBox
* OpenCV (Image Processing)
* Regex & Text Processing
* JSON-based Template Learning
* Swagger (API Testing)
* JUnit Testing

---

## ▶️ How to Run

```bash
mvn clean install
mvn spring-boot:run
```

---

## 🌐 API Endpoints

### 📌 Auto Document Detection (Recommended)

```
POST /api/document/extract
```

### 🧾 Invoice OCR

```
POST /api/invoice/extract
```

### 🪪 Aadhaar OCR

```
POST /api/aadhaar/extract
```

Upload a document (PDF/Image) → Get structured JSON output.

---

## 📊 Confidence Scoring

The system assigns a confidence score based on extracted fields:

* Invoice Number → 0.15
* Date → 0.15
* GSTIN → 0.20
* Total → 0.20
* Buyer → 0.10
* Vendor → 0.10
* Line Items → 0.10

---

## 🔮 Future Improvements

* AI/ML-based fallback extraction
* Table detection using deep learning
* Multi-language OCR support
* Signature & stamp detection
* Export to Excel / Database
* Web UI for document upload
* Parallel OCR processing for large PDFs

---

## 🎯 Project Highlights

* Works across **multiple document formats**
* **Self-learning template system**
* **Factory-based modular architecture**
* **Production-ready REST API**
* Designed for:

  * Government automation
  * Invoice processing systems
  * KYC verification systems
  * Enterprise document digitization

---

## 👨‍💻 Author

**Junaid Shaik**

---

## ⭐ Version

**v2.0 — Multi-Document OCR System**
