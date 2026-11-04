package agents;

import jade.core.AID;
import jade.core.Agent;
import jade.core.behaviours.TickerBehaviour;
import jade.core.behaviours.WakerBehaviour;
import jade.domain.DFService;
import jade.domain.FIPAAgentManagement.*;
import jade.domain.FIPAException;
import jade.lang.acl.ACLMessage;
import utils.Journal;
import utils.Ontology;
import utils.Scenario;

import java.util.Random;

/**
 * La ville : fait apparaître les clients (scriptés ou aléatoires) selon le
 * scénario et diffuse un niveau de trafic qui ralentit les taxis.
 *
 * Argument : nom du scénario.
 */
public class EnvironmentAgent extends Agent {

    private final Random rnd = new Random();
    private Scenario sc;
    private int compteur = 0;
    private int congestion;

    @Override
    protected void setup() {
        Object[] args = getArguments();
        sc = Scenario.get(args != null && args.length > 0 ? args[0].toString() : "nominal");
        congestion = sc.traficInitial;
        Journal.log(this, "scénario « " + sc.nom + " » : " + sc.description);

        // Clients scriptés
        for (Scenario.ClientInit c : sc.clientsScriptes) {
            addBehaviour(new WakerBehaviour(this, Math.max(1, c.delai)) {
                @Override protected void onWake() {
                    creerClient(c.px, c.py, c.dx, c.dy, c.budget, c.patience);
                }
            });
        }

        // Clients aléatoires
        for (int k = 0; k < sc.clientsInitiaux; k++) creerClientAleatoire();
        if (sc.maxClients > 0) {
            addBehaviour(new TickerBehaviour(this, sc.periodeApparition) {
                @Override protected void onTick() {
                    int n = chercher(Ontology.SERVICE_CLIENT).length;
                    if (n < sc.maxClients && (n == 0 || rnd.nextDouble() < 0.75)) creerClientAleatoire();
                }
            });
        }

        // Trafic : diffusé tout de suite puis toutes les 6 s
        diffuserTrafic();
        addBehaviour(new TickerBehaviour(this, 6000) {
            @Override protected void onTick() {
                int old = congestion;
                congestion = Math.max(sc.traficMin, Math.min(sc.traficMax, congestion + rnd.nextInt(5) - 2));
                diffuserTrafic();
                if (congestion >= 6 && old < 6) Journal.log(myAgent, "embouteillages en ville (trafic " + congestion + "/9)");
                if (congestion <= 2 && old > 2) Journal.log(myAgent, "le trafic redevient fluide");
            }
        });
    }

    private void diffuserTrafic() {
        ACLMessage msg = new ACLMessage(ACLMessage.INFORM);
        msg.setOntology(Ontology.TRAFIC);
        msg.setContent(String.valueOf(congestion));
        for (DFAgentDescription d : chercher(Ontology.SERVICE_TAXI)) msg.addReceiver(d.getName());
        msg.addReceiver(new AID(Ontology.DISPLAY, AID.ISLOCALNAME));
        send(msg);
    }

    private void creerClientAleatoire() {
        int px = rnd.nextInt(Ontology.GRILLE_W), py = rnd.nextInt(Ontology.GRILLE_H);
        int dx, dy;
        do {
            dx = rnd.nextInt(Ontology.GRILLE_W);
            dy = rnd.nextInt(Ontology.GRILLE_H);
        } while (Ontology.distance(px, py, dx, dy) < 5);

        // Budget = prix de la course seule + une marge plus ou moins grande pour l'approche du taxi
        int dist = Ontology.distance(px, py, dx, dy);
        int budget = Ontology.PRISE_EN_CHARGE_EUR + Ontology.TARIF_CASE * dist
                + sc.margeBudgetMin + rnd.nextInt(sc.margeBudgetMax - sc.margeBudgetMin + 1);
        int patience = sc.patienceMin + rnd.nextInt(sc.patienceMax - sc.patienceMin + 1);
        creerClient(px, py, dx, dy, budget, patience);
    }

    private void creerClient(int px, int py, int dx, int dy, int budget, int patience) {
        String nom = "client" + (++compteur);
        try {
            getContainerController().createNewAgent(nom, "agents.ClientAgent", new Object[]{
                    String.valueOf(px), String.valueOf(py), String.valueOf(dx), String.valueOf(dy),
                    String.valueOf(budget), String.valueOf(patience), String.valueOf(sc.maxTentatives)
            }).start();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private DFAgentDescription[] chercher(String type) {
        DFAgentDescription t = new DFAgentDescription();
        ServiceDescription sd = new ServiceDescription();
        sd.setType(type);
        t.addServices(sd);
        try { return DFService.search(this, t); }
        catch (FIPAException e) { return new DFAgentDescription[0]; }
    }
}
