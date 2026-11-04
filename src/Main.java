import jade.core.Profile;
import jade.core.ProfileImpl;
import jade.core.Runtime;
import jade.wrapper.AgentContainer;
import utils.Scenario;

import java.awt.GraphicsEnvironment;

/**
 * Lancement : java -cp "lib/*:classes" Main [scenario]
 * Scénarios : nominal (défaut), heure_de_pointe, embouteillage, carburant, budget_serre, concurrence
 */
public class Main {
    public static void main(String[] args) throws Exception {
        String nom = args.length > 0 ? args[0] : "nominal";
        Scenario sc;
        try {
            sc = Scenario.get(nom);
        } catch (IllegalArgumentException e) {
            System.err.println(e.getMessage());
            for (Scenario s : Scenario.tous()) System.err.println("  " + s.nom + " — " + s.description);
            return;
        }
        System.out.println("=== Scénario " + sc.nom + " : " + sc.description + " ===");

        boolean graphique = !GraphicsEnvironment.isHeadless() && !Boolean.getBoolean("sansRMA");

        Runtime rt = Runtime.instance();
        Profile p = new ProfileImpl();
        p.setParameter(Profile.GUI, String.valueOf(graphique));   // RMA de JADE (AMS, DF, Sniffer...)
        AgentContainer mc = rt.createMainContainer(p);

        // Affichage en premier pour qu'il reçoive tout le journal
        mc.createNewAgent("display", "agents.GuiDisplayAgent", new Object[]{ sc.nom }).start();

        // Taxis : nom, x, y, carburant
        int k = 1;
        for (Scenario.TaxiInit t : sc.taxis) {
            mc.createNewAgent("taxi" + (k++), "agents.TaxiAgent", new Object[]{
                    t.nom, String.valueOf(t.x), String.valueOf(t.y), String.valueOf(t.carburant) }).start();
        }

        Thread.sleep(1500);   // laisser les taxis s'enregistrer au DF

        // La ville génère les clients et le trafic selon le scénario
        mc.createNewAgent("ville", "agents.EnvironmentAgent", new Object[]{ sc.nom }).start();
    }
}
