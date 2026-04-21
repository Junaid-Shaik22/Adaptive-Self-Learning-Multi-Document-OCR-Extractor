package com.medical.extractor.service.impl;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class MedicalLineIndexingService {
    private MedicalLineIndexingService() {
    }

    public static Zones indexLinesAndZones(String text) {
        List<IndexedLine> allLines = new ArrayList<>();
        if (text != null) {
            String[] rawLines = text.split("\\R");
            for (int i = 0; i < rawLines.length; i++) {
                allLines.add(new IndexedLine(i + 1, rawLines[i] == null ? "" : rawLines[i].trim()));
            }
        }
        return new Zones(allLines);
    }

    public static final class Zones {
        public final List<IndexedLine> allLines;

        private Zones(List<IndexedLine> allLines) {
            this.allLines = Collections.unmodifiableList(new ArrayList<>(allLines));
        }
    }

    public static final class IndexedLine {
        private final int lineNumber;
        private final String text;

        private IndexedLine(int lineNumber, String text) {
            this.lineNumber = lineNumber;
            this.text = text == null ? "" : text;
        }

        public int getLineNumber() {
            return lineNumber;
        }

        public String getText() {
            return text;
        }
    }
}
