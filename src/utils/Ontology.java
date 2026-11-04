package utils;

/**
 * Vocabulaire partagé par tous les agents + paramètres de la ville.
 *
 * Formats des messages (champs séparés par ';') :
 *   CFP             client -> taxis   : px;py;dx;dy;budget
 *   PROPOSE         taxi   -> client  : prix;distApproche;distCourse
 *   REFUSE          taxi   -> client  : indisponible | trop-cher;prix | carburant
 *   PRISE_EN_CHARGE taxi   -> client  : x;y
 *   ARRIVEE         taxi   -> client  : x;y;prix
 *   PAIEMENT        client -> taxi    : montant
 *   ANNULATION      client -> taxi    : raison         (performatif CANCEL)
 *   TRAFIC          ville  -> taxis   : niveau (0..9)
 *   LOG             *      -> display : texte libre
 */
public class Ontology {
    // Services DF
    public static final String SERVICE_TAXI    = "taxi-service";
    public static final String SERVICE_CLIENT  = "client-service";

    // Ontologies des messages
    public static final String CFP_COURSE      = "cfp-course";
    public static final String PRISE_EN_CHARGE = "prise-en-charge";
    public static final String ARRIVEE         = "arrivee";
    public static final String PAIEMENT        = "paiement";
    public static final String ANNULATION      = "annulation";
    public static final String TRAFIC          = "trafic";
    public static final String LOG             = "log";
    public static final String ETAT_QUERY      = "etat?";
    public static final String ETAT_RESPONSE   = "etat!";

    // Nom local de l'agent d'affichage
    public static final String DISPLAY         = "display";

    // Ville (grille)
    public static final int GRILLE_W = 20;
    public static final int GRILLE_H = 15;

    // Station-service
    public static final int STATION_X = 10;
    public static final int STATION_Y = 7;

    // Carburant (1 unité par case parcourue)
    public static final int RESERVOIR   = 100;
    public static final int SEUIL_PLEIN = 25;   // en dessous : désir FAIRE_LE_PLEIN
    public static final int DEBIT_POMPE = 15;   // unités ajoutées par tick à la station

    // Tarification : prix = PRISE_EN_CHARGE_EUR + TARIF_CASE * distCourse + TARIF_APPROCHE * distApproche
    public static final int PRISE_EN_CHARGE_EUR = 4;
    public static final int TARIF_CASE          = 2;
    public static final int TARIF_APPROCHE      = 1;

    public static int distance(int x1, int y1, int x2, int y2) {
        return Math.abs(x1 - x2) + Math.abs(y1 - y2);   // ville en damier (Manhattan)
    }

    public static int prix(int distApproche, int distCourse) {
        return PRISE_EN_CHARGE_EUR + TARIF_CASE * distCourse + TARIF_APPROCHE * distApproche;
    }
}
