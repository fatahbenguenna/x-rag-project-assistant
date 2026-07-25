package com.domwil.xrag.config;

import com.domwil.xrag.adapter.out.rerank.NoOpChunkReranker;
import com.domwil.xrag.adapter.out.rerank.OnnxCrossEncoderReranker;
import com.domwil.xrag.domain.port.ChunkReranker;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import reactor.core.scheduler.Schedulers;

import java.nio.file.Path;

/**
 * Câblage du reranker cross-encoder (action M-7) : null-object quand désactivé
 * (team-config retrieval.reranker.enabled, défaut false — produit exportable), sinon
 * adapter ONNX chargé APRÈS le démarrage, hors du thread de boot.
 */
@Configuration
public class RerankerConfig {

    @Bean
    public ChunkReranker chunkReranker(TeamConfig config) {
        var reranker = config.retrieval().reranker();
        if (!reranker.enabled()) {
            return new NoOpChunkReranker();
        }
        return new OnnxCrossEncoderReranker(
                Path.of(reranker.modelPath()), reranker.modelFile(), reranker.candidates(),
                reranker.maxLength(), reranker.batchSize(), reranker.intraOpThreads());
    }

    /** Chargement du modèle (~2-4 s) en tâche de fond une fois l'app prête. */
    @EventListener(ApplicationReadyEvent.class)
    public void warmUpReranker(ApplicationReadyEvent event) {
        ChunkReranker reranker = event.getApplicationContext().getBean(ChunkReranker.class);
        if (reranker instanceof OnnxCrossEncoderReranker onnx) {
            Schedulers.boundedElastic().schedule(onnx::load);
        }
    }
}
