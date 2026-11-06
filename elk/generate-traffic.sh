#!/bin/sh
# Génère du trafic sur MicroCRM pour alimenter le tableau de bord Kibana :
# requêtes normales (front et API), requêtes en erreur 404 (WARN) et 500 (ERROR).
# Usage : sh elk/generate-traffic.sh [nombre_d_iterations]   (défaut : 20)

ITERATIONS="${1:-20}"
API="http://localhost:8080"
FRONT="https://localhost"

i=1
while [ "$i" -le "$ITERATIONS" ]; do
  # Parcours normal : pages du front et appels API correspondants
  curl -sk -o /dev/null "$FRONT/"
  curl -sk -o /dev/null "$FRONT/persons/1"
  curl -s  -o /dev/null "$API/persons"
  curl -s  -o /dev/null "$API/organizations"
  curl -s  -o /dev/null "$API/persons/1"
  curl -s  -o /dev/null "$API/persons/1/organizations"

  # Ressource inexistante : 404 -> WARN
  if [ $((i % 3)) -eq 0 ]; then
    curl -s -o /dev/null "$API/persons/99999"
  fi

  # Identifiant invalide : 500 -> ERROR
  if [ $((i % 5)) -eq 0 ]; then
    curl -s -o /dev/null "$API/persons/abc"
  fi

  i=$((i + 1))
  sleep 1
done
echo "Trafic généré : $ITERATIONS itérations"
