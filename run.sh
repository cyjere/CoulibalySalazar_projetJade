#!/bin/bash
# Usage : ./run.sh [scenario]
# Scénarios : nominal (défaut), heure_de_pointe, embouteillage, carburant, budget_serre, concurrence
cd "$(dirname "$0")"
rm -rf classes
mkdir -p classes
javac -encoding UTF-8 -cp "lib/*" -d classes $(find src -name "*.java") || exit 1
java -cp "lib/*:classes" Main "${1:-nominal}"
