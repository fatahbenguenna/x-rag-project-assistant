package com.domwil.xrag.adapter.out.git;

import com.domwil.xrag.adapter.out.IndexedCodeFiles;
import com.domwil.xrag.domain.model.SourceDocument;
import com.domwil.xrag.domain.port.SourceConnector;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Lecture du code d'une branche directement dans l'object store git (pas de checkout : la
 * branche lue est la ref de suivi du remote, pas le worktree du dev, possiblement sale).
 *
 * <p>Version d'un document = id du blob (hash du contenu) : l'ingestion saute les fichiers
 * inchangés, donc la récolte peut être complète à chaque sync tout en ne ré-embeddant que
 * ce qui a changé — sans dépendre d'une fenêtre temporelle (un fetch tardif ne crée pas de trou).
 * Les documents sont remis un à un au consommateur : rien n'est accumulé en mémoire.
 */
final class GitCodeReader {

    private static final Logger log = LoggerFactory.getLogger(GitCodeReader.class);
    private static final int BINARY_PROBE_BYTES = 8000;
    static final String TRANSPORT = "git-local";

    private final long maxFileBytes;
    private final String webBaseUrl;

    GitCodeReader(int maxFileKb, String webBaseUrl) {
        this.maxFileBytes = maxFileKb * 1024L;
        this.webBaseUrl = webBaseUrl;
    }

    /**
     * @param alreadyEmitted paths (source) déjà remis au consommateur — en cas de nouvel essai
     *                       après un objet manquant, aucun document n'est ré-émis
     * @return nombre de documents remis au consommateur
     */
    int stream(Repository repository, GitRepositories.LocalRepo repo, String branch, ObjectId head,
               Set<String> alreadyEmitted, Consumer<SourceDocument> sink) throws IOException {
        int emitted = 0;
        int skipped = 0;
        try (RevWalk walk = new RevWalk(repository);
             ObjectReader reader = repository.newObjectReader();
             TreeWalk tree = new TreeWalk(reader)) {
            RevCommit commit = walk.parseCommit(head);
            tree.addTree(commit.getTree());
            tree.setRecursive(true);
            while (tree.next()) {
                String path = tree.getPathString();
                FileMode mode = tree.getFileMode(0);
                if ((mode != FileMode.REGULAR_FILE && mode != FileMode.EXECUTABLE_FILE)
                        || !IndexedCodeFiles.indexable(path)) {
                    continue; // symlinks, sous-modules, extensions non indexées
                }
                String documentPath = repo.slug() + "@" + branch + "/" + path;
                if (alreadyEmitted.contains(documentPath)) {
                    continue;
                }
                ObjectId blobId = tree.getObjectId(0);
                ObjectLoader loader = reader.open(blobId, Constants.OBJ_BLOB);
                if (loader.getSize() > maxFileBytes || loader.isLarge()) {
                    skipped++;
                    continue;
                }
                byte[] bytes = loader.getBytes();
                if (looksBinary(bytes)) {
                    skipped++;
                    continue;
                }
                alreadyEmitted.add(documentPath);
                sink.accept(new SourceDocument(
                        SourceConnector.CODE_SOURCE,
                        repo.slug(),
                        documentPath,
                        path,
                        decode(bytes),
                        blobUrl(repo, branch, path),
                        blobId.name(),
                        null,
                        Map.of("branch", branch, "file", path, "commit", commit.name(),
                                "transport", TRANSPORT, "projectPath", repo.fullPath())));
                emitted++;
            }
        }
        if (skipped > 0) {
            log.info("Dépôt {} @{} : {} fichier(s) ignoré(s) (binaire ou > {} Ko)",
                    repo.slug(), branch, skipped, maxFileBytes / 1024);
        }
        return emitted;
    }

    private String blobUrl(GitRepositories.LocalRepo repo, String branch, String path) {
        String origin = webBaseUrl != null ? webBaseUrl : repo.webOrigin();
        return origin == null ? null : origin + "/" + repo.fullPath() + "/-/blob/" + branch + "/" + path;
    }

    /** Heuristique de git lui-même : un octet nul dans les premiers 8 Ko = binaire. */
    static boolean looksBinary(byte[] bytes) {
        int limit = Math.min(bytes.length, BINARY_PROBE_BYTES);
        for (int i = 0; i < limit; i++) {
            if (bytes[i] == 0) {
                return true;
            }
        }
        return false;
    }

    /**
     * UTF-8 strict avec repli ISO-8859-1 (vieux .properties/.sql : les accents FR survivent
     * au lieu de devenir U+FFFD), BOM retiré, fins de ligne normalisées en LF.
     */
    static String decode(byte[] bytes) {
        int offset = bytes.length >= 3 && (bytes[0] & 0xFF) == 0xEF && (bytes[1] & 0xFF) == 0xBB
                && (bytes[2] & 0xFF) == 0xBF ? 3 : 0;
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes, offset, bytes.length - offset))
                    .toString();
        } catch (CharacterCodingException e) {
            text = new String(bytes, offset, bytes.length - offset, StandardCharsets.ISO_8859_1);
        }
        return text.indexOf('\r') < 0 ? text : text.replace("\r\n", "\n").replace('\r', '\n');
    }
}
