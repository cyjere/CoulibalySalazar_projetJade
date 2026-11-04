package utils;

import java.util.*;

/**
 * Scénarios de simulation. Chacun fixe la flotte de taxis et les paramètres
 * de la ville (demande, trafic, budgets, patience des clients).
 *
 * Lancement : ./run.sh <nom>   (par défaut : nominal)
 */
public class Scenario {

    /** Taxi initial : nom, position, carburant. */
    public static class TaxiInit {
        public final String nom; public final int x, y, carburant;
        TaxiInit(String nom, int x, int y, int carburant) {
            this.nom = nom; this.x = x; this.y = y; this.carburant = carburant;
        }
    }

    /** Client scripté : départ, destination, budget, patience (s), délai d'apparition (ms). */
    public static class ClientInit {
        public final int px, py, dx, dy, budget, patience; public final long delai;
        ClientInit(int px, int py, int dx, int dy, int budget, int patience, long delai) {
            this.px = px; this.py = py; this.dx = dx; this.dy = dy;
            this.budget = budget; this.patience = patience; this.delai = delai;
        }
    }

    public String nom, description;
    public final List<TaxiInit> taxis = new ArrayList<>();
    public final List<ClientInit> clientsScriptes = new ArrayList<>();

    // Génération aléatoire de clients (maxClients = 0 : désactivée)
    public int  maxClients = 5;
    public long periodeApparition = 4000;
    public int  clientsInitiaux = 2;
    public int  margeBudgetMin = 8, margeBudgetMax = 25;   // € au-dessus du prix de la course seule
    public int  patienceMin = 25, patienceMax = 45;        // s d'attente du taxi avant annulation
    public int  maxTentatives = 6;                         // appels d'offres avant abandon

    // Trafic : marche aléatoire bornée dans [traficMin, traficMax]
    public int traficMin = 0, traficMax = 9, traficInitial = 2;

    private Scenario taxi(String nom, int x, int y, int carburant) {
        taxis.add(new TaxiInit(nom, x, y, carburant)); return this;
    }

    private static final Map<String, Scenario> TOUS = new LinkedHashMap<>();

    static {
        Scenario s;

        s = new Scenario(); s.nom = "nominal";
        s.description = "4 taxis, demande modérée, trafic variable";
        s.taxi("Taxi1", 3, 3, 90).taxi("Taxi2", 16, 3, 70).taxi("Taxi3", 9, 11, 100).taxi("Taxi4", 2, 13, 60);
        TOUS.put(s.nom, s);

        s = new Scenario(); s.nom = "heure_de_pointe";
        s.description = "2 taxis pour une forte demande : files d'attente, relances, abandons";
        s.taxi("Taxi1", 5, 5, 100).taxi("Taxi2", 14, 9, 100);
        s.maxClients = 10; s.periodeApparition = 1500; s.clientsInitiaux = 4; s.maxTentatives = 8;
        TOUS.put(s.nom, s);

        s = new Scenario(); s.nom = "embouteillage";
        s.description = "Trafic saturé (6 à 9/9) et clients pressés : retards et annulations";
        s.taxi("Taxi1", 3, 3, 100).taxi("Taxi2", 16, 3, 100).taxi("Taxi3", 9, 11, 100).taxi("Taxi4", 2, 13, 100);
        s.traficMin = 6; s.traficMax = 9; s.traficInitial = 8;
        s.patienceMin = 4; s.patienceMax = 9;
        TOUS.put(s.nom, s);

        s = new Scenario(); s.nom = "carburant";
        s.description = "Réservoirs presque vides : le désir FAIRE_LE_PLEIN entre en concurrence avec les courses";
        s.taxi("Taxi1", 1, 1, 22).taxi("Taxi2", 18, 2, 40).taxi("Taxi3", 3, 12, 30).taxi("Taxi4", 17, 13, 18);
        TOUS.put(s.nom, s);

        s = new Scenario(); s.nom = "budget_serre";
        s.description = "Clients au budget juste : refus « trop cher » et choix du taxi le plus proche";
        s.taxi("Taxi1", 3, 3, 100).taxi("Taxi2", 16, 3, 100).taxi("Taxi3", 9, 11, 100).taxi("Taxi4", 2, 13, 100);
        s.margeBudgetMin = 2; s.margeBudgetMax = 10;
        TOUS.put(s.nom, s);

        s = new Scenario(); s.nom = "concurrence";
        s.description = "Scénario scripté : 2 taxis, 3 clients qui appellent presque en même temps";
        s.taxi("Taxi1", 4, 7, 100).taxi("Taxi2", 15, 7, 100);
        s.maxClients = 0; s.clientsInitiaux = 0; s.maxTentatives = 15;
        s.traficMin = 0; s.traficMax = 0; s.traficInitial = 0;
        s.clientsScriptes.add(new ClientInit(6, 7, 6, 1, 40, 60, 0));     // proche de Taxi1
        s.clientsScriptes.add(new ClientInit(13, 7, 17, 13, 40, 60, 0));  // proche de Taxi2
        s.clientsScriptes.add(new ClientInit(9, 7, 1, 12, 60, 90, 300));  // arrive juste après : doit attendre
        TOUS.put(s.nom, s);
    }

    public static Scenario get(String nom) {
        Scenario s = TOUS.get(nom == null ? "nominal" : nom);
        if (s == null) throw new IllegalArgumentException("Scénario inconnu : " + nom + " — disponibles : " + TOUS.keySet());
        return s;
    }

    public static Collection<Scenario> tous() { return TOUS.values(); }
}
