# Documentation technique – MicroCRM (Orion)

| | |
|---|---|
| **Titre** | Documentation technique – Industrialisation CI/CD de MicroCRM |
| **Auteur** | Bruno Hoarau |
| **Option choisie** | Option B (Scénario Orion) |
| **Date** | 05/11/2026 |
| **Dépôt** | https://github.com/brunoHoarau/Hoarau_Bruno_09_Gerez_un_projet_d-integration_et_de_deploiement_continu |

> Statut : les plans (sections 3, 4 et 5) sont définis **avant** toute mise en œuvre technique. Les sections marquées « à compléter » le seront au fil de la réalisation du pipeline.

---

## 1. Introduction

### 1.1 Contexte du projet

Orion souhaite moderniser la livraison de son application interne **MicroCRM**, un CRM simplifié permettant de créer, modifier et consulter des individus rattachés à des organisations. Aujourd'hui, la construction, les tests et le déploiement sont manuels : aucune chaîne CI/CD, aucune analyse qualité, aucune orchestration de conteneurs.

### 1.2 Objectifs de l'industrialisation

- Automatiser **build, tests, analyse qualité/sécurité, conteneurisation et déploiement** à chaque modification du code.
- Détecter les régressions et les vulnérabilités **avant** qu'elles n'atteignent la branche principale.
- Rendre le déploiement **reproductible** (images Docker versionnées, Docker Compose).
- Réduire la dette technique en la rendant **visible et mesurée** (SonarQube Cloud).

### 1.3 Technologies principales

| Composant | Technologie | Version actuelle du projet | Statut (nov. 2026) |
|---|---|---|---|
| Back-end | Spring Boot (Spring Data REST, JPA) | 3.2.5 | Hors support OSS |
| Langage back | Java | 17 (compilation) / 21 (exécution Docker) | LTS supportées |
| Build back | Gradle (wrapper) | 8.7 | Branche 8 en maintenance (actuelle : 9.8) |
| Base de données | HSQLDB **en mémoire** | – | Données non persistantes |
| Front-end | Angular | 17.3 | Hors support (versions supportées : 20, 21, 22) |
| Tests front | Karma + Jasmine (Chrome Headless) | Karma 6.4 | – |
| Serveur web front | Caddy (Alpine) | Alpine 3.19 | Alpine 3.19 hors support |
| CI/CD | GitHub Actions | – | À mettre en place |
| Qualité / sécurité | SonarQube Cloud | – | À mettre en place |
| Orchestration | Docker Compose | – | À mettre en place |

### 1.4 État des lieux

**Organisation du code (monorepo)**

```
back/            API Spring Boot (Gradle)
front/           Application Angular (npm)
misc/docker/     Caddyfile (serveur web) et supervisor.ini (image tout-en-un)
Dockerfile       Build multi-stage : cibles front, back, standalone
```

**Commandes locales vérifiées**

| Action | Commande | Résultat constaté |
|---|---|---|
| Build back (inclut les tests) | `cd back && ./gradlew build` | OK – JAR `build/libs/microcrm-0.0.1-SNAPSHOT.jar` |
| Tests back | `cd back && ./gradlew test` | OK – 2 tests |
| Installation front | `cd front && npm ci` | OK – 90 vulnérabilités npm signalées (dont 5 critiques) |
| Build front | `cd front && npx ng build` | OK – `dist/microcrm/browser` |
| Tests front (mode CI) | `cd front && npx ng test --watch=false --browsers=ChromeHeadless --code-coverage` | OK – 8 tests, ~31 % de couverture des lignes |

**Tests existants**

- Back : `MicroCRMApplicationTests` (démarrage du contexte Spring) et `PersonRepositoryIntegrationTest` (test d'intégration JPA de `findByEmail`).
- Front : 8 tests Jasmine, essentiellement des tests de création de composants/services (« should create ») et du titre de l'application.

**Contraintes techniques identifiées pour la CI/CD**

1. `back/gradlew` n'a pas le droit d'exécution dans Git : `Permission denied` sur un runner Linux et dans le build Docker.
2. `FROM node` sans version : build front non reproductible ; Angular 17 ne supporte officiellement que Node 18/20 (en fin de vie).
3. Étage `back` du Dockerfile : `EXPOSE 4200` alors que l'API écoute sur **8080**.
4. Image de base `alpine:3.19` hors support, conteneurs exécutés en `root`.
5. `./gradlew build` dans le Dockerfile ré-exécute les tests déjà joués en CI.
6. `ng test` est en mode « watch » par défaut ; la CI doit forcer `--watch=false` et un navigateur headless.
7. Aucun rapport de couverture exploitable par SonarQube (pas de JaCoCo côté back, pas de rapport `lcov` côté front).
8. URL de l'API codée en dur dans le front (`http://localhost:8080`) : l'API doit être publiée sur le port 8080 de l'hôte.
9. Base HSQLDB en mémoire : aucune donnée persistante à ce jour.
10. API sans authentification et CORS ouvert à `*`.

### 1.5 Présentation rapide du pipeline CI/CD

Le workflow `.github/workflows/ci.yml` exécute en parallèle deux jobs, back-end et front-end. Chacun construit son application, exécute les tests existants, mesure la couverture et soumet le code à SonarQube Cloud, dont la Quality Gate bloque le pipeline en cas d'échec. Les rapports sont publiés en artefacts. Dependabot (`.github/dependabot.yml`) propose chaque semaine les mises à jour de dépendances, qui passent elles-mêmes par le pipeline.

---

## 2. Étapes de mise en œuvre du pipeline CI/CD

### 2.1 Structure du pipeline

```
                 ┌───────────────────────────── job "back" ─────────────────────────────┐
 push / PR /     │ checkout → Java 21 → Gradle (cache) → build + tests + JaCoCo         │
 nightly /  ───► │          → SonarQube Cloud (Quality Gate) → artefacts de rapports    │
 manuel          ├───────────────────────────── job "front" ────────────────────────────┤
                 │ checkout → Node 24 (cache npm) → npm ci → ng build → ng test + lcov  │
                 │          → npm audit (non bloquant) → SonarQube Cloud → artefacts    │
                 └──────────────────────────────────────────────────────────────────────┘
```

**Déclencheurs** (conformes au plan de testing, section 4.2) :

| Déclencheur | Configuration |
|---|---|
| Push sur toute branche | `push: branches: ["**"]` |
| Pull request vers `main` | `pull_request: branches: [main]` |
| Nightly | `schedule: cron "0 2 * * *"` (tous les jours à 2 h UTC, sur `main`) |
| Manuel | `workflow_dispatch` |

**Ordre d'exécution** : les jobs `back` et `front` sont **indépendants et parallèles**, ce qui réduit la durée totale. Dans chaque job, les étapes sont séquentielles : un échec de build ou de test arrête le job avant l'analyse SonarQube. Les rapports sont publiés **même en cas d'échec** (`if: always()`) pour faciliter le diagnostic.

Les étapes de conteneurisation (build des images, smoke test Docker Compose) et de déploiement seront ajoutées lors des étapes suivantes.

**Justification des actions GitHub retenues** : uniquement des actions officielles et maintenues, épinglées sur leur dernière version majeure (vérifiée en novembre 2026).

| Action | Version | Rôle | Pourquoi ce choix |
|---|---|---|---|
| `actions/checkout` | v7 | Récupération du code | Action officielle GitHub ; `fetch-depth: 0` fournit l'historique complet dont SonarQube a besoin pour identifier le nouveau code et les auteurs |
| `actions/setup-java` | v6 | Installation du JDK | Action officielle ; distribution **Temurin 21** (LTS), identique au JRE de l'image Docker du back |
| `gradle/actions/setup-gradle` | v6 | Cache Gradle | Action officielle Gradle : cache des dépendances et du wrapper, **validation de l'intégrité du wrapper** (protection de la chaîne d'approvisionnement), résumé du build dans l'interface GitHub |
| `actions/setup-node` | v7 | Installation de Node.js | Action officielle ; **Node 24** (LTS, support jusqu'en avril 2028), compatible avec Angular 17 (`engines: >=20.9.0`) ; cache npm basé sur `package-lock.json` |
| `SonarSource/sonarqube-scan-action` | v8 | Analyse du front | Action officielle SonarSource ; vérifie la signature GPG du scanner téléchargé |
| `actions/upload-artifact` | v7 | Publication des rapports | Action officielle ; rapports consultables depuis la page du run pendant 14 jours |

**Outil de build back-end** : l'énoncé mentionne Maven, mais le projet existant utilise **Gradle** (wrapper 8.7). Le pipeline conserve Gradle pour ne pas réécrire le build : le wrapper garantit une version identique en local et en CI. Le back est analysé avec le **plugin Gradle `org.sonarqube`**, recommandé par SonarSource pour les projets Gradle (il récupère automatiquement sources, binaires et classpath).

**SonarQube Cloud, deux projets** : le dépôt est un monorepo contenant deux applications de technologies différentes. Elles sont analysées comme **deux projets SonarQube** (back et front), ce qui correspond au mode monorepo de SonarQube Cloud : chaque application a sa propre Quality Gate et ses propres indicateurs.

### 2.2 Scripts et fichiers d'automatisation

| Fichier | Rôle dans le pipeline |
|---|---|
| `.github/workflows/ci.yml` | Workflow CI : toute la logique d'automatisation est centralisée ici |
| `.github/dependabot.yml` | Mises à jour hebdomadaires des dépendances Gradle, npm, actions GitHub et images Docker |
| `back/build.gradle` | Ajout du plugin **JaCoCo** (rapport de couverture XML/HTML généré après les tests) et du plugin **`org.sonarqube`** (URL SonarQube Cloud, chemin du rapport JaCoCo, attente de la Quality Gate) |
| `front/karma.conf.js` | Ajout du reporter de couverture **`lcov`**, format lu par SonarQube |
| `front/sonar-project.properties` | Configuration statique de l'analyse front : sources, fichiers de tests, chemin `lcov`, attente de la Quality Gate |
| `back/gradlew` | Ajout du droit d'exécution dans Git (`git update-index --chmod=+x`), indispensable sur les runners Linux |

**Exécuter les mêmes étapes en local** :

```shell
# Back-end : build + tests + couverture (rapport : back/build/reports/jacoco/test/html/index.html)
cd back
./gradlew build jacocoTestReport

# Front-end : build + tests + couverture (rapport : front/coverage/microcrm/index.html)
cd front
npm ci
npx ng build
npx ng test --watch=false --browsers=ChromeHeadlessNoSandbox --code-coverage
```

**Adapter le pipeline** : les versions de Java et de Node sont centralisées dans le bloc `env:` du workflow (`JAVA_VERSION`, `NODE_VERSION`). Les seuils de qualité se règlent dans la Quality Gate de SonarQube Cloud, sans modifier le workflow.

### 2.3 Reproductibilité et gestion des secrets

**Configuration requise (une seule fois)**

1. **SonarQube Cloud** (https://sonarcloud.io) : se connecter avec GitHub, importer l'organisation, puis analyser le dépôt en mode **monorepo** en créant deux projets (back et front).
2. Dans chaque projet SonarQube : *Administration → Analysis Method* → **désactiver l'« Automatic Analysis »** (incompatible avec l'analyse par la CI).
3. Générer un jeton : *My Account → Security → Generate Token*.
4. Dans GitHub, *Settings → Secrets and variables → Actions* :

| Type | Nom | Valeur |
|---|---|---|
| **Secret** | `SONAR_TOKEN` | Jeton SonarQube Cloud |
| Variable | `SONAR_ORGANIZATION` | Clé de l'organisation SonarQube Cloud |
| Variable | `SONAR_PROJECT_KEY_BACK` | Clé du projet back |
| Variable | `SONAR_PROJECT_KEY_FRONT` | Clé du projet front |

5. Recommandé : *Settings → Branches* → protéger `main` (fusion par pull request uniquement, checks `Back-end` et `Front-end` obligatoires).

**Gestion des secrets (sans jamais les afficher)**

- Le seul secret, `SONAR_TOKEN`, est stocké dans **GitHub Secrets** et transmis aux étapes via une variable d'environnement. GitHub le **masque automatiquement** dans les logs ; il n'apparaît dans aucun fichier du dépôt.
- Les identifiants non sensibles (organisation, clés de projet) sont des **variables GitHub** (`vars.*`) : ils peuvent changer sans modifier le code.
- Les pull requests venant de forks ne reçoivent pas les secrets : l'étape SonarQube est alors **ignorée** (`if: env.SONAR_TOKEN != ''`), mais le build et les tests s'exécutent normalement.
- Le workflow s'exécute avec des **permissions minimales** (`contents: read`).

**Relancer le pipeline**

- Automatiquement à chaque push ou pull request.
- Manuellement : onglet *Actions* → *CI* → *Run workflow* (déclencheur `workflow_dispatch`).
- Pour relancer un run échoué : bouton *Re-run jobs* de la page du run.

**Consulter les résultats**

- **Statut** de chaque job dans l'onglet *Actions* et sur la pull request (checks).
- **Logs détaillés** de chaque test (plugin `test-logger` côté back, Karma côté front).
- **Artefacts** `back-reports` (rapports JUnit et JaCoCo) et `front-coverage` (rapport de couverture HTML), téléchargeables depuis la page du run.
- **Tableau de bord SonarQube Cloud** : bugs, vulnérabilités, security hotspots, code smells, couverture, duplications, statut de la Quality Gate, et commentaire automatique sur les pull requests.

---

## 3. Plan de conteneurisation et de déploiement

### 3.1 Rôle des Dockerfiles existants

Le dépôt contient un **Dockerfile unique à la racine**, en *multi-stage build* : les étages de compilation (qui contiennent Node, Gradle, le JDK et les sources) sont séparés des étages d'exécution, qui ne contiennent que le strict nécessaire. Les images finales sont ainsi plus légères et exposent une surface d'attaque réduite.

| Étage | Rôle | Image de base actuelle |
|---|---|---|
| `front-build` | `npm ci` puis build Angular optimisé | `node` (non versionnée) |
| `back-build` | Compilation et tests Gradle, production du JAR | `gradle:jdk17` |
| `front` | **Image d'exécution du front** : fichiers statiques servis par Caddy (ports 80/443, fallback SPA vers `index.html`) | `alpine:3.19` + Caddy |
| `back` | **Image d'exécution de l'API** : JAR exécuté sur un JRE 21 (port 8080) | `alpine:3.19` + OpenJDK 21 JRE |
| `standalone` | Front et back dans un même conteneur, pilotés par supervisord | `alpine:3.19` + supervisor |

**Principes retenus pour adapter les Dockerfiles** (sans changer l'architecture existante) :

- **Versions figées** des images de base (Node LTS compatible, Gradle/JDK, Alpine maintenue) pour des builds reproductibles.
- **Correction des ports** : `EXPOSE 8080` pour le back.
- **Pas de double exécution des tests** : la CI exécute les tests ; le build Docker compile seulement (`-x test`).
- **Exécution en utilisateur non-root** dans les images d'exécution.
- **`HEALTHCHECK`** sur chaque image pour permettre à Docker Compose de vérifier l'état réel des services.
- **`.dockerignore`** maintenu pour exclure `node_modules`, `build`, `dist` et les caches du contexte de build.
- Les cibles `front` et `back` sont utilisées par Docker Compose ; la cible `standalone` est conservée pour une démonstration rapide, mais elle n'est pas déployée par le pipeline (un processus par conteneur).

### 3.2 Rôle de docker-compose

Le fichier `docker-compose.yml` (à créer) orchestre l'application complète sur un seul hôte, avec une seule commande :

| Service | Image (cible Dockerfile) | Ports | Particularités |
|---|---|---|---|
| `back` | `back` | `8080:8080` | Healthcheck sur l'API (`/persons`) |
| `front` | `front` | `80:80`, `443:443` | `depends_on: back` avec `condition: service_healthy` |

- **Pas de service de base de données** : HSQLDB est embarquée en mémoire dans le back. L'ajout d'une base persistante (PostgreSQL + volume) est identifié comme une évolution (voir plan de sauvegarde).
- Le port 8080 doit rester publié sur l'hôte, car le front appelle `http://localhost:8080` depuis le navigateur de l'utilisateur.
- Usages :
  - **en local** : `docker compose up --build` pour construire et lancer l'application ;
  - **en CI** : démarrage de la pile puis *smoke test* (l'API répond, le front est servi) pour valider que les conteneurs démarrent réellement ;
  - **en déploiement** : `docker compose pull && docker compose up -d` à partir des images publiées.

### 3.3 Stratégie de déploiement envisagée

1. **Publication des images** sur **GitHub Container Registry (GHCR)**, authentifiée avec le `GITHUB_TOKEN` du workflow (aucun secret supplémentaire) :
   - `ghcr.io/<owner>/microcrm-back` et `ghcr.io/<owner>/microcrm-front` ;
   - tag `sha-<commit>` à chaque push sur `main`, plus `latest` ;
   - tag de version `vX.Y.Z` lors de la création d'un tag Git (release).
2. **Conditions de publication** : uniquement depuis `main` ou un tag, et seulement si les tests, la Quality Gate SonarQube et le smoke test Docker Compose sont au vert. Les pull requests construisent les images sans les publier.
3. **Déploiement automatisé** : un job de déploiement récupère les images publiées et relance la pile via Docker Compose sur l'environnement cible, puis exécute un smoke test. Pour ce projet, la cible est un environnement local ou de démonstration ; un déploiement cloud reste optionnel.
4. **Environnement GitHub `production`** avec approbation manuelle possible avant le déploiement d'une release.
5. **Retour arrière** : redéployer le tag d'image précédent (`IMAGE_TAG=<version précédente> docker compose up -d`). Les images étant immuables et versionnées, le rollback ne nécessite aucune reconstruction.

---

## 4. Plan de testing périodique

### 4.1 Types de tests automatisés

| Type | Périmètre | Outil | Existant |
|---|---|---|---|
| Tests unitaires et de composants front | Composants et services Angular | Karma + Jasmine (Chrome Headless) | 8 tests |
| Test de démarrage back | Chargement du contexte Spring | JUnit 5 / `@SpringBootTest` | 1 test |
| Tests d'intégration back | Repositories JPA sur base en mémoire | JUnit 5 / `@DataJpaTest` | 1 test |
| Couverture de code | Back et front | JaCoCo (back), karma-coverage `lcov` (front) | Front seulement, non exporté |
| Analyse statique qualité et sécurité | Tout le code | SonarQube Cloud | À mettre en place |
| Audit des dépendances | npm et Gradle | `npm audit`, Dependabot | À mettre en place |
| Scan des images Docker | Images `back` et `front` | Trivy | À mettre en place |
| Smoke test de déploiement | Pile Docker Compose | `docker compose up` + `curl` | À mettre en place |

### 4.2 Fréquence d'exécution : quels tests, à quel moment

| Déclencheur | Tests exécutés | Objectif |
|---|---|---|
| **Push sur toute branche** | Build back + tests back ; build front + tests front ; couverture et analyse SonarQube de la branche | Retour rapide au développeur (quelques minutes) |
| **Pull request vers `main`** | Tout ce qui précède + couverture + analyse SonarQube (Quality Gate) + build des images + smoke test Docker Compose | Bloquer la fusion de code qui casse, régresse ou dégrade la qualité |
| **Push sur `main` (après fusion)** | Mêmes contrôles que la PR + publication des images sur GHCR + déploiement | Garantir que `main` reste livrable et déployé |
| **Exécution périodique (nightly, cron quotidien à 2 h UTC)** | Suite complète sur `main` + `npm audit` + scan Trivy des images + analyse SonarQube | Détecter les nouvelles vulnérabilités publiées et les régressions liées à l'environnement, même sans modification du code |
| **Avant release (tag `v*`)** | Suite complète + Quality Gate + scan Trivy bloquant (critique/haute) | Ne livrer qu'une version vérifiée et versionnée |
| **Manuel (`workflow_dispatch`)** | Pipeline complet à la demande | Relancer après un incident ou un changement de configuration |

### 4.3 Critères de réussite et d'alerte

| Contrôle | Critère bloquant | Alerte (non bloquante) |
|---|---|---|
| Tests unitaires et d'intégration | 100 % des tests passent | – |
| Build back et front | Build réussi | Avertissements de budget Angular |
| Quality Gate SonarQube (« Sonar way », sur le **nouveau code**) | Aucun nouveau bug ni nouvelle vulnérabilité, security hotspots revus, couverture du nouveau code ≥ 80 %, duplication ≤ 3 % | Dégradation de la note globale |
| Smoke test Docker Compose | API et front répondent (HTTP 200) | – |
| Scan Trivy | Release : aucune vulnérabilité **critique** corrigeable | Vulnérabilités hautes : issue créée / notification |
| `npm audit` (nightly) | – | Vulnérabilité haute ou critique : notification |

En cas d'échec d'un job périodique, une notification est envoyée (e-mail GitHub sur échec de workflow) et une issue est ouverte pour tracer la correction.

> Choix réaliste : la Quality Gate s'applique au **nouveau code** uniquement. La couverture actuelle (~31 % côté front, non mesurée côté back) ne permettrait pas de passer un seuil global ; on empêche la dégradation tout en remboursant la dette progressivement.

### 4.4 Objectifs des tests

- **Validation fonctionnelle** : vérifier que l'API démarre et que la persistance fonctionne, que les composants Angular s'initialisent, et que la pile conteneurisée répond (smoke test).
- **Non-régression** : rejouer automatiquement l'ensemble des tests à chaque push et chaque PR ; aucune fusion sur `main` sans pipeline vert (branche protégée).
- **Qualité** : mesurer la couverture, la duplication et la maintenabilité avec SonarQube, et empêcher leur dégradation via la Quality Gate.
- **Vérification avant déploiement** : aucune image n'est publiée ni déployée si une étape précédente échoue.
- **Évolution prévue** : enrichir les tests existants, très limités (tests de l'API REST avec `MockMvc`, tests des services Angular avec `HttpTestingController`), afin d'augmenter progressivement la couverture.

---

## 5. Plan de sécurité

### 5.1 Rôle de l'analyse SonarQube Cloud

SonarQube Cloud est la **porte de qualité et de sécurité** du pipeline :

- il analyse le code back (via le plugin Gradle `org.sonarqube`) et le code front (via `SonarSource/sonarqube-scan-action`) à chaque pull request et à chaque push sur `main` ;
- il commente directement la pull request (décoration de PR) avec les problèmes introduits ;
- sa **Quality Gate** bloque le pipeline si le nouveau code n'atteint pas les critères définis (section 4.3) ;
- il fournit un historique de la dette technique et de la couverture, utilisé pour le suivi et le plan de remédiation.

**Types de problèmes surveillés**

| Catégorie Sonar | Ce qui est détecté | Exemples possibles dans MicroCRM |
|---|---|---|
| **Vulnérabilités** | Failles exploitables (injection, configuration dangereuse…) | – |
| **Security Hotspots** | Code sensible à revoir manuellement | CORS ouvert à `*` |
| **Bugs** (fiabilité) | Comportements erronés | `Optional.get()` sans vérification |
| **Code smells** (maintenabilité) | Dette technique, mauvaises pratiques | Dépendance dupliquée, injection de champs `final = null` |
| **Couverture** | Part du code exécutée par les tests | ~31 % front, non mesurée back |
| **Duplications** | Code copié-collé | Tests de composants similaires |

*Les résultats réels de la première analyse seront reportés ici (section 5.4).*

### 5.2 Bonnes pratiques OWASP appliquées au projet

| Risque OWASP Top 10 | Constat dans MicroCRM | Mesure prévue |
|---|---|---|
| A01 – Contrôle d'accès défaillant | API sans authentification (GET, POST, PATCH, DELETE publics) | Documenté comme risque ; authentification à prévoir (Spring Security) en évolution |
| A05 – Mauvaise configuration de sécurité | CORS `*`, conteneurs en `root` | Restreindre les origines CORS, utilisateur non-root dans les images |
| A06 – Composants vulnérables et obsolètes | Angular 17 et Spring Boot 3.2 hors support, 90 vulnérabilités npm | Dependabot, `npm audit`, scan Trivy, plan de mise à jour |
| A08 – Intégrité des logiciels et des données | Images de base non versionnées, chaîne CI à sécuriser | Images figées, actions épinglées, `npm ci` sur lockfile, validation du wrapper Gradle |
| A09 – Journalisation et surveillance insuffisantes | Pas de supervision | Logs des conteneurs, notifications d'échec du pipeline |

### 5.3 Bonnes pratiques attendues dans la CI

**Gestion des secrets**

- Les secrets (`SONAR_TOKEN`) sont stockés dans **GitHub Secrets**, jamais dans le code, les Dockerfiles ou les logs.
- La publication sur GHCR utilise le **`GITHUB_TOKEN`** éphémère fourni par GitHub : aucun identifiant de registre à stocker.
- **Permissions minimales** déclarées dans chaque workflow (`permissions: contents: read` par défaut, `packages: write` uniquement pour le job de publication).
- Pas de déclencheur `pull_request_target` : le code des forks ne s'exécute pas avec les secrets.
- Le secret scanning et la push protection de GitHub sont activés sur le dépôt.

**Gestion des dépendances**

- Installation reproductible : `npm ci` (respect du `package-lock.json`), wrapper Gradle versionné et vérifié (`gradle/actions/wrapper-validation`).
- **Dependabot** pour npm, Gradle, les actions GitHub et les images Docker (mises à jour hebdomadaires sous forme de PR, donc testées par le pipeline).
- `npm audit` et scan Trivy des images (voir plan de testing).
- Actions GitHub épinglées sur une version majeure officielle (ou un SHA pour les actions tierces).

**Protection du dépôt**

- Branche `main` protégée : fusion uniquement par pull request, avec pipeline vert et Quality Gate validée.

### 5.4 Résultats SonarQube

*À compléter après la première analyse : vulnérabilités identifiées, code smells critiques, zones de complexité, couverture des tests.*

### 5.5 Analyse des risques

| Risque | Probabilité | Impact | Mesure |
|---|---|---|---|
| Fuite d'un secret dans le code ou les logs | Moyenne | Élevé | GitHub Secrets, masquage automatique, push protection |
| Dépendance vulnérable ou obsolète | **Élevée** (constatée) | Élevé | Dependabot, audit nightly, plan de mise à jour |
| Accès non autorisé à l'API | Élevée si exposée | Élevé | Usage interne uniquement ; authentification en évolution |
| Image Docker non reproductible ou vulnérable | Moyenne | Moyen | Versions figées, scan Trivy |
| Action GitHub compromise | Faible | Élevé | Actions officielles, versions épinglées, permissions minimales |
| Perte des données (base en mémoire) | **Certaine** à chaque redémarrage | Moyen | Base persistante + sauvegarde (section 7) |

### 5.6 Plan d'action et de remédiation

- **Actions immédiates** (avec la mise en place du pipeline) : secrets dans GitHub Secrets, permissions minimales, images de base figées, utilisateur non-root, Quality Gate active, Dependabot.
- **Court terme** : corriger les vulnérabilités npm et les problèmes bloquants remontés par SonarQube, restreindre le CORS, augmenter la couverture des tests.
- **Long terme** : migrer vers des versions supportées (Angular 20+, Spring Boot 4.x, Java 21/25, Gradle 9), ajouter une authentification sur l'API et une base persistante.

---

## 6. Monitoring, métriques & KPI

*À compléter : métriques DORA (Lead Time, Deployment Frequency, MTTR, Change Failure Rate), KPI (temps de build, temps des tests, taux d'erreurs), dashboards et alertes.*

---

## 7. Plan de sauvegarde des données

*À compléter : éléments à sauvegarder (données, configuration, artefacts de build), procédure de sauvegarde, procédure de restauration.*

---

## 8. Plan de mise à jour

*À compléter : mise à jour des dépendances npm/Gradle, d'Angular et de Spring Boot, des images Docker et des actions GitHub ; fréquence et bonnes pratiques.*

---

## 9. Conclusion

*À compléter.*

---

## Annexes

*À compléter : captures SonarQube, extraits de workflows, commandes utiles.*
