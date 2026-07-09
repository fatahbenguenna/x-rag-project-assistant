---
project_name: 'x-rag-project-assistant'
user_name: 'Fatah'
date: '2026-07-09'
sections_completed:
  ['technology_stack', 'language_rules', 'framework_rules', 'testing_rules', 'quality_rules', 'workflow_rules', 'anti_patterns']
status: 'complete'
rule_count: 36
optimized_for_llm: true
---

# Contexte projet pour agents IA

_Ce fichier contient les règles et patterns critiques que les agents IA doivent suivre pour implémenter du code dans ce projet. Il se concentre sur les détails non évidents qu'un agent risquerait de manquer. Référence produit/architecture complète : `CLAUDE.md` à la racine._

---

## Stack technique & versions

- Java **21**, Spring Boot **4.1.0** (parent Maven), Spring AI **2.0.0** (BOM)
- Starters : `webflux`, `jdbc` (pas de JPA), `validation`, `actuator`, `spring-boot-starter-liquibase` (Boot 4 exige ce starter dédié), `spring-ai-starter-model-ollama`, `spring-ai-starter-model-google-genai`, `spring-ai-starter-vector-store-pgvector`
- **Jackson 3** (`tools.jackson.*`) — Boot 4 n'auto-configure plus Jackson 2 : ne jamais importer `com.fasterxml.jackson` (les codecs HTTP ne désérialisent plus vers les types Jackson 2)
- JavaParser **3.26.4** (extraction déterministe Java) ; PostgreSQL **16** + pgvector, `embedding vector(1024)` (bge-m3), index HNSW
- LLM : Ollama `qwen2.5:7b-instruct` (chat, fallback `qwen2.5:3b`), `bge-m3` (embeddings) ; profil `gemini` commutable via `spring.ai.google.genai.*`
- Tests : JUnit 5 + Mockito ; build Maven (`mvn -B verify` en CI, Temurin 21)

## Règles d'implémentation critiques

### Règles Java

- Objets du domaine = **records** immuables dans `domain/model` ; **pas de Lombok** dans ce projet
- Javadoc et commentaires **en français** ; la Javadoc de classe résume le rôle dans le pipeline (voir `RagChatService`)
- Constantes de tuning (profondeurs, limites, seuils) en `static final` **en tête de classe**, jamais de littéraux magiques en corps de méthode
- Logger SLF4J : `private static final Logger log = LoggerFactory.getLogger(X.class);`
- SQL et prompts en **text blocks** (`"""`), pas de concaténation
- Spring AI 2 : `OllamaOptions` est scindé en `OllamaChatOptions` / `OllamaEmbeddingOptions` ; `ChatClient` accepte le builder d'options par requête

### Règles framework (Spring / hexagonal)

- Architecture hexagonale **stricte** : `domain/model` + `domain/port` sans **aucun** import Spring ; `application` ne dépend que des ports ; `adapter/in|out` implémente les ports
- Services `application` : **pas de `@Service`** — câblage explicite par `@Bean` dans `config/*Configuration` ; seuls les adapters persistence portent `@Repository`
- Nouvelle source = implémenter le port `SourceConnector` ; nouveau langage = `RelationExtractor` ; activation **uniquement** via `team-config.yml` (plugins), jamais de câblage en dur
- Toute nouvelle propriété de config passe par le record validé `TeamConfig` (`@ConfigurationProperties`) **et** par `team-config.example.yml` — un test vérifie le binding de l'exemple, il échouera si l'exemple diverge
- WebFlux réactif : le chat renvoie `Flux` (SSE, streaming obligatoire) ; **jamais de `.block()`** dans le chemin de requête
- Un seul `ChatModel` actif à la fois ; les embeddings restent **toujours** Ollama/local, même en profil `gemini` (confidentialité)
- `spring.ai.vectorstore.pgvector.initialize-schema: false` — le schéma appartient à Liquibase, jamais à Spring AI

### Règles de tests

- Tests unitaires **sans contexte Spring** : JUnit 5 + Mockito pur, mocks en champs `private final X x = mock(X.class);`
- Nommage `*Test.java`, même package que la classe testée
- Tous les tests verts (`mvn -B verify`) avant toute PR — la CI l'exécute sur chaque PR et push `main`
- Quand une PR touche du SQL : vérifier les requêtes contre un **Postgres 16 + pgvector réel** et le mentionner dans la section « Vérification » de la PR (pattern établi)

### Règles qualité & style

- Ports suffixés par rôle (`Repository`, `Connector`, `Extractor`, `Notifier`) ; adapters JDBC préfixés `Jdbc` ; pas d'interface pour les services `application`
- Documents et messages destinés aux humains en **français** ; identifiants de code en anglais
- Contenu lean : pas de code mort, pas de commentaires évidents, chaque classe documentée par une Javadoc utile
- Les fiches techniques longues vont dans `docs/`, les artefacts BMAD dans `_bmad-output/` — ne pas gonfler le README

### Règles de workflow

- Branches : `type/description-courte` (`feat/`, `chore/`, `docs/`, `ci/`)
- Commits et titres de PR : **Conventional Commits en français** (`feat:`, `chore:`, `docs:`, `ci:`)
- Une PR par étape, mergée avant d'ouvrir la suivante ; description avec sections **Contenu** et **Vérification**
- Release : tag `vX.Y.Z` → build + push image `rag-api` (GHCR par défaut, registry privé via secrets `REGISTRY_*`)

### Règles critiques à ne pas manquer

- **Jamais Flyway** — Liquibase uniquement (décision explicite) ; nouveau changelog = `db/changelog/NNN-nom.sql` + référence dans `db.changelog-master.yaml`
- **Jamais Neo4j** — le graphe vit dans Postgres (`graph_nodes`/`graph_edges`, PK `(src,dst,type)`) ; voisinage via `WITH RECURSIVE` profondeur 2, plafonné (protection du prompt)
- **Jamais de destruction d'index** : upsert only (`ON CONFLICT`), clé de chunk stable `source:path:chunk_index` ; un batch nocturne en échec laisse l'index de la veille servi
- Index HNSW : jamais de rebuild — `VACUUM ANALYZE` seulement
- Secrets **uniquement** en variables d'environnement (`.env`) ; jamais dans `team-config.yml` ni `application.yml`
- **Aucun nom de projet/équipe en dur** dans le code — tout vient de `team-config.yml` (produit exportable)
- Extraction de relations : **déterministe d'abord** (regex, JavaParser, parsing TS) ; extraction LLM nocturne seulement si l'éval `/api/admin/graph-quality` montre des trous
- Connecteurs et extracteurs **ne lèvent jamais** pour un document invalide (résultat vide) ; une implémentation de `Notifier` ne fait jamais échouer le batch
- Questions factuelles/structurées (MRs, tris, comptages) via **tools SQL** (`@Tool`), pas via le RAG
- Réponses LLM : concises (~200 mots en descriptif), **toujours citer les sources** (page/fichier/MR)
- Les décisions de la section « Décisions d'architecture » du `CLAUDE.md` sont **non négociables** — ne pas les remettre en cause

---

## Consignes d'utilisation

**Pour les agents IA :**

- Lire ce fichier avant toute implémentation
- Suivre TOUTES les règles telles que documentées
- En cas de doute, choisir l'option la plus restrictive
- Mettre à jour ce fichier si de nouveaux patterns émergent

**Pour les humains :**

- Garder ce fichier lean, centré sur les besoins des agents
- Mettre à jour à chaque évolution de la stack
- Revoir périodiquement pour retirer les règles devenues évidentes

Dernière mise à jour : 2026-07-09
