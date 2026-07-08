/**
 * Assistant RAG d'équipe — architecture hexagonale.
 *
 * <ul>
 *   <li>{@code domain} — modèle métier (documents, graphe, chunks) et ports ; aucune dépendance Spring/IO.</li>
 *   <li>{@code application} — cas d'usage (ingestion, retrieval, batch nocturne) orchestrant les ports.</li>
 *   <li>{@code adapter.in} — entrées : API REST/chat, scheduler, webhooks GitLab.</li>
 *   <li>{@code adapter.out} — sorties : connecteurs Confluence/GitLab/Jira, persistance Postgres, LLM.</li>
 *   <li>{@code infrastructure} — configuration Spring, chargement de {@code team-config.yml}.</li>
 * </ul>
 */
package com.domwil.xrag;
