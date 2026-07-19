package com.domwil.xrag.application;

import com.domwil.xrag.domain.model.Chunk;
import com.domwil.xrag.domain.model.ExtractionResult;
import com.domwil.xrag.domain.model.SourceDocument;
import com.domwil.xrag.domain.port.ChunkRepository;
import com.domwil.xrag.domain.port.GraphRepository;
import com.domwil.xrag.domain.port.RelationExtractor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Pipeline d'ingestion (domaine) : extraction de relations → upsert graphe →
 * chunking → embeddings → upsert chunks avec node_ids. Clé de chunk stable
 * source:path:chunk_index, jamais de destruction d'index (upsert only).
 */
public class IngestionService {

    private static final Logger log = LoggerFactory.getLogger(IngestionService.class);

    private final List<RelationExtractor> extractors;
    private final GraphRepository graph;
    private final ChunkRepository chunks;
    private final EmbeddingModel embeddingModel;
    private final TextChunker chunker = new TextChunker();

    public IngestionService(List<RelationExtractor> extractors, GraphRepository graph,
                            ChunkRepository chunks, EmbeddingModel embeddingModel) {
        this.extractors = extractors;
        this.graph = graph;
        this.chunks = chunks;
        this.embeddingModel = embeddingModel;
    }

    /** @return true si le document a été (ré)indexé, false s'il était déjà à jour. */
    public boolean ingest(SourceDocument doc) {
        return ingest(doc, Set.of(), false);
    }

    public boolean ingest(SourceDocument doc, Set<String> extraNodeIds) {
        return ingest(doc, extraNodeIds, false);
    }

    /**
     * @param force ré-indexe même si la version est inchangée — nécessaire après un changement
     *              d'extraction (ex. ajout des commentaires) qui ne bump pas la version source.
     */
    public boolean ingest(SourceDocument doc, Set<String> extraNodeIds, boolean force) {
        if (!force && doc.version() != null
                && doc.version().equals(chunks.indexedVersion(doc.source(), doc.path()).orElse(null))) {
            return false; // version inchangée : pas de re-embedding
        }

        var nodeIds = new LinkedHashSet<>(extraNodeIds);
        for (RelationExtractor extractor : extractors) {
            if (!extractor.supports(doc)) {
                continue;
            }
            try {
                ExtractionResult extraction = extractor.extract(doc);
                if (!extraction.isEmpty()) {
                    graph.upsert(extraction);
                }
                nodeIds.addAll(extraction.documentNodeIds());
            } catch (Exception e) {
                log.warn("Extraction {} en échec sur {} : {}",
                        extractor.getClass().getSimpleName(), doc.chunkKeyPrefix(), e.getMessage());
            }
        }

        List<String> pieces = chunker.split(doc.content());
        if (pieces.isEmpty()) {
            return false;
        }
        List<float[]> embeddings = embeddingModel.embed(pieces);
        var toUpsert = new ArrayList<Chunk>(pieces.size());
        for (int i = 0; i < pieces.size(); i++) {
            toUpsert.add(new Chunk(
                    doc.chunkKeyPrefix() + ":" + i,
                    doc.source(), doc.project(), doc.path(), i,
                    doc.title(), pieces.get(i), doc.url(),
                    nodeIds, embeddings.get(i), doc.version()));
        }
        chunks.upsert(toUpsert);
        chunks.deleteOtherChunksOf(doc.source(), doc.path(),
                toUpsert.stream().map(Chunk::id).toList());
        return true;
    }
}
