<p align="center">
   <img src="./front/src/favicon.png" width="192px" />
</p>

# MicroCRM (P7 - Développeur Full-Stack - Java et Angular - Mettez en œuvre l'intégration et le déploiement continu d'une application Full-Stack)

[![CI](https://github.com/brunoHoarau/Hoarau_Bruno_09_Gerez_un_projet_d-integration_et_de_deploiement_continu/actions/workflows/ci.yml/badge.svg)](https://github.com/brunoHoarau/Hoarau_Bruno_09_Gerez_un_projet_d-integration_et_de_deploiement_continu/actions/workflows/ci.yml)

MicroCRM est une application de démonstration basique ayant pour être objectif de servir de socle pour le module "P7 - Développeur Full-Stack".

L'application MicroCRM est une implémentation simplifiée d'un ["CRM" (Customer Relationship Management)](https://fr.wikipedia.org/wiki/Gestion_de_la_relation_client). Les fonctionnalités sont limitées à la création, édition et la visualisations des individus liés à des organisations.

![Page d'accueil](./misc/screenshots/screenshot_1.png)
![Édition de la fiche d'un individu](./misc/screenshots/screenshot_2.png)

**Sommaire** : [Organisation](#organisation) · [Prérequis](#prérequis) · [Démarrer avec les sources](#démarrer-avec-les-sources) · [Tests](#exécution-des-tests) · [Docker](#conteneurs-docker) · [Pipeline CI/CD](#pipeline-cicd) · [Configuration](#configuration-du-pipeline) · [Déploiement](#déploiement) · [Logs (ELK)](#centralisation-des-logs-elk) · [Accès aux outils](#accès-aux-outils)

## Organisation

Ce [monorepo](https://en.wikipedia.org/wiki/Monorepo) contient :

| Chemin | Contenu |
|---|---|
| `back/` | API REST en Java Spring Boot 3 (Gradle) |
| `front/` | Application Angular 17 (npm) |
| `Dockerfile` | Build multi-stage des images `back` et `front` |
| `docker-compose.yml` | Orchestration de l'application (local, CI, déploiement) |
| `docker-compose.elk.yml`, `elk/` | Stack ELK de centralisation des logs (local) |
| `.github/workflows/ci.yml` | Pipeline CI/CD GitHub Actions |
| `.github/dependabot.yml` | Mises à jour automatiques des dépendances |
| `misc/docker/Caddyfile` | Configuration du serveur web du front |
| `docs/` | Documentation technique et captures |

## Prérequis

| Usage | Outils |
|---|---|
| Lancer l'application en conteneurs | [Docker Desktop](https://www.docker.com/products/docker-desktop/) ou Docker Engine ≥ 24 avec Docker Compose v2 |
| Développer le back-end | [Java 21](https://adoptium.net/) (Eclipse Temurin) ; Gradle est fourni par le wrapper |
| Développer le front-end | [Node.js 24 LTS](https://nodejs.org/) (npm inclus) |
| Tests du front | Google Chrome ou Chromium |
| Stack ELK | 4 Go de RAM disponibles pour Docker |

## Démarrer avec les sources

### Serveur

```shell
cd back
./gradlew build                 # Windows : gradlew.bat build
java -jar build/libs/microcrm-0.0.1-SNAPSHOT.jar
```

Puis ouvrir http://localhost:8080 dans votre navigateur.

### Client

```shell
cd front
npm ci                          # installe les dépendances exactes du package-lock.json
npx ng serve
```

Puis ouvrir http://localhost:4200 dans votre navigateur.

## Exécution des tests

### Serveur

```shell
cd back
./gradlew test jacocoTestReport
```

Rapport de couverture : `back/build/reports/jacoco/test/html/index.html`.

### Client

```shell
cd front
npx ng test --watch=false --browsers=ChromeHeadless --code-coverage
```

Rapport de couverture : `front/coverage/microcrm/index.html`. Si Chrome n'est pas trouvé, indiquer son chemin avec la variable d'environnement `CHROME_BIN`.

## Conteneurs Docker

L'application se lance avec [Docker Compose](https://docs.docker.com/compose/) :

```shell
docker compose up --build
```

- Front-end : https://localhost (certificat auto-signé généré par Caddy ; http://localhost redirige vers HTTPS)
- API : http://localhost:8080

Arrêter l'application :

```shell
docker compose down
```

Les deux services démarrent dans l'ordre : le front n'est lancé qu'une fois l'API déclarée « healthy ».

Les images peuvent aussi être construites séparément à partir du `Dockerfile` multi-stage :

```shell
docker build --target back  -t microcrm-back  .
docker build --target front -t microcrm-front .
```

## Pipeline CI/CD

Le workflow [`.github/workflows/ci.yml`](.github/workflows/ci.yml) automatise toute la chaîne :

| Job | Contenu | Exécution |
|---|---|---|
| **Back-end** | Build Gradle, tests JUnit, couverture JaCoCo, analyse SonarQube Cloud (Quality Gate bloquante) | À chaque run |
| **Front-end** | `npm ci`, build Angular, tests Karma, couverture, `npm audit`, analyse SonarQube Cloud | À chaque run, en parallèle du back |
| **Images Docker** | `docker compose build`, démarrage et smoke test, scan Trivy, publication sur GitHub Container Registry | Si back et front réussissent ; publication uniquement sur `main` et les tags `v*` |
| **Déploiement** | `docker compose pull` puis `up`, vérification | Sur `main` et les tags `v*` |

Déclencheurs : push sur toute branche, pull request vers `main`, tag `v*`, exécution quotidienne à 2 h UTC et lancement manuel (*Actions → CI → Run workflow*).

## Configuration du pipeline

À faire une seule fois, sur un nouveau dépôt :

1. **SonarQube Cloud** (https://sonarcloud.io) : se connecter avec GitHub, puis importer le dépôt en mode **monorepo** avec deux projets (back et front). Dans chaque projet, vérifier que *Administration → Analysis Method → Automatic Analysis* est **désactivé**. Générer un jeton dans *My Account → Security*.
2. **GitHub**, *Settings → Secrets and variables → Actions* :

   | Onglet | Nom | Valeur |
   |---|---|---|
   | Secrets | `SONAR_TOKEN` | Jeton SonarQube Cloud (jamais dans le code) |
   | Variables | `SONAR_ORGANIZATION` | Clé de l'organisation SonarQube Cloud |
   | Variables | `SONAR_PROJECT_KEY_BACK` | Clé du projet back |
   | Variables | `SONAR_PROJECT_KEY_FRONT` | Clé du projet front |

3. Recommandé :
   - protéger la branche `main` (*Settings → Branches* : fusion par pull request, checks obligatoires) ;
   - activer la push protection du secret scanning (*Settings → Code security*).

La publication des images utilise le jeton éphémère `GITHUB_TOKEN` : aucun autre secret n'est nécessaire. Si un paquet `microcrm-back` ou `microcrm-front` existe déjà sur le compte GHCR depuis un autre dépôt, il faut au choix :
- donner à ce dépôt le droit d'écriture (*Package settings → Manage Actions access*) ;
- supprimer le paquet.

## Déploiement

| Mode | Procédure |
|---|---|
| **Automatique** | Fusionner sur `main` : le pipeline teste, publie les images `sha-<commit>` et `latest`, puis déploie |
| **Release** | `git tag v1.0.0 && git push origin v1.0.0` : images publiées avec le tag `v1.0.0`, puis déploiement |
| **Manuel sur un serveur** | Copier `docker-compose.yml` sur un hôte disposant de Docker, puis lancer les commandes ci-dessous |

```shell
IMAGE_TAG=v1.0.0 docker compose pull
IMAGE_TAG=v1.0.0 docker compose up -d --no-build --wait
docker compose ps        # les deux conteneurs doivent être "healthy"
```

Pour revenir à une version précédente, relancer les mêmes commandes avec le tag précédent. La liste des versions est dans l'onglet *Packages* du dépôt.

## Centralisation des logs (ELK)

Une stack Elasticsearch / Logstash / Kibana 8.19 centralise les logs du back-end et du front-end (usage local uniquement, environ 4 Go de RAM) :

```shell
docker compose -f docker-compose.yml -f docker-compose.elk.yml up -d --build
sh elk/generate-traffic.sh 30        # trafic de démonstration (optionnel)
```

Dans Kibana (http://localhost:5601), ouvrir *Tableaux de bord*, puis **MicroCRM - Logs applicatifs**.

## Accès aux outils

| Outil | Adresse |
|---|---|
| Pipeline (GitHub Actions) | https://github.com/brunoHoarau/Hoarau_Bruno_09_Gerez_un_projet_d-integration_et_de_deploiement_continu/actions |
| Historique des déploiements | https://github.com/brunoHoarau/Hoarau_Bruno_09_Gerez_un_projet_d-integration_et_de_deploiement_continu/deployments |
| Images Docker (GHCR) | https://github.com/brunoHoarau?tab=packages |
| SonarQube Cloud – organisation | https://sonarcloud.io/organizations/brunohoarau/projects |
| SonarQube Cloud – back-end | https://sonarcloud.io/summary/overall?id=brunoHoarau_Hoarau_Bruno_09_Gerez_un_projet_d-integration_et_de_deploiement_continu |
| SonarQube Cloud – front-end | https://sonarcloud.io/summary/overall?id=brunoHoarau_microcrm-front |
| Application – front (local) | https://localhost |
| Application – API (local) | http://localhost:8080 (ex. http://localhost:8080/persons) |
| Kibana (local, avec ELK) | http://localhost:5601 ; tableau de bord : http://localhost:5601/app/dashboards#/view/microcrm-logs-dashboard |
| Elasticsearch (local, avec ELK) | http://localhost:9200 (ex. http://localhost:9200/_cat/indices?v) |

Les adresses locales ne répondent que lorsque l'application est lancée (et, pour Kibana et Elasticsearch, la stack ELK). Elles ne sont accessibles que depuis la machine elle-même.
