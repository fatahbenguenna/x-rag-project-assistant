package com.domwil.xrag.extraction;

import com.domwil.xrag.application.AliasResolver;
import com.domwil.xrag.domain.model.ExtractionResult;
import com.domwil.xrag.domain.model.GraphEdge;
import com.domwil.xrag.domain.model.GraphNode;
import com.domwil.xrag.domain.model.SourceDocument;
import com.domwil.xrag.domain.port.RelationExtractor;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.expr.SingleMemberAnnotationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Extraction déterministe des relations depuis le code Java (JavaParser) :
 * <ul>
 *   <li>{@code @Entity} / {@code @Table} → nœuds TABLE + SHARES_TABLE (deux
 *       projets touchant la même table se retrouvent voisins à profondeur 2) ;</li>
 *   <li>{@code @FeignClient} → CALLS_API (projet cible via alias, sinon ENDPOINT) ;</li>
 *   <li>{@code @KafkaListener} → CONSUMES, {@code kafkaTemplate.send(...)} → PUBLISHES.</li>
 * </ul>
 */
public class JavaRelationExtractor implements RelationExtractor {

    private final AliasResolver aliases;
    private final JavaParser parser = new JavaParser();

    public JavaRelationExtractor(AliasResolver aliases) {
        this.aliases = aliases;
    }

    @Override
    public boolean supports(SourceDocument document) {
        return "gitlab-code".equals(document.source()) && document.path().endsWith(".java");
    }

    @Override
    public ExtractionResult extract(SourceDocument document) {
        var parseResult = parser.parse(document.content());
        Optional<CompilationUnit> parsed = parseResult.getResult();
        if (!parseResult.isSuccessful() || parsed.isEmpty()) {
            return ExtractionResult.empty();
        }
        CompilationUnit unit = parsed.get();

        String projectId = aliases.projectIdFor(document.project());
        GraphNode project = aliases.projectNode(projectId);
        var result = ExtractionResult.builder();
        result.node(project).linkDocumentTo(projectId);

        for (ClassOrInterfaceDeclaration type : unit.findAll(ClassOrInterfaceDeclaration.class)) {
            extractEntities(type, project, result);
            extractFeignClients(type, project, result);
        }
        extractKafkaListeners(unit, project, result);
        extractKafkaPublishers(unit, project, result);
        return result.build();
    }

    private void extractEntities(ClassOrInterfaceDeclaration type, GraphNode project,
                                 ExtractionResult.Builder result) {
        boolean isEntity = type.getAnnotationByName("Entity").isPresent()
                || type.getAnnotationByName("Table").isPresent();
        if (!isEntity) {
            return;
        }
        String tableName = type.getAnnotationByName("Table")
                .flatMap(a -> annotationValue(a, "name"))
                .orElse(snakeCase(type.getNameAsString()));
        GraphNode table = GraphNode.of("table:" + tableName.toLowerCase(Locale.ROOT),
                GraphNode.Types.TABLE, tableName);
        result.edge(project, table, GraphEdge.Types.SHARES_TABLE)
                .linkDocumentTo(table.id());
    }

    private void extractFeignClients(ClassOrInterfaceDeclaration type, GraphNode project,
                                     ExtractionResult.Builder result) {
        type.getAnnotationByName("FeignClient")
                .flatMap(a -> annotationValue(a, "name").or(() -> annotationValue(a, "value")))
                .ifPresent(target -> {
                    GraphNode dst = aliases.resolveProjectId(target)
                            .map(aliases::projectNode)
                            .orElse(GraphNode.of("endpoint:" + AliasResolver.normalize(target),
                                    GraphNode.Types.ENDPOINT, target));
                    result.edge(project, dst, GraphEdge.Types.CALLS_API)
                            .linkDocumentTo(dst.id());
                });
    }

    private void extractKafkaListeners(CompilationUnit unit, GraphNode project,
                                       ExtractionResult.Builder result) {
        unit.findAll(AnnotationExpr.class).stream()
                .filter(a -> a.getNameAsString().equals("KafkaListener"))
                .forEach(annotation -> topicsOf(annotation).forEach(topic ->
                        result.edge(project, topicNode(topic), GraphEdge.Types.CONSUMES)
                                .linkDocumentTo(topicNode(topic).id())));
    }

    private void extractKafkaPublishers(CompilationUnit unit, GraphNode project,
                                        ExtractionResult.Builder result) {
        unit.findAll(MethodCallExpr.class).stream()
                .filter(call -> call.getNameAsString().equals("send"))
                .filter(call -> call.getScope()
                        .map(scope -> scope.toString().toLowerCase(Locale.ROOT).contains("kafkatemplate"))
                        .orElse(false))
                .forEach(call -> call.getArguments().stream()
                        .findFirst()
                        .filter(StringLiteralExpr.class::isInstance)
                        .map(arg -> ((StringLiteralExpr) arg).getValue())
                        .ifPresent(topic -> result
                                .edge(project, topicNode(topic), GraphEdge.Types.PUBLISHES)
                                .linkDocumentTo(topicNode(topic).id())));
    }

    private static GraphNode topicNode(String topic) {
        return GraphNode.of("topic:" + topic.toLowerCase(Locale.ROOT), GraphNode.Types.TOPIC, topic);
    }

    /** Valeur littérale d'un attribut d'annotation ({@code @Table(name = "x")}, {@code @FeignClient("x")}). */
    private static Optional<String> annotationValue(AnnotationExpr annotation, String attribute) {
        if (annotation instanceof SingleMemberAnnotationExpr single
                && single.getMemberValue() instanceof StringLiteralExpr literal
                && attribute.equals("value")) {
            return Optional.of(literal.getValue());
        }
        if (annotation instanceof NormalAnnotationExpr normal) {
            return normal.getPairs().stream()
                    .filter(pair -> pair.getNameAsString().equals(attribute))
                    .filter(pair -> pair.getValue() instanceof StringLiteralExpr)
                    .map(pair -> ((StringLiteralExpr) pair.getValue()).getValue())
                    .findFirst();
        }
        return Optional.empty();
    }

    /** Topics d'un {@code @KafkaListener} : littéral simple ou tableau de littéraux. */
    private static Set<String> topicsOf(AnnotationExpr annotation) {
        var topics = new LinkedHashSet<String>();
        if (annotation instanceof NormalAnnotationExpr normal) {
            normal.getPairs().stream()
                    .filter(pair -> pair.getNameAsString().equals("topics"))
                    .forEach(pair -> pair.getValue().findAll(StringLiteralExpr.class)
                            .forEach(literal -> topics.add(literal.getValue())));
        }
        return topics;
    }

    private static String snakeCase(String className) {
        return className.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase(Locale.ROOT);
    }
}
