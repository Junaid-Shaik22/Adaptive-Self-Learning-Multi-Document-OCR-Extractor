package com.invoice.extractor.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.invoice.extractor.model.InvoiceData;
import com.invoice.extractor.template.JsonTemplateRepository;
import org.springframework.mock.web.MockMultipartFile;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class Invoices2AuditTest {
    @Test
    void runAuditTest() throws Exception {
        String dpiStr = System.getProperty("audit.dpi", "300");
        int dpi = Integer.parseInt(dpiStr);
        boolean resume = Boolean.parseBoolean(System.getProperty("audit.resume", "true"));
        runAudit(dpi, resume);
    }
    private static final List<Path> PDF_CANDIDATES = List.of(
            Path.of("C:\\Users\\S.Junaid\\Downloads\\invoices2\\invoices2.pdf"),
            Path.of("C:\\Users\\S.Junaid\\OneDrive\\Documents\\Dokumen\\invoices2\\invoices2.pdf")
    );
    private static final Path AUDIT_DIR = Path.of("target", "audit");
    private static final Path RENDER_DIR = AUDIT_DIR.resolve("rendered");
    private static final Path TEMPLATES_PATH = AUDIT_DIR.resolve("audit-templates.json");
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final long PAGE_TIMEOUT_MINUTES = Long.getLong("audit.pageTimeoutMinutes", 3L);

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && "page".equalsIgnoreCase(args[0])) {
            runSinglePage(Integer.parseInt(args[1]), Integer.parseInt(args[2]), args.length > 3 ? Path.of(args[3]) : resolvePdfPath());
            return;
        }
        int dpi = args.length > 0 ? Integer.parseInt(args[0]) : 150;
        boolean resume = args.length > 1 ? Boolean.parseBoolean(args[1]) : true;
        runAudit(dpi, resume);
    }

    static void runAudit(int dpi, boolean resume) throws Exception {
        Path pdfPath = resolvePdfPath();
        Files.createDirectories(AUDIT_DIR);
        Files.createDirectories(RENDER_DIR);
        if (!resume) {
            resetTemplatesFile();
        }
        if (!Files.exists(TEMPLATES_PATH)) {
            Files.writeString(TEMPLATES_PATH, "[]", StandardCharsets.UTF_8);
        }

        int totalPages = getPageCount(pdfPath);
        int processed = 0;
        int skipped = 0;
        int failed = 0;
        List<Integer> skippedPages = new ArrayList<>();
        List<Integer> failedPages = new ArrayList<>();

        for (int page = 1; page <= totalPages; page++) {
            Path output = AUDIT_DIR.resolve("page_" + page + ".json");
            if (resume && shouldSkipPage(output)) {
                skipped++;
                skippedPages.add(page);
                System.out.println("Skipping page " + page + " because checkpoint exists");
                continue;
            }

            System.out.println("Processing page " + page + " of " + totalPages);
            try {
                PageOutcome outcome = runPageWithTimeout(pdfPath, page, dpi);
                if (outcome == PageOutcome.PROCESSED) {
                    processed++;
                } else if (outcome == PageOutcome.SKIPPED) {
                    skipped++;
                    skippedPages.add(page);
                    System.out.println("Skipped page " + page + " after timeout");
                } else {
                    failed++;
                    failedPages.add(page);
                    System.out.println("Failed page " + page + "; see audit log");
                }
            } catch (Exception ex) {
                failed++;
                failedPages.add(page);
                System.out.println("Failed page " + page + " with " + ex.getClass().getSimpleName());
                Map<String, Object> error = Map.of(
                        "page", page,
                        "dpi", dpi,
                        "error", ex.getClass().getSimpleName(),
                        "message", ex.getMessage()
                );
                Files.writeString(output, OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(error), StandardCharsets.UTF_8);
            }
        }

        System.out.println("Summary:");
        System.out.println("total pages: " + totalPages);
        System.out.println("processed: " + processed);
        System.out.println("skipped: " + skipped);
        System.out.println("failed: " + failed);
        System.out.println("skipped pages: " + skippedPages);
        System.out.println("failed pages: " + failedPages);
    }

    private static void runSinglePage(int page, int dpi, Path pdfPath) throws Exception {
        Files.createDirectories(AUDIT_DIR);
        Files.createDirectories(RENDER_DIR);
        if (!Files.exists(TEMPLATES_PATH)) {
            Files.writeString(TEMPLATES_PATH, "[]", StandardCharsets.UTF_8);
        }

        InvoiceServiceImpl service = new InvoiceServiceImpl(
                new OcrServiceImpl(dpi),
                new TemplateServiceImpl(new JsonTemplateRepository(TEMPLATES_PATH)),
                new TemplateExtractionServiceImpl(),
                new TemplateLearningServiceImpl(new JsonTemplateRepository(TEMPLATES_PATH))
        );

        Path image = renderPage(pdfPath, page, dpi);
        byte[] bytes = Files.readAllBytes(image);
        InvoiceData data = service.processInvoice(new MockMultipartFile(
                "file",
                image.getFileName().toString(),
                "image/png",
                bytes
        ));
        Files.writeString(pageOutput(page), OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(data), StandardCharsets.UTF_8);
    }

    private static int getPageCount(Path pdfPath) throws IOException {
        String script = """
                import fitz
                import sys

                doc = fitz.open(sys.argv[1])
                print(doc.page_count)
                """;
        List<String> output = runPythonScript(script, pdfPath.toString());
        if (output.isEmpty()) {
            throw new IOException("Unable to determine page count for " + pdfPath);
        }
        return Integer.parseInt(output.get(0).trim());
    }

    private static Path renderPage(Path pdfPath, int pageNumber, int dpi) throws IOException {
        Path imagePath = RENDER_DIR.resolve("page_" + pageNumber + "_dpi" + dpi + ".png");
        if (Files.exists(imagePath)) {
            return imagePath;
        }

        String script = """
                import fitz
                import sys
                from pathlib import Path

                pdf = Path(sys.argv[1])
                page_number = int(sys.argv[2])
                dpi = int(sys.argv[3])
                output = Path(sys.argv[4])
                output.parent.mkdir(parents=True, exist_ok=True)

                doc = fitz.open(pdf)
                page = doc.load_page(page_number - 1)
                scale = dpi / 72
                pix = page.get_pixmap(matrix=fitz.Matrix(scale, scale), alpha=False)
                pix.save(output.as_posix())
                print(output.as_posix())
                """;

        List<String> output = runPythonScript(
                script,
                pdfPath.toString(),
                Integer.toString(pageNumber),
                Integer.toString(dpi),
                imagePath.toString()
        );
        if (output.isEmpty()) {
            throw new IOException("Unable to render page " + pageNumber);
        }
        return imagePath;
    }

    private static PageOutcome runPageWithTimeout(Path pdfPath, int page, int dpi) throws Exception {
        Path output = pageOutput(page);
        Path logPath = AUDIT_DIR.resolve("page_" + page + ".log");
        String javaBin = Path.of(System.getProperty("java.home"), "bin", "java.exe").toString();
        Process process = new ProcessBuilder(
                javaBin,
                "-cp",
                buildAuditClasspath(),
                Invoices2AuditTest.class.getName(),
                "page",
                Integer.toString(page),
                Integer.toString(dpi),
                pdfPath.toString()
        ).redirectErrorStream(true)
                .redirectOutput(logPath.toFile())
                .start();

        if (!process.waitFor(PAGE_TIMEOUT_MINUTES, TimeUnit.MINUTES)) {
            process.destroyForcibly();
            writeErrorJson(output, page, dpi, "Timeout", "Page processing exceeded " + PAGE_TIMEOUT_MINUTES + " minutes");
            return PageOutcome.SKIPPED;
        }

        if (process.exitValue() != 0) {
            if (!Files.exists(output)) {
                String detail = Files.exists(logPath) ? Files.readString(logPath) : "See log file for details";
                writeErrorJson(output, page, dpi, "PageProcessFailed", detail);
            }
            return PageOutcome.FAILED;
        }

        return Files.exists(output) ? PageOutcome.PROCESSED : PageOutcome.FAILED;
    }

    private static String buildAuditClasspath() {
        LinkedHashSet<String> entries = new LinkedHashSet<>();
        entries.add(Path.of("target", "test-classes").toString());
        entries.add(Path.of("target", "classes").toString());
        addClasspathEntries(entries, System.getProperty("surefire.test.class.path"));
        addClasspathEntries(entries, System.getProperty("java.class.path"));
        return String.join(File.pathSeparator, entries);
    }

    private static void addClasspathEntries(LinkedHashSet<String> entries, String classpath) {
        if (classpath == null || classpath.isBlank()) {
            return;
        }
        for (String entry : classpath.split(java.util.regex.Pattern.quote(File.pathSeparator))) {
            if (entry != null && !entry.isBlank()) {
                entries.add(entry);
            }
        }
    }

    private static List<String> runPythonScript(String script, String... args) throws IOException {
        Path tempScript = Files.createTempFile("audit-pdf-", ".py");
        try {
            Files.writeString(tempScript, script, StandardCharsets.UTF_8);
            List<String> command = new ArrayList<>();
            command.add("python");
            command.add(tempScript.toString());
            for (String arg : args) {
                command.add(arg);
            }
            Process process = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .start();
            List<String> output = new ArrayList<>();
            try (var reader = process.inputReader(StandardCharsets.UTF_8)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.add(line);
                }
            }
            try {
                int exitCode = process.waitFor();
                if (exitCode != 0) {
                    throw new IOException("Python script failed with exit code " + exitCode + ": " + String.join("\n", output));
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IOException("Python execution interrupted", ex);
            }
            return output;
        } finally {
            Files.deleteIfExists(tempScript);
        }
    }

    private static Path pageOutput(int page) {
        return AUDIT_DIR.resolve("page_" + page + ".json");
    }

    private static boolean shouldSkipPage(Path output) {
        if (output == null || Files.notExists(output)) {
            return false;
        }
        try {
            String json = Files.readString(output, StandardCharsets.UTF_8);
            return !json.contains("\"error\"");
        } catch (IOException ex) {
            return false;
        }
    }

    private static Path resolvePdfPath() throws IOException {
        for (Path candidate : PDF_CANDIDATES) {
            if (Files.exists(candidate)) {
                return candidate;
            }
        }
        throw new IOException("Unable to locate invoices2.pdf. Checked: " + PDF_CANDIDATES);
    }

    private static void writeErrorJson(Path output, int page, int dpi, String error, String message) throws IOException {
        Map<String, Object> payload = Map.of(
                "page", page,
                "dpi", dpi,
                "error", error,
                "message", message == null ? "" : message
        );
        Files.writeString(output, OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(payload), StandardCharsets.UTF_8);
    }

    private static void resetTemplatesFile() throws IOException {
        try {
            Files.deleteIfExists(TEMPLATES_PATH);
        } catch (IOException ex) {
            Files.writeString(
                    TEMPLATES_PATH,
                    "[]",
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE
            );
        }
    }

    private enum PageOutcome {
        PROCESSED,
        SKIPPED,
        FAILED
    }
}
