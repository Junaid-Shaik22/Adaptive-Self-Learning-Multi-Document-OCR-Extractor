package com.docextract.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * SwaggerConfig – exposes OpenAPI 3 specification and Swagger UI.
 *
 * Access Swagger UI at: http://localhost:${server.port}/swagger-ui/index.html
 * Access raw OpenAPI spec at: http://localhost:${server.port}/api-docs
 */
@Configuration
public class SwaggerConfig {

    @Value("${server.port:8080}")
    private String serverPort;

    @Bean
    public OpenAPI documentIntelligenceOpenAPI() {
        Server localServer = new Server();
        localServer.setUrl("http://localhost:" + serverPort);
        localServer.setDescription("Local Development Server");

        Contact contact = new Contact();
        contact.setName("Document Intelligence System");
        contact.setEmail("support@docextract.com");

        License license = new License()
                .name("Apache 2.0")
                .url("https://www.apache.org/licenses/LICENSE-2.0");

        Info info = new Info()
                .title("Document Intelligence System API")
                .version("1.0.0")
                .description("""
                        ## Indian ID Document OCR Extraction System
                        
                        Extracts structured data from Indian identity documents:
                        - **Aadhaar Card** – Name, Aadhaar Number, DOB, Gender, Address
                        - **PAN Card** – Name, Father Name, DOB, PAN Number
                        - **Driving License** – Name, DL Number, DOB, Valid From/To, Address
                        
                        ### Supported Input Formats
                        - JPEG, PNG, TIFF, BMP (single image)
                        - PDF (single or multi-page; each page processed independently)
                        
                        ### Handles Real-World Conditions
                        - Rotated images, low quality, blurry, shadows
                        - Cropped / partial documents
                        - Mobile camera photos
                        
                        ### Pipeline
                        `Upload → Preprocess (OpenCV) → OCR (Tesseract) → Clean → Detect → Extract → Validate → JSON`
                        """)
                .contact(contact)
                .license(license);

        return new OpenAPI()
                .info(info)
                .servers(List.of(localServer));
    }
}
