package com.invoice.extractor.util;

import java.text.Normalizer;

public class TextUtil {
    public static String cleanOcrText(String text) {
        if (text == null) {
            return "";
        }

        text = Normalizer.normalize(text, Normalizer.Form.NFKC);
        text = text
                .replace('\u2013', '-')
                .replace('\u2014', '-')
                .replace('\u2212', '-')
                .replace('\u2018', '\'')
                .replace('\u2019', '\'')
                .replace('\u201C', '"')
                .replace('\u201D', '"')
                .replace('\u00A0', ' ')
                .replace('\u2022', ' ');

        StringBuilder normalized = new StringBuilder(text.length());
        for (char ch : text.toCharArray()) {
            if (ch == '\r') {
                continue;
            }
            if (ch == '\n' || ch == '\t' || !Character.isISOControl(ch)) {
                normalized.append(ch);
            }
        }
        text = normalized.toString();
        text = text.replaceAll("\\r\\n?", "\\n");
        text = text.replaceAll("\\t+", "    ");
        text = text.replaceAll(" {3,}", " | ");
        text = text.replaceAll("\\n{3,}", "\n\n");
        text = text.replaceAll("([\\-=~_])\\1{2,}", "$1");
        text = text.replaceAll("\\s*([:#])\\s*", " $1 ");
        text = text.replaceAll("(?<=\\d),\\s+(?=\\d)", ",");
        text = text.replaceAll(" +", " ");

        StringBuilder sb = new StringBuilder();
        for (String line : text.split("\\n")) {
            String cleaned = line.trim();
            if (!cleaned.isEmpty()) {
                sb.append(cleaned).append("\n");
            }
        }

        return sb.toString().trim();
    }
}
