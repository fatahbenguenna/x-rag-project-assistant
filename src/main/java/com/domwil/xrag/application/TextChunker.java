package com.domwil.xrag.application;

import java.util.ArrayList;
import java.util.List;

/**
 * Découpe un document en chunks à peu près paragraphe-alignés (~1800 caractères,
 * léger recouvrement) — un compromis simple qui marche pour la doc comme pour le code.
 */
public class TextChunker {

    private static final int MAX_CHARS = 1800;
    private static final int OVERLAP_CHARS = 200;

    public List<String> split(String content) {
        if (content == null || content.isBlank()) {
            return List.of();
        }
        String text = content.strip();
        if (text.length() <= MAX_CHARS) {
            return List.of(text);
        }

        var chunks = new ArrayList<String>();
        var current = new StringBuilder();
        for (String paragraph : text.split("\n{2,}")) {
            if (paragraph.length() > MAX_CHARS) {
                flush(chunks, current);
                for (int start = 0; start < paragraph.length(); start += MAX_CHARS - OVERLAP_CHARS) {
                    chunks.add(paragraph.substring(start, Math.min(paragraph.length(), start + MAX_CHARS)));
                }
                continue;
            }
            if (current.length() + paragraph.length() + 2 > MAX_CHARS) {
                flush(chunks, current);
            }
            if (!current.isEmpty()) {
                current.append("\n\n");
            }
            current.append(paragraph);
        }
        flush(chunks, current);
        return List.copyOf(chunks);
    }

    private static void flush(List<String> chunks, StringBuilder current) {
        if (!current.isEmpty()) {
            chunks.add(current.toString());
            current.setLength(0);
        }
    }
}
