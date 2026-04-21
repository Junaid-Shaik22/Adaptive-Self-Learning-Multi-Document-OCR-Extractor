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
  "buyerName": "Department of Atomic Energy- KOTA, Directorate Of Purchase And Stores",
  "buyerGstin": "08AAAGN1030Q1Z8",

  "poNumber": "NOT_MENTIONED",
  "poDate": "NOT_MENTIONED",
  "orderReference": "NOT_MENTIONED",
  "deliveryNote": "NOT_MENTIONED",

  "dispatchThrough": "By Aman Roadlines Kota",
  "transporterName": "Aman Roadlines",
  "transportDetails": "NFC KOTA PLANT SITE, RAWATBHATTA",
  "vehicleNumber": "NOT_MENTIONED",
  "destination": "RAWATBHATTA",
  "placeOfSupply": "Rajasthan",

  "paymentTerms": "NOT_MENTIONED",

  "bankName": "State Bank of India",
  "accountNumber": "56007241003",
  "ifscCode": "SBIN0063762",
  "branch": "NOT_MENTIONED",

  "irn": "NOT_MENTIONED",
  "ackNumber": "NOT_MENTIONED",
  "ewayBill": "NOT_MENTIONED",

  "vendorPhone": "9619377072, 9825083030",
  "vendorEmail": "NOT_MENTIONED",
  "vendorWebsite": "NOT_MENTIONED",

  "vendorAddress": "S. No-150, Plot No-3A, Sihor Ghangali Rd",
  "buyerAddress": "Department of Atomic Energy- KOTA, Directorate Of Purchase And Stores",

  "vendorPAN": "AAEFR7351M",
  "vendorCIN": "NOT_MENTIONED",
  "msmeNumber": "NOT_MENTIONED",

  "state": "Rajasthan",
  "stateCode": "08",
  "pincode": "364240",

  "subTotal": "260680.51",
  "taxableValue": "260680.51",

  "cgst": "NOT_MENTIONED",
  "sgst": "NOT_MENTIONED",
  "igst": "NOT_MENTIONED",

  "taxAmount": "46922.49",
  "roundOff": "NOT_MENTIONED",

  "totalAmount": "307603.00",
  "currency": "INR",

  "lineItems": [],

  "confidenceScore": 0.92
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
