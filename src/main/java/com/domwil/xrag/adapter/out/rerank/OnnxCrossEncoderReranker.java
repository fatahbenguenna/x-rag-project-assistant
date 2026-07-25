package com.domwil.xrag.adapter.out.rerank;

import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.djl.huggingface.translator.CrossEncoderTranslator;
import ai.djl.inference.Predictor;
import ai.djl.repository.zoo.Criteria;
import ai.djl.repository.zoo.ZooModel;
import ai.djl.translate.TranslateException;
import ai.djl.util.StringPair;
import com.domwil.xrag.domain.model.ScoredChunk;
import com.domwil.xrag.domain.port.ChunkReranker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Reranker cross-encoder (bge-reranker-v2-m3 ONNX int8) chargé in-process via DJL /
 * ONNX Runtime — reclasse le vivier de candidats du retrieval en topK sur CPU.
 *
 * <p>Robustesse : chargement en tâche de fond après le boot (l'app démarre sans
 * attendre) ; modèle absent ou corrompu → passe-plat avec WARN (désactivation
 * gracieuse, l'ordre du retrieval est conservé). Le {@link ZooModel} est thread-safe ;
 * un {@link Predictor} (non thread-safe) est créé par appel — négligeable devant une
 * inférence de plusieurs secondes, et adapté au scheduler boundedElastic.
 */
public final class OnnxCrossEncoderReranker implements ChunkReranker {

    private static final Logger log = LoggerFactory.getLogger(OnnxCrossEncoderReranker.class);

    private final Path modelDir;
    private final String modelName;
    private final int candidatePoolSize;
    private final int maxLength;
    private final int batchSize;
    private final int intraOpThreads;

    /** Non-null seulement une fois le modèle chargé avec succès ; sinon passe-plat. */
    private volatile ZooModel<StringPair, float[]> model;

    public OnnxCrossEncoderReranker(Path modelDir, String modelName, int candidatePoolSize,
                                    int maxLength, int batchSize, int intraOpThreads) {
        this.modelDir = modelDir;
        this.modelName = modelName;
        this.candidatePoolSize = candidatePoolSize;
        this.maxLength = maxLength;
        this.batchSize = batchSize;
        this.intraOpThreads = intraOpThreads;
    }

    /**
     * Charge le modèle — à invoquer en tâche de fond après le démarrage. Toute erreur
     * laisse {@code model == null} : le reranker reste en passe-plat.
     */
    public void load() {
        Path onnx = modelDir.resolve(modelName + ".onnx");
        if (!Files.isRegularFile(onnx) || !Files.isRegularFile(modelDir.resolve("tokenizer.json"))) {
            log.warn("Reranker désactivé : modèle ou tokenizer absent sous {} (attendu {}.onnx + "
                    + "tokenizer.json — voir bootstrap.sh). Repli sur l'ordre du retrieval.",
                    modelDir, modelName);
            return;
        }
        try {
            HuggingFaceTokenizer tokenizer = HuggingFaceTokenizer.builder()
                    .optTokenizerPath(modelDir)
                    .optMaxLength(maxLength)
                    .optTruncation(true)
                    .optPadding(true)          // le batchifier STACK exige des longueurs égales
                    .build();
            CrossEncoderTranslator translator = CrossEncoderTranslator.builder(tokenizer)
                    .optSigmoid(true)          // score 0..1 (monotone : ordre préservé)
                    .optIncludeTokenTypes(false) // XLM-R : pas de token_type_ids dans le graphe ONNX
                    .build();
            Criteria<StringPair, float[]> criteria = Criteria.builder()
                    .setTypes(StringPair.class, float[].class)
                    .optModelPath(modelDir)
                    .optModelName(modelName)
                    .optEngine("OnnxRuntime")
                    .optTranslator(translator)
                    .optOption("intraOpNumThreads", String.valueOf(intraOpThreads))
                    .optOption("interOpNumThreads", "1")
                    .build();
            this.model = criteria.loadModel();
            log.info("Reranker cross-encoder chargé : {} (maxLength={}, batch={}, threads={})",
                    onnx.getFileName(), maxLength, batchSize, intraOpThreads);
        } catch (Exception e) {
            log.warn("Chargement du reranker échoué ({}). Repli sur l'ordre du retrieval.", e.toString());
        }
    }

    @Override
    public int candidatePoolSize(int topK) {
        return model == null ? topK : Math.max(candidatePoolSize, topK);
    }

    @Override
    public List<ScoredChunk> rerank(String question, List<ScoredChunk> candidates, int topK) {
        ZooModel<StringPair, float[]> loaded = this.model;
        if (loaded == null || candidates.size() <= topK) {
            return candidates.size() <= topK ? candidates : List.copyOf(candidates.subList(0, topK));
        }
        long startedAt = System.nanoTime();
        try (Predictor<StringPair, float[]> predictor = loaded.newPredictor()) {
            var scored = new ArrayList<Scored>(candidates.size());
            for (int start = 0; start < candidates.size(); start += batchSize) {
                List<ScoredChunk> slice =
                        candidates.subList(start, Math.min(start + batchSize, candidates.size()));
                List<StringPair> pairs = slice.stream()
                        .map(chunk -> new StringPair(question, chunk.content()))
                        .toList();
                List<float[]> outputs = predictor.batchPredict(pairs);
                for (int i = 0; i < slice.size(); i++) {
                    scored.add(new Scored(slice.get(i), outputs.get(i)[0]));
                }
            }
            List<ScoredChunk> top = scored.stream()
                    .sorted(Comparator.comparingDouble(Scored::score).reversed())
                    .limit(topK)
                    .map(Scored::toChunk)
                    .toList();
            log.debug("Rerank : {} candidats -> top-{} en {} ms", candidates.size(), topK,
                    (System.nanoTime() - startedAt) / 1_000_000);
            return top;
        } catch (TranslateException e) {
            log.warn("Rerank échoué à l'exécution ({}) — repli sur l'ordre du retrieval.", e.getMessage());
            return List.copyOf(candidates.subList(0, topK));
        }
    }

    /** Paire (chunk, score reranker) ; reconstruit un ScoredChunk portant le nouveau score. */
    private record Scored(ScoredChunk chunk, double score) {
        ScoredChunk toChunk() {
            return new ScoredChunk(chunk.id(), chunk.source(), chunk.project(), chunk.path(),
                    chunk.title(), chunk.content(), chunk.url(), score);
        }
    }
}
