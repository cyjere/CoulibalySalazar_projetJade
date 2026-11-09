# Réservation de taxis BDI sous JADE

## Lancer
    ./run.sh                    # scénario nominal
    ./run.sh heure_de_pointe    # ou un autre scénario
Dans IntelliJ : lancer `Main` avec le nom du scénario en argument de programme.

## Scénarios (src/utils/Scenario.java)
| Scénario          | Ce qu'il montre |
|-------------------|-----------------|
| nominal           | 4 taxis, demande modérée, trafic variable : cycle complet des courses |
| heure_de_pointe   | 2 taxis pour une forte demande : relances d'appels d'offres, abandons |
| embouteillage     | trafic 6–9/9, clients pressés : annulation par le client et **abandon d'intention** par le taxi |
| carburant         | réservoirs presque vides : le désir FAIRE_LE_PLEIN passe avant les courses, refus « carburant » |
| budget_serre      | budgets justes : refus « trop cher », seuls les taxis proches peuvent répondre |
| concurrence       | scripté : 2 taxis, 3 clients simultanés ; Contract Net et réservations provisoires |

## Architecture BDI (src/bdi)
`BDIAgent` exécute à chaque tick : perception (messages → croyances) → génération des désirs →
reconsidération de l'intention courante → délibération (désir le plus prioritaire) → un pas du plan.

**Taxi** — croyances : position, carburant, station d'attache, phase de la course, client, prix, trafic.
Désirs : ENCAISSER (100) > TRANSPORTER (90) > ALLER_CHERCHER (80) > FAIRE_LE_PLEIN (70) >
ATTENDRE_DECISION (50) > SE_REPOSITIONNER (20) > ATTENDRE_CLIENT (10).

**Client** — croyances : position, destination, budget, patience, taxi réservé, phase.
Désirs : PAYER (100) > QUITTER (90) > ANNULER (60) > CHERCHER_TAXI (50) > ATTENDRE_TAXI / ARRIVER (40).

## Protocole
1. Client → taxis : CFP (départ, destination, budget) — FIPA Contract Net
2. Taxi → client : PROPOSE (prix = 4 € + 2 €/case de course + 1 €/case d'approche) ou REFUSE (indisponible, trop cher, carburant)
3. Client : ACCEPT au moins cher (puis au plus proche), REJECT aux autres ; taxi → INFORM « en route »
4. Taxi → client : PRISE_EN_CHARGE, puis ARRIVEE (prix) ; client → taxi : PAIEMENT
5. Client → taxi : CANCEL (ANNULATION) si l'attente dépasse sa patience
La ville (EnvironmentAgent) crée les clients et diffuse le trafic ; l'agent `display` affiche la carte.

`captures/` : une capture d'écran par scénario.
`videos/` : une vidéo de démonstration par scénario.
