package com.invoice.extractor;

import org.bytedeco.javacpp.Loader;
import org.bytedeco.opencv.opencv_java;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = {"com.invoice.extractor", "com.docextract", "com.medical.extractor"})
public class AdaptiveInvoiceExtractorApplication {
    static {
        Loader.load(opencv_java.class);
    }

    public static void main(String[] args) {
        SpringApplication.run(AdaptiveInvoiceExtractorApplication.class, args);
    }
}
