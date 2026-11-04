package agents;

import bdi.*;
import jade.core.AID;
import jade.core.behaviours.CyclicBehaviour;
import jade.domain.DFService;
import jade.domain.FIPAAgentManagement.*;
import jade.domain.FIPAException;
import jade.lang.acl.*;
import jade.proto.ContractNetResponder;
import utils.Journal;
import utils.Ontology;

import java.util.*;

/**
 * Taxi BDI.
 *
 * CROYANCES (bb) : x, y, carburant, solde, courses, congestion, homeX/homeY (station d'attache),
 *   phase de la course (null | reserve | engage | a_bord | paiement), client, pickup, destination, prix.
 *
 * DÉSIRS (priorité) générés par genererDesirs() :
 *   ENCAISSER (100)        si phase = paiement
 *   TRANSPORTER (90)       si phase = a_bord
 *   ALLER_CHERCHER (80)    si phase = engage
 *   FAIRE_LE_PLEIN (70)    si libre et carburant < SEUIL_PLEIN (jusqu'au plein)
 *   ATTENDRE_DECISION (50) si phase = reserve (offre envoyée, réponse attendue)
 *   SE_REPOSITIONNER (20)  si libre et loin de sa station d'attache
 *   ATTENDRE_CLIENT (10)   sinon
 *
 * INTENTIONS / PLANS : executer() fait avancer d'un pas le plan de l'intention courante
 *   (se déplacer d'une case, prendre en charge, déposer, faire le plein...).
 *
 * Les messages (CFP, ACCEPT, PAIEMENT, ANNULATION, TRAFIC) ne font que réviser les croyances ;
 * c'est le cycle BDI qui en tire les conséquences.
 */
public class TaxiAgent extends BDIAgent {

    private static final int  TICK_MS             = 500;    // 1 case par tick hors bouchons
    private static final long TIMEOUT_RESERVATION = 5000;
    private static final long TIMEOUT_PAIEMENT    = 5000;
    private static final int  RAYON_ATTACHE       = 4;      // au-delà : désir SE_REPOSITIONNER
    private static final int  MARGE_CARBURANT     = 3;

    private final Random rnd = new Random();

    @Override
    protected void setup() {
        Object[] args = getArguments();
        if (args == null || args.length < 3) {
            System.err.println(getLocalName() + " : arguments manquants (nom,x,y[,carburant]) ! args="
                    + Arrays.toString(args));
            doDelete();
            return;
        }

        int x = Integer.parseInt(args[1].toString()), y = Integer.parseInt(args[2].toString());
        int carburant = args.length > 3 ? Integer.parseInt(args[3].toString()) : Ontology.RESERVOIR;

        bb.set("name", args[0].toString());
        bb.set("x", x);           bb.set("y", y);
        bb.set("homeX", x);       bb.set("homeY", y);
        bb.set("carburant", carburant);
        bb.set("besoinPlein", carburant < Ontology.SEUIL_PLEIN);
        bb.set("solde", 0);
        bb.set("courses", 0);
        bb.set("congestion", 0);

        DFAgentDescription dfd = new DFAgentDescription();
        dfd.setName(getAID());
        ServiceDescription sd = new ServiceDescription();
        sd.setType(Ontology.SERVICE_TAXI);
        sd.setName("TaxiService-" + getLocalName());
        dfd.addServices(sd);
        try { DFService.register(this, dfd); } catch (FIPAException e) { e.printStackTrace(); }

        Journal.log(this, "en service en (" + x + "," + y + "), carburant " + carburant + "%");

        addBehaviour(new ResponderCourse());         // Contract Net
        addBehaviour(new PerceptionMessages());      // PAIEMENT, ANNULATION, TRAFIC
        addBehaviour(new ReponseDisplay());
        demarrerCycleBDI(TICK_MS);
    }

    // =====================================================================
    // 1. Génération des désirs à partir des croyances
    // =====================================================================
    @Override
    protected List<Desire> genererDesirs() {
        List<Desire> d = new ArrayList<>();
        String phase = phase();
        String client = (String) bb.get("client");

        if ("paiement".equals(phase))      d.add(new Desire(Desire.Type.ENCAISSER, 100, client));
        else if ("a_bord".equals(phase))   d.add(new Desire(Desire.Type.TRANSPORTER, 90, client));
        else if ("engage".equals(phase))   d.add(new Desire(Desire.Type.ALLER_CHERCHER, 80, client));
        else if ("reserve".equals(phase))  d.add(new Desire(Desire.Type.ATTENDRE_DECISION, 50,
                                                            bb.get("clientPotentiel")));
        else {
            if (i("carburant") < Ontology.SEUIL_PLEIN) bb.set("besoinPlein", true);   // révision
            if (Boolean.TRUE.equals(bb.get("besoinPlein")))
                d.add(new Desire(Desire.Type.FAIRE_LE_PLEIN, 70));
            if (Ontology.distance(i("x"), i("y"), i("homeX"), i("homeY")) > RAYON_ATTACHE)
                d.add(new Desire(Desire.Type.SE_REPOSITIONNER, 20));
            d.add(new Desire(Desire.Type.ATTENDRE_CLIENT, 10));
        }
        return d;
    }

    // =====================================================================
    // 2. Plans : un pas d'exécution par tick
    // =====================================================================
    @Override
    protected boolean executer(Intention it) {
        switch (it.desire.type) {
            case ALLER_CHERCHER:
                if (allerVers(i("pickupX"), i("pickupY"))) { priseEnCharge(); return true; }
                return false;

            case TRANSPORTER:
                if (allerVers(i("destX"), i("destY"))) { deposerClient(); return true; }
                return false;

            case ENCAISSER: {
                Long t = (Long) bb.get("paiementDepuis");
                if (t != null && System.currentTimeMillis() - t > TIMEOUT_PAIEMENT) {
                    Journal.log(this, bb.get("client") + " n'a pas payé !");
                    oublierCourse();
                    return true;
                }
                return false;   // le PAIEMENT reçu marque l'intention comme atteinte
            }

            case ATTENDRE_DECISION: {
                Long t = (Long) bb.get("reserveDepuis");
                if (t != null && System.currentTimeMillis() - t > TIMEOUT_RESERVATION) {
                    Journal.log(this, "réservation expirée, redevient libre");
                    oublierCourse();
                    return true;
                }
                return false;
            }

            case FAIRE_LE_PLEIN:
                if (!allerVers(Ontology.STATION_X, Ontology.STATION_Y)) return false;
                int c = Math.min(Ontology.RESERVOIR, i("carburant") + Ontology.DEBIT_POMPE);
                bb.set("carburant", c);
                if (c >= Ontology.RESERVOIR) {
                    bb.set("besoinPlein", false);
                    Journal.log(this, "plein fait, de nouveau disponible");
                    return true;
                }
                return false;

            case SE_REPOSITIONNER:
                return allerVers(i("homeX"), i("homeY"));

            case ATTENDRE_CLIENT:
            default:
                return false;   // intention de fond, jamais « atteinte »
        }
    }

    @Override
    protected void intentionAdoptee(Intention nouvelle, Intention ancienne) {
        Desire.Type t = nouvelle.desire.type;
        if (ancienne != null && ancienne.desire.type == Desire.Type.SE_REPOSITIONNER
                && t != Desire.Type.ATTENDRE_CLIENT) {
            System.out.println("[" + getLocalName() + "] interrompt SE_REPOSITIONNER pour " + t);
        }
        if (t == Desire.Type.FAIRE_LE_PLEIN)
            Journal.log(this, "intention FAIRE_LE_PLEIN (carburant " + bb.get("carburant") + "%)");
        else if (t == Desire.Type.SE_REPOSITIONNER)
            Journal.log(this, "intention SE_REPOSITIONNER vers sa station (" + bb.get("homeX") + "," + bb.get("homeY") + ")");
    }

    @Override
    protected void intentionAbandonnee(Intention i) {
        if (i.desire.type == Desire.Type.ALLER_CHERCHER)
            Journal.log(this, "abandonne l'intention ALLER_CHERCHER " + i.desire.params[0]);
    }

    /** Avance d'une case vers (tx,ty). Renvoie true si l'agent est sur la cible. */
    private boolean allerVers(int tx, int ty) {
        int x = i("x"), y = i("y");
        bb.set("targetX", tx);
        bb.set("targetY", ty);
        if (x == tx && y == ty) return true;

        if (rnd.nextInt(20) < i("congestion")) return false;   // coincé dans les bouchons
        if (i("carburant") <= 0) return false;                 // panne sèche (ne devrait pas arriver)

        if (x != tx) x += Integer.signum(tx - x);
        else         y += Integer.signum(ty - y);
        bb.set("x", x);
        bb.set("y", y);
        bb.set("carburant", i("carburant") - 1);
        return x == tx && y == ty;
    }

    private void priseEnCharge() {
        String client = (String) bb.get("client");
        envoyer(client, Ontology.PRISE_EN_CHARGE, bb.get("x") + ";" + bb.get("y"));
        bb.set("phase", "a_bord");
        Journal.log(this, "prend en charge " + client + ", direction ("
                + bb.get("destX") + "," + bb.get("destY") + ")");
    }

    private void deposerClient() {
        String client = (String) bb.get("client");
        envoyer(client, Ontology.ARRIVEE, bb.get("x") + ";" + bb.get("y") + ";" + bb.get("prixCourse"));
        bb.set("phase", "paiement");
        bb.set("paiementDepuis", System.currentTimeMillis());
        Journal.log(this, "dépose " + client + " — " + bb.get("prixCourse") + " € à payer");
    }

    private void oublierCourse() {
        for (String k : new String[]{"phase", "client", "clientPotentiel", "pickupX", "pickupY",
                "destX", "destY", "prixCourse", "reserveDepuis", "paiementDepuis"})
            bb.remove(k);
        if (i("carburant") < Ontology.SEUIL_PLEIN) bb.set("besoinPlein", true);
    }

    // =====================================================================
    // Perception : messages -> révision des croyances
    // =====================================================================
    private class PerceptionMessages extends CyclicBehaviour {
        private final MessageTemplate mt = MessageTemplate.or(
                MessageTemplate.MatchOntology(Ontology.PAIEMENT),
                MessageTemplate.or(MessageTemplate.MatchOntology(Ontology.TRAFIC),
                                   MessageTemplate.MatchOntology(Ontology.ANNULATION)));

        @Override
        public void action() {
            ACLMessage m = receive(mt);
            if (m == null) { block(); return; }
            String emetteur = m.getSender().getLocalName();

            switch (m.getOntology()) {
                case Ontology.TRAFIC:
                    bb.set("congestion", Integer.parseInt(m.getContent().trim()));
                    break;

                case Ontology.PAIEMENT:
                    if (!"paiement".equals(phase()) || !emetteur.equals(bb.get("client"))) return;
                    int montant = Integer.parseInt(m.getContent().trim());
                    bb.set("solde", i("solde") + montant);
                    bb.set("courses", i("courses") + 1);
                    Journal.log(TaxiAgent.this, "reçoit " + montant + " € de " + emetteur
                            + " (solde " + bb.get("solde") + " €)");
                    if (intention != null && intention.desire.type == Desire.Type.ENCAISSER)
                        intention.markAchieved();
                    oublierCourse();
                    break;

                case Ontology.ANNULATION:
                    if ("engage".equals(phase()) && emetteur.equals(bb.get("client"))) {
                        Journal.log(TaxiAgent.this, "course annulée par " + emetteur + " (" + m.getContent() + ")");
                        oublierCourse();   // le désir ALLER_CHERCHER disparaît -> intention abandonnée
                    }
                    break;
            }
        }
    }

    // =====================================================================
    // Contract Net (rôle Responder) : le taxi délibère sur l'offre à faire
    // =====================================================================
    private class ResponderCourse extends ContractNetResponder {
        ResponderCourse() {
            super(TaxiAgent.this, MessageTemplate.and(
                    MessageTemplate.MatchPerformative(ACLMessage.CFP),
                    MessageTemplate.MatchOntology(Ontology.CFP_COURSE)));
        }

        @Override
        protected ACLMessage handleCfp(ACLMessage cfp) {
            ACLMessage reply = cfp.createReply();
            reply.setPerformative(ACLMessage.REFUSE);

            if (phase() != null) { reply.setContent("indisponible"); return reply; }
            if (Boolean.TRUE.equals(bb.get("besoinPlein"))) { reply.setContent("carburant"); return reply; }

            String[] p = cfp.getContent().split(";");
            int px = Integer.parseInt(p[0]), py = Integer.parseInt(p[1]);
            int dx = Integer.parseInt(p[2]), dy = Integer.parseInt(p[3]);
            int budget = Integer.parseInt(p[4]);

            int distA = Ontology.distance(i("x"), i("y"), px, py);
            int distC = Ontology.distance(px, py, dx, dy);
            int prix  = Ontology.prix(distA, distC);

            if (distA + distC + MARGE_CARBURANT > i("carburant")) {
                reply.setContent("carburant");
                Journal.log(TaxiAgent.this, "refuse " + cfp.getSender().getLocalName()
                        + " : pas assez de carburant (" + bb.get("carburant") + "%)");
                return reply;
            }
            if (prix > budget) { reply.setContent("trop-cher;" + prix); return reply; }

            // Réservation provisoire le temps que le client décide
            bb.set("phase", "reserve");
            bb.set("clientPotentiel", cfp.getSender().getLocalName());
            bb.set("pickupX", px); bb.set("pickupY", py);
            bb.set("destX", dx);   bb.set("destY", dy);
            bb.set("prixCourse", prix);
            bb.set("reserveDepuis", System.currentTimeMillis());

            reply.setPerformative(ACLMessage.PROPOSE);
            reply.setContent(prix + ";" + distA + ";" + distC);
            return reply;
        }

        @Override
        protected ACLMessage handleAcceptProposal(ACLMessage cfp, ACLMessage propose, ACLMessage accept) {
            ACLMessage reply = accept.createReply();
            String client = accept.getSender().getLocalName();

            if (!"reserve".equals(phase()) || !client.equals(bb.get("clientPotentiel"))) {
                reply.setPerformative(ACLMessage.FAILURE);   // réservation expirée entre-temps
                reply.setContent("plus-disponible");
                return reply;
            }
            bb.remove("reserveDepuis");
            bb.set("phase", "engage");
            bb.set("client", client);
            Journal.log(TaxiAgent.this, "accepte la course de " + client
                    + " (" + bb.get("prixCourse") + " €), en route");

            reply.setPerformative(ACLMessage.INFORM);
            reply.setContent("en-route");
            return reply;
        }

        @Override
        protected void handleRejectProposal(ACLMessage cfp, ACLMessage propose, ACLMessage reject) {
            if ("reserve".equals(phase()) && reject.getSender().getLocalName().equals(bb.get("clientPotentiel")))
                oublierCourse();
        }
    }

    // =====================================================================
    // État pour l'affichage
    // =====================================================================
    private class ReponseDisplay extends CyclicBehaviour {
        @Override
        public void action() {
            ACLMessage m = receive(MessageTemplate.MatchOntology(Ontology.ETAT_QUERY));
            if (m == null) { block(); return; }
            ACLMessage r = m.createReply();
            r.setPerformative(ACLMessage.INFORM);
            r.setOntology(Ontology.ETAT_RESPONSE);
            Object client = bb.get("client");
            Object tx = bb.has("targetX") ? bb.get("targetX") : bb.get("x");
            Object ty = bb.has("targetY") ? bb.get("targetY") : bb.get("y");
            // x;y;etat;tx;ty;solde;courses;client;nom;carburant;intention
            r.setContent(bb.get("x") + ";" + bb.get("y") + ";" + etatAffiche() + ";" + tx + ";" + ty + ";"
                    + bb.get("solde") + ";" + bb.get("courses") + ";" + (client == null ? "-" : client) + ";"
                    + bb.get("name") + ";" + bb.get("carburant") + ";" + intentionCourante());
            send(r);
        }
    }

    /** État synthétique pour l'affichage, déduit des croyances et de l'intention. */
    private String etatAffiche() {
        String phase = phase();
        if ("reserve".equals(phase))  return "reserve";
        if ("engage".equals(phase))   return "approche";
        if ("a_bord".equals(phase))   return "en_course";
        if ("paiement".equals(phase)) return "paiement";
        Intention it = intention;
        if (it != null && it.desire.type == Desire.Type.FAIRE_LE_PLEIN) return "plein";
        return "libre";
    }

    // --- utilitaires ---
    private String phase() { return (String) bb.get("phase"); }
    private int i(String k) { return (int) bb.get(k); }

    private void envoyer(String dest, String onto, String contenu) {
        ACLMessage m = new ACLMessage(ACLMessage.INFORM);
        m.addReceiver(new AID(dest, AID.ISLOCALNAME));
        m.setOntology(onto);
        m.setContent(contenu);
        send(m);
    }

    @Override
    protected void takeDown() {
        try { DFService.deregister(this); } catch (FIPAException ignored) {}
    }
}
