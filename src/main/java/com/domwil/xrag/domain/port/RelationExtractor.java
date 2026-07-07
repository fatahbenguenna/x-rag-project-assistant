package com.domwil.xrag.domain.port;

import com.domwil.xrag.domain.model.ExtractionResult;
import com.domwil.xrag.domain.model.SourceDocument;

/**
 * Plugin d'extraction déterministe de relations (regex, JavaParser, parsing TS).
 * Activé par team-config.yml (extractors.java / typescript / ...). L'extraction
 * LLM nocturne ne sera ajoutée que si l'évaluation montre des trous.
 */
public interface RelationExtractor {

    boolean supports(SourceDocument document);

    ExtractionResult extract(SourceDocument document);
}
