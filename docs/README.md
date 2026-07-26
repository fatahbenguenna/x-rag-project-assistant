# docs/ — documentation opérationnelle et connaissance projet

## Documentation opérationnelle

- **[RUNBOOK.md](RUNBOOK.md)** — guide d'exploitation pas à pas (🪟 Windows / 🐧 WSL) :
  installation, credentials, démarrage, accès aux services, dépannage.
- **[VALIDATION.md](VALIDATION.md)** — checklist de mise en service, critères mesurables.
- **[WORKFLOWS.md](WORKFLOWS.md)** — les flux du système en schémas Mermaid.

Le point d'entrée reste le `README.md` à la racine (carte « Documentation »).

## Connaissance long-terme (BMAD `project_knowledge`)

Ce dossier est aussi la destination « project knowledge » configurée pour la méthode
BMAD (voir `_bmad/bmm/config.yaml`) : les agents BMAD y **lisent** la connaissance
durable (ex. `revue-architecture-rag-2026-07.md`). Leurs **artefacts** (PRD,
architecture, epics, stories) sont produits ailleurs, dans `_bmad-output/`.

La référence canonique du projet — objectif, décisions d'architecture validées, modèle
de graphe, batch nocturne, exportabilité, stack — est **`CLAUDE.md`** à la racine du
dépôt. Tout document produit ici doit rester cohérent avec ce fichier.
