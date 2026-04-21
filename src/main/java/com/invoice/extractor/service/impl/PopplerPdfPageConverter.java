package com.invoice.extractor.service.impl;

import com.invoice.extractor.service.ConvertedPdfDocument;
import com.invoice.extractor.service.PdfPageConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class PopplerPdfPageConverter implements PdfPageConverter {
    private static final Pattern OUTPUT_PAGE_PATTERN = Pattern.compile("output-(\\d+)\\.png", Pattern.CASE_INSENSITIVE);

    @Value("${invoice.pdf.poppler.command:pdftoppm}")
    private String popplerCommand = "pdftoppm";

    @Value("${invoice.pdf.render.dpi:300}")
    private int renderDpi = 300;

    public PopplerPdfPageConverter() {
    }

    PopplerPdfPageConverter(String popplerCommand, int renderDpi, boolean ignored) {
        this.popplerCommand = popplerCommand == null || popplerCommand.isBlank() ? "pdftoppm" : popplerCommand;
        this.renderDpi = renderDpi <= 0 ? 300 : renderDpi;
    }

    @Override
    public ConvertedPdfDocument convert(MultipartFile pdfFile) throws IOException {
        if (pdfFile == null || pdfFile.isEmpty()) {
            throw new IOException("Uploaded PDF is empty");
        }

        Path workingDirectory = Files.createTempDirectory("invoice-poppler-");
        Path inputPdf = workingDirectory.resolve("input.pdf");
        Path outputPrefix = workingDirectory.resolve("output");

        try {
            Files.copy(pdfFile.getInputStream(), inputPdf, StandardCopyOption.REPLACE_EXISTING);
            List<String> command = List.of(
                    popplerCommand,
                    "-png",
                    "-r",
                    Integer.toString(renderDpi),
                    inputPdf.toString(),
                    outputPrefix.toString()
            );

            Process process;
            try {
                process = new ProcessBuilder(command)
                        .directory(workingDirectory.toFile())
                        .redirectErrorStream(true)
                        .start();
            } catch (IOException ex) {
                throw new IOException(popplerStartupMessage(ex), ex);
            }

            List<String> output = new ArrayList<>();
            try (var reader = process.inputReader(StandardCharsets.UTF_8)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.add(line);
                }
            }

            int exitCode;
            try {
                exitCode = process.waitFor();
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IOException("pdftoppm execution interrupted", ex);
            }

            if (exitCode != 0) {
                throw new IOException("pdftoppm failed with exit code " + exitCode + ": " + String.join(System.lineSeparator(), output));
            }

            List<Path> generatedImages;
            try (var stream = Files.list(workingDirectory)) {
                generatedImages = stream
                        .filter(Files::isRegularFile)
                        .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).matches("output-\\d+\\.png"))
                        .sorted(Comparator.comparingInt(this::pageNumber))
                        .toList();
            }

            if (generatedImages.isEmpty()) {
                throw new IOException("pdftoppm produced no page images");
            }

            return new ConvertedPdfDocument(workingDirectory, generatedImages);
        } catch (IOException ex) {
            new ConvertedPdfDocument(workingDirectory, List.of()).close();
            throw ex;
        }
    }

    private String popplerStartupMessage(IOException ex) {
        String message = ex.getMessage() == null ? "" : ex.getMessage();
        String lower = message.toLowerCase(Locale.ROOT);
        if (lower.contains("createprocess error=2")
                || lower.contains("cannot find the file")
                || lower.contains("cannot run program")
                || lower.contains("error=2")) {
            return "Poppler pdftoppm command was not found. Install Poppler and set 'invoice.pdf.poppler.command' to the full pdftoppm executable path.";
        }
        return "Unable to start Poppler pdftoppm: " + message;
    }

    private int pageNumber(Path path) {
        Matcher matcher = OUTPUT_PAGE_PATTERN.matcher(path.getFileName().toString());
        if (matcher.matches()) {
            return Integer.parseInt(matcher.group(1));
        }
        return Integer.MAX_VALUE;
    }
}
