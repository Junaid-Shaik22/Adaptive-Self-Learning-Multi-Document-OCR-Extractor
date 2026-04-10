package com.invoice.extractor.config;

import io.swagger.v3.oas.models.info.Info;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class UnifiedOpenApiInfoConfig {

    @Bean
    public OpenApiCustomizer unifiedOpenApiInfoCustomizer() {
        return openApi -> {
            Info info = openApi.getInfo();
            if (info == null) {
                info = new Info();
                openApi.setInfo(info);
            }

            info.setDescription("""
                    ## Unified OCR Extraction System

                    Extracts structured data from both invoice documents and Indian identity documents.

                    ### Invoice OCR
                    - Invoice Number
                    - Invoice Date
                    - Vendor Name and GSTIN
                    - Buyer Name and GSTIN
                    - Subtotal, Tax Amount, Total Amount
                    - Line Items (when detected)

                    ### Indian ID Document OCR
                    - **Aadhaar Card** - Name, Aadhaar Number, DOB, Gender, Address
                    - **PAN Card** - Name, Father Name, DOB, PAN Number
                    - **Driving License** - Name, DL Number, DOB, Valid From/To, Address

                    ### Available APIs
                    - `/api/invoice/extract` - Invoice OCR extraction
                    - `/extract-document` - Indian ID document OCR extraction

                    ### Supported Input Formats
                    - JPEG, PNG, TIFF, BMP (single image)
                    - PDF (single or multi-page; each page processed independently)

                    ### Handles Real-World Conditions
                    - Rotated images, low quality, blurry, shadows
                    - Cropped / partial documents
                    - Mobile camera photos

                    ### Pipeline
                    `Upload -> Preprocess / OCR -> Clean -> Detect / Extract -> Validate -> JSON`
                    """);
        };
    }
}
