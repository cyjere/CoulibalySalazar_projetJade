package agents;

import bdi.*;
import jade.core.AID;
import jade.core.Agent;
import jade.core.behaviours.CyclicBehaviour;
import jade.domain.DFService;
import jade.domain.FIPAAgentManagement.*;
import jade.domain.FIPAException;
import jade.domain.FIPANames;
import jade.lang.acl.*;
import jade.proto.ContractNetInitiator;
import utils.Journal;
import utils.Ontology;

import java.util.*;

/**
 * Client BDI. But final : ARRIVER à destination sans dépasser son budget.
 *
 * CROYANCES (bb) : x, y, destX, destY, budget, patience (s), tentatives,
 *   phase (recherche | negociation | attente | a_bord | arrive | parti | abandon),
 *   taxi réservé, prix convenu, début de l'attente, prochain essai.
 *
 * DÉSIRS (priorité) :
 *   PAYER (100)          arrivé et pas encore payé
 *   QUITTER (90)         payé ou abandon -> quitter la simulation
 *   ANNULER (60)         taxi réservé mais attente > patience
 *   CHERCHER_TAXI (50)   sans taxi et délai de relance écoulé
 *   ATTENDRE_TAXI (40)   taxi réservé, en approche
 *   ARRIVER (40)         à bord
 *
 * Arguments : px, py, dx, dy, budget [, patience(s) [, maxTentatives]]
 */
public class ClientAgent extends BDIAgent {

    private static final long TICK_MS       = 300;
    private static final long DELAI_RELANCE = 3000;
    private static final long DELAI_REPONSE = 1500;   // reply-by du CFP

    private int maxTentatives = 6;

    @Override
    protected void setup() {
        Object[] a = getArguments();
        if (a == null || a.length < 5) {
            System.err.println(getLocalName() + " : arguments manquants (px,py,dx,dy,budget) ! args=" + Arrays.toString(a));
            doDelete();
            return;
        }
        bb.set("x", Integer.parseInt(a[0].toString()));
        bb.set("y", Integer.parseInt(a[1].toString()));
        bb.set("destX", Integer.parseInt(a[2].toString()));
        bb.set("destY", Integer.parseInt(a[3].toString()));
        bb.set("budget", Integer.parseInt(a[4].toString()));
        bb.set("patience", a.length > 5 ? Integer.parseInt(a[5].toString()) : 30);
        if (a.length > 6) maxTentatives = Integer.parseInt(a[6].toString());
        bb.set("phase", "recherche");
        bb.set("tentatives", 0);
        bb.set("prochainEssai", 0L);

        DFAgentDescription dfd = new DFAgentDescription();
        dfd.setName(getAID());
        ServiceDescription sd = new ServiceDescription();
        sd.setType(Ontology.SERVICE_CLIENT);
        sd.setName("ClientService-" + getLocalName());
        dfd.addServices(sd);
        try { DFService.register(this, dfd); } catch (FIPAException e) { e.printStackTrace(); }

        Journal.log(this, "veut aller de (" + bb.get("x") + "," + bb.get("y") + ") à ("
                + bb.get("destX") + "," + bb.get("destY") + "), budget " + bb.get("budget")
                + " €, patience " + bb.get("patience") + " s");

        addBehaviour(new PerceptionMessages());
        addBehaviour(new ReponseDisplay());
        demarrerCycleBDI(TICK_MS);
    }

    // =====================================================================
    // Désirs
    // =====================================================================
    @Override
    protected List<Desire> genererDesirs() {
        List<Desire> d = new ArrayList<>();
        String phase = phase();
        long now = System.currentTimeMillis();

        switch (phase) {
            case "arrive":
                d.add(new Desire(Desire.Type.PAYER, 100, bb.get("taxi")));
                break;
            case "parti":
            case "abandon":
                if (now >= (long) bb.get("quitterApres"))   // reste visible quelques secondes
                    d.add(new Desire(Desire.Type.QUITTER, 90));
                break;
            case "attente": {
                long attente = now - (long) bb.get("attenteDepuis");
                if (attente > 1000L * i("patience"))
                    d.add(new Desire(Desire.Type.ANNULER, 60, bb.get("taxi")));
                d.add(new Desire(Desire.Type.ATTENDRE_TAXI, 40, bb.get("taxi")));
                break;
            }
            case "a_bord":
                d.add(new Desire(Desire.Type.ARRIVER, 40, bb.get("destX"), bb.get("destY")));
                break;
            case "recherche":
                if (now >= (long) bb.get("prochainEssai"))
                    d.add(new Desire(Desire.Type.CHERCHER_TAXI, 50, i("tentatives")));
                break;
            default:   // negociation : on attend la fin du Contract Net
                break;
        }
        return d;
    }

    // =====================================================================
    // Plans
    // =====================================================================
    @Override
    protected boolean executer(Intention it) {
        switch (it.desire.type) {
            case CHERCHER_TAXI:  lancerAppelDOffres(); return true;
            case ANNULER:        annuler();            return true;
            case PAYER:          payer();              return true;
            case QUITTER:        doDelete();           return true;
            default:             return false;   // ATTENDRE_TAXI, ARRIVER : buts passifs
        }
    }

    @Override
    protected void intentionAbandonnee(Intention i) {
        // ex. ATTENDRE_TAXI abandonné car le taxi a annulé : rien à faire, la phase a changé
    }

    private void lancerAppelDOffres() {
        int n = i("tentatives") + 1;
        bb.set("tentatives", n);

        List<AID> taxis = chercherTaxis();
        if (taxis.isEmpty()) { relancer("aucun taxi en service"); return; }

        ACLMessage cfp = new ACLMessage(ACLMessage.CFP);
        for (AID t : taxis) cfp.addReceiver(t);
        cfp.setOntology(Ontology.CFP_COURSE);
        cfp.setProtocol(FIPANames.InteractionProtocol.FIPA_CONTRACT_NET);
        cfp.setReplyByDate(new Date(System.currentTimeMillis() + DELAI_REPONSE));
        cfp.setContent(bb.get("x") + ";" + bb.get("y") + ";" + bb.get("destX") + ";"
                + bb.get("destY") + ";" + bb.get("budget"));

        bb.set("phase", "negociation");
        addBehaviour(new InitiateurCourse(this, cfp));
    }

    private void annuler() {
        String taxi = (String) bb.get("taxi");
        ACLMessage m = new ACLMessage(ACLMessage.CANCEL);
        m.addReceiver(new AID(taxi, AID.ISLOCALNAME));
        m.setOntology(Ontology.ANNULATION);
        m.setContent("attente trop longue");
        send(m);
        Journal.log(this, "annule " + taxi + " : attente > " + bb.get("patience") + " s");
        bb.remove("taxi");
        bb.set("tentatives", 0);
        relancer("nouvelle recherche après annulation");
    }

    private void payer() {
        String taxi = (String) bb.get("taxi");
        int du = i("prixDu");
        int paye = Math.min(du, i("budget"));
        bb.set("budget", i("budget") - paye);

        ACLMessage pay = new ACLMessage(ACLMessage.INFORM);
        pay.addReceiver(new AID(taxi, AID.ISLOCALNAME));
        pay.setOntology(Ontology.PAIEMENT);
        pay.setContent(String.valueOf(paye));
        send(pay);
        Journal.log(this, "arrivé à destination, paie " + paye + " € à " + taxi);

        bb.set("phase", "parti");
        bb.set("quitterApres", System.currentTimeMillis() + 3000);   // reste visible 3 s
    }

    /** Remet le client en recherche, ou abandonne s'il a épuisé ses tentatives. */
    private void relancer(String raison) {
        if (i("tentatives") >= maxTentatives) {
            bb.set("phase", "abandon");
            bb.set("quitterApres", System.currentTimeMillis() + 2000);
            Journal.log(this, "abandonne après " + i("tentatives") + " essais (" + raison + ")");
            return;
        }
        bb.set("phase", "recherche");
        bb.set("prochainEssai", System.currentTimeMillis() + DELAI_RELANCE);
        System.out.println("[" + getLocalName() + "] " + raison + ", nouvel essai dans " + DELAI_RELANCE / 1000 + " s");
    }

    // =====================================================================
    // Contract Net (rôle Initiator)
    // =====================================================================
    private class InitiateurCourse extends ContractNetInitiator {
        private String choisi = null;

        InitiateurCourse(Agent a, ACLMessage cfp) { super(a, cfp); }

        @Override
        protected void handleAllResponses(Vector responses, Vector acceptances) {
            ACLMessage best = null;
            int bestPrix = Integer.MAX_VALUE, bestApproche = Integer.MAX_VALUE, tropCher = 0, carburant = 0;

            for (Object o : responses) {
                ACLMessage r = (ACLMessage) o;
                if (r.getPerformative() == ACLMessage.PROPOSE) {
                    String[] p = r.getContent().split(";");
                    int prix = Integer.parseInt(p[0]), approche = Integer.parseInt(p[1]);
                    // Critère : prix le plus bas, puis taxi le plus proche
                    if (prix < bestPrix || (prix == bestPrix && approche < bestApproche)) {
                        bestPrix = prix; bestApproche = approche; best = r;
                    }
                } else if (r.getPerformative() == ACLMessage.REFUSE && r.getContent() != null) {
                    if (r.getContent().startsWith("trop-cher")) tropCher++;
                    if (r.getContent().startsWith("carburant")) carburant++;
                }
            }

            for (Object o : responses) {
                ACLMessage r = (ACLMessage) o;
                if (r.getPerformative() != ACLMessage.PROPOSE) continue;
                ACLMessage reply = r.createReply();
                reply.setPerformative(r == best ? ACLMessage.ACCEPT_PROPOSAL : ACLMessage.REJECT_PROPOSAL);
                acceptances.add(reply);
            }

            if (best == null) {
                relancer(tropCher > 0 ? "les taxis libres sont trop loin pour le budget"
                       : carburant > 0 ? "les taxis libres manquent de carburant"
                       : "aucun taxi libre");
            } else {
                choisi = best.getSender().getLocalName();
                bb.set("prixConvenu", bestPrix);
            }
        }

        @Override
        protected void handleAllResultNotifications(Vector results) {
            if (choisi == null) return;   // déjà relancé dans handleAllResponses
            for (Object o : results) {
                ACLMessage r = (ACLMessage) o;
                if (r.getPerformative() == ACLMessage.INFORM && r.getSender().getLocalName().equals(choisi)) {
                    bb.set("taxi", choisi);
                    bb.set("phase", "attente");
                    bb.set("attenteDepuis", System.currentTimeMillis());
                    Journal.log(myAgent, "réserve " + choisi + " pour " + bb.get("prixConvenu") + " €");
                    return;
                }
            }
            relancer("le taxi choisi s'est désisté");
        }
    }

    // =====================================================================
    // Perception
    // =====================================================================
    private class PerceptionMessages extends CyclicBehaviour {
        private final MessageTemplate mt = MessageTemplate.or(
                MessageTemplate.MatchOntology(Ontology.PRISE_EN_CHARGE),
                MessageTemplate.MatchOntology(Ontology.ARRIVEE));
        @Override
        public void action() {
            ACLMessage m = receive(mt);
            if (m == null) { block(); return; }
            if (!m.getSender().getLocalName().equals(bb.get("taxi"))) return;   // taxi annulé entre-temps

            if (Ontology.PRISE_EN_CHARGE.equals(m.getOntology())) {
                bb.set("phase", "a_bord");
            } else {
                String[] p = m.getContent().split(";");
                bb.set("x", Integer.parseInt(p[0]));
                bb.set("y", Integer.parseInt(p[1]));
                bb.set("prixDu", Integer.parseInt(p[2]));
                bb.set("phase", "arrive");
                if (intention != null && intention.desire.type == Desire.Type.ARRIVER) intention.markAchieved();
            }
        }
    }

    private class ReponseDisplay extends CyclicBehaviour {
        @Override
        public void action() {
            ACLMessage m = receive(MessageTemplate.MatchOntology(Ontology.ETAT_QUERY));
            if (m == null) { block(); return; }
            ACLMessage r = m.createReply();
            r.setPerformative(ACLMessage.INFORM);
            r.setOntology(Ontology.ETAT_RESPONSE);
            Object taxi = bb.get("taxi");
            // x;y;etat;dx;dy;budget;taxi;intention
            r.setContent(bb.get("x") + ";" + bb.get("y") + ";" + etatAffiche() + ";"
                    + bb.get("destX") + ";" + bb.get("destY") + ";" + bb.get("budget") + ";"
                    + (taxi == null ? "-" : taxi) + ";" + intentionCourante());
            send(r);
        }
    }

    private String etatAffiche() {
        switch (phase()) {
            case "negociation": return "recherche";
            case "parti":       return "arrive";
            default:            return phase();
        }
    }

    @Override
    protected void takeDown() {
        try { DFService.deregister(this); } catch (FIPAException ignored) {}
    }

    private String phase() { return (String) bb.get("phase"); }
    private int i(String k) { return (int) bb.get(k); }

    private List<AID> chercherTaxis() {
        DFAgentDescription template = new DFAgentDescription();
        ServiceDescription sd = new ServiceDescription();
        sd.setType(Ontology.SERVICE_TAXI);
        template.addServices(sd);
        List<AID> res = new ArrayList<>();
        try { for (DFAgentDescription d : DFService.search(this, template)) res.add(d.getName()); }
        catch (FIPAException e) { e.printStackTrace(); }
        return res;
    }
}
