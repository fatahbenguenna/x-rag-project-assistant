# Plan — modes de connexion explicites, périmètre par tickets, GitLab via git

> Brownfield, octobre 2026. Ne remet pas en cause les décisions d'architecture de
> `CLAUDE.md`. Une PR par étape, mergée avant d'ouvrir la suivante (après la PR #51).

## Besoins

1. **MCP pour Jira et Confluence** : xrag doit pouvoir lire Jira/Confluence via un
   serveur MCP au lieu de l'API REST. **Deux serveurs doivent marcher** : le serveur
   MCP officiel Atlassian et le serveur MCP Jira d'entreprise.
2. **Périmètre par tickets** : en entreprise, récolter tout Jira est interdit. On donne
   une liste de tickets ; xrag récupère ces tickets puis la documentation Confluence et
   les MRs GitLab qui leur sont liées. La recherche Confluence par clé
   (`text ~ "PASS-42"`) est **autorisée**.
3. **GitLab via `git`** : le code est récupéré par la commande `git` et le credential
   helper de la machine, sans token de session. Un token API **lecture seule** est
   accepté pour les métadonnées des MRs.

## Principe de configuration

Chaque source sépare **comment se connecter** (`connection`) de **quoi récupérer**
(`spaces`, `projects`, `group`… puis `scope`). Le transport (`type`) et le mode d'auth
(`auth`) sont explicites ; plus rien n'est deviné en silence.

```yaml
sources:
  jira:
    connection:
      type: rest                 # rest | mcp
      base-url: https://jira.example.com
      auth: bearer               # cookie | basic | bearer | scoped | oauth (secrets dans .env)
    projects: [PASS, INFRA]      # mode full uniquement
  confluence:
    connection:
      type: mcp
      server: atlassian          # renvoie à mcp.servers.atlassian
    spaces: [PASS, ARCHI]
  gitlab:
    connection:
      type: git                  # rest | git
      base-url: https://gitlab.example.com   # API lecture seule : MRs + découverte du groupe
    group: passerelle
    branches: [main, develop]

mcp:
  servers:
    atlassian:
      preset: atlassian-official   # atlassian-official | custom
      url: https://mcp.atlassian.com/v1/mcp
      auth: oauth                  # oauth (consentement unique, refresh token conservé) | bearer | none
    jira-entreprise:
      preset: custom
      url: https://mcp-jira.interne.example/mcp
      auth: bearer                 # MCP_JIRA_ENTREPRISE_TOKEN dans .env
      tools:                       # opération logique -> tool du serveur
        get-issue: <nom du tool>
        search-issues: <nom du tool>

scope:
  mode: seeds                    # full (comportement actuel) | seeds
  jira-issues: [PASS-42, PASS-108]
  follow:
    issue-links: 1               # profondeur des liens entre tickets
    confluence: [remote-links, urls, cql-mentions]
    gitlab: [merge-requests, commits]
  limits: { issues: 200, pages: 300, merge-requests: 200 }
```

**Rétrocompatibilité** : la forme historique (`base-url` directement sous la source)
reste acceptée comme `connection.type: rest`, avec un avertissement au démarrage.
`auth` absent = déduction historique depuis les variables `.env`, écrite dans les logs.

## PRs

| # | PR | Contenu |
|---|---|---|
| 1 | `refactor: config` | Bloc `connection` (`type: rest`, `base-url`, `auth` explicite), forme historique acceptée avec avertissement, auth explicite qui échoue au démarrage si un secret manque, préflight aligné. Aucun changement de comportement. |
| 2 | `feat: perimeter` | `scope.mode: seeds` sur REST : table `scope_items` (changeset 007 en SQL), exploration bornée sans doublons (liens entre tickets, remote links, URLs Confluence, CQL `text ~ "<clé>"`, MRs et commits citant la clé), `POST /api/admin/scope` (ADMIN_TOKEN), batch limité au périmètre, dashboard (origine de chaque document), cas d'éval. |
| 3 | `feat: gitlab-git` | `connection.type: git` : clones partiels gérés par la commande `git` (pas JGit, qui ignore les credential helpers natifs), `GIT_TERMINAL_PROMPT=0`, sous-commandes en lecture seule, diff de SHA incrémental. MRs et découverte des dépôts par l'API avec le token lecture seule. `git` dans l'image ; montage des credentials documenté (clones maintenus par l'hôte ou socket `ssh-agent`). |
| 4 | `feat: mcp` | Client MCP Spring AI, adapters `McpJiraConnector` / `McpConfluenceConnector` sur les ports existants (méthodes du périmètre comprises). Adapter **indépendant du serveur** : opérations logiques (`get-issue`, `search-issues`, `remote-links`, `get-page`, `search-pages`, `page-comments`) traduites en tools par un preset. Preset `atlassian-official` (OAuth 2.1 : consentement unique via `bootstrap.sh`, refresh token en volume) et preset `custom` à mapping explicite pour le serveur d'entreprise. Liste blanche de tools en lecture seule. Tests contre un faux serveur MCP. |
| 5 | `docs` | Décision 11 dans `CLAUDE.md` (modes de connexion et périmètre), README, RUNBOOK, VALIDATION. |

## Prérequis à obtenir avant la PR 4

- La liste des tools du serveur MCP Jira d'entreprise (sortie de `tools/list`) et un
  exemple de réponse de chaque tool utile : sans elle, le preset `custom` ne peut pas
  être validé.
- Le mode d'auth de ce serveur (header, token, OAuth, réseau interne sans auth).
- Pour le serveur officiel : confirmer que le tenant autorise l'app MCP (côté admin
  Atlassian) et le mode d'auth retenu pour un batch sans utilisateur.

## Risques

- **Tools MCP prévus pour un agent interactif** : résultats plafonnés, formats variables.
  L'adapter gère la pagination et vérifie le format au démarrage (préflight).
- **OAuth du serveur officiel** : le refresh token peut expirer ou être révoqué. Le health
  check du batch le détecte et alerte, l'index de la veille reste servi.
- **Credential helper dans Docker** : il vit sur l'hôte. La PR 3 documente les deux
  montages supportés plutôt que d'en imposer un.
