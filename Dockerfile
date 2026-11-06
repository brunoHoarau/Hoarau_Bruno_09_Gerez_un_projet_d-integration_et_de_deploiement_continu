# syntax=docker/dockerfile:1
#
# Build multi-stage de MicroCRM.
#   - cible "front" : application Angular servie par Caddy
#   - cible "back"  : API Spring Boot sur JRE 21
# Images de base : images officielles Docker, variantes Alpine minimales, versions figées
# (mises à jour proposées par Dependabot). Voir docs/documentation-technique.md, section 3.

# ---------------------------------------------------------------------------
# Étape de build du front-end (Node.js LTS)
# ---------------------------------------------------------------------------
FROM node:24.22-alpine3.24 AS front-build

ENV NG_CLI_ANALYTICS=false

WORKDIR /src

# Dépendances d'abord : couche mise en cache tant que package*.json ne change pas
COPY front/package.json front/package-lock.json ./
RUN --mount=type=cache,target=/root/.npm npm ci

COPY front/ ./
RUN npx ng build --configuration production

# ---------------------------------------------------------------------------
# Étape de build du back-end (JDK 21)
# ---------------------------------------------------------------------------
FROM eclipse-temurin:21-jdk-alpine-3.24 AS back-build

WORKDIR /src

# Wrapper et scripts Gradle d'abord : couche mise en cache tant qu'ils ne changent pas
COPY back/gradlew back/settings.gradle back/build.gradle ./
COPY back/gradle ./gradle
RUN chmod +x gradlew

COPY back/src ./src
# Les tests sont exécutés par la CI : le build d'image produit uniquement le JAR exécutable
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew bootJar --no-daemon \
    && cp build/libs/microcrm-0.0.1-SNAPSHOT.jar /src/app.jar

# ---------------------------------------------------------------------------
# Image d'exécution du front-end : fichiers statiques servis par Caddy
# ---------------------------------------------------------------------------
FROM caddy:2.11-alpine AS front

# Utilisateur non-root ; le binaire caddy possède la capacité de lier les ports 80/443
RUN addgroup -S app && adduser -S -G app app \
    && mkdir -p /data /config \
    && chown -R app:app /data /config

COPY misc/docker/Caddyfile /etc/caddy/Caddyfile
COPY --from=front-build /src/dist/microcrm/browser /app/front

USER app

EXPOSE 80 443

HEALTHCHECK --interval=10s --timeout=3s --start-period=10s --retries=3 \
    CMD wget -q --spider http://127.0.0.1:2019/config/ || exit 1

CMD ["caddy", "run", "--config", "/etc/caddy/Caddyfile", "--adapter", "caddyfile"]

# ---------------------------------------------------------------------------
# Image d'exécution du back-end : JRE seul (pas de JDK ni de Gradle)
# ---------------------------------------------------------------------------
FROM eclipse-temurin:21-jre-alpine-3.24 AS back

RUN addgroup -S app && adduser -S -G app app

WORKDIR /app
COPY --from=back-build --chown=app:app /src/app.jar /app/app.jar

USER app

# La JVM adapte sa mémoire aux limites du conteneur
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75"

EXPOSE 8080

HEALTHCHECK --interval=10s --timeout=3s --start-period=40s --retries=5 \
    CMD wget -q -O /dev/null http://127.0.0.1:8080/ || exit 1

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
