package com.invoice.extractor.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class ConvertedPdfDocument implements AutoCloseable {
    private final Path workingDirectory;
    private final List<Path> imagePaths;

    public ConvertedPdfDocument(Path workingDirectory, List<Path> imagePaths) {
        this.workingDirectory = workingDirectory;
        this.imagePaths = imagePaths == null ? List.of() : List.copyOf(new ArrayList<>(imagePaths));
    }

    public List<Path> getImagePaths() {
        return imagePaths;
    }

    @Override
    public void close() {
        if (workingDirectory == null || Files.notExists(workingDirectory)) {
            return;
        }
        try (var walk = Files.walk(workingDirectory)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                }
            });
        } catch (IOException ignored) {
        }
    }
}
