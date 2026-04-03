# Invoice OCR Extraction System

## Overview

This project is an intelligent invoice data extraction system that automatically reads invoice images or PDFs and converts them into structured JSON data.

The system uses Tesseract OCR to extract text from invoices and then applies zone-based extraction, regex patterns, keyword detection, and template learning to accurately identify important fields like invoice number, date, GSTIN, vendor details, buyer details, and total amounts.

The goal of this project is to build a generic invoice extraction system that works across multiple invoice formats instead of being limited to a single template.

---

## Features

* OCR using Tesseract
* Automatic invoice number extraction
* Invoice date extraction
* Vendor and Buyer details extraction
* GSTIN extraction
* Subtotal, Tax, and Total extraction
* Line item table extraction
* Template learning for repeated invoice formats
* Confidence score for extraction accuracy
* Rule-based + template-based extraction
* Low-confidence fallback extraction (AI-ready architecture)
* REST API using Spring Boot
* JSON output

---

## Technology Stack

* Java
* Spring Boot
* Maven
* Tesseract OCR
* Regex & text processing
* Template Learning (JSON-based storage)
* REST APIs
* JUnit Testing

---

## Extraction Flow

OCR → Text Cleaning → Zone Detection → Template Extraction → Keyword Extraction → Regex Extraction → Validation → Confidence Score → JSON Output

---

## Sample Output JSON

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

## How to Run the Project

```bash
mvn clean install
mvn spring-boot:run
```

---

## API Endpoint

```
POST /extract-invoice
```

Upload invoice image or PDF and the system will return extracted data in JSON format.

---

## Future Improvements

* AI-based fallback extraction for low-confidence invoices
* Support for more invoice formats
* Improved table and line item detection
* Export extracted data to Excel
* Web interface for uploading invoices

---

## Authors

* Junaid
