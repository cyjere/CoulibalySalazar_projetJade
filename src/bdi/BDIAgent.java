package bdi;

import jade.core.Agent;
import jade.core.behaviours.TickerBehaviour;

import java.util.List;

/**
 * Agent BDI générique (boucle de Rao & Georgeff simplifiée), exécutée à chaque tick :
 *
 *   1. Perception      : les comportements JADE de réception de messages
 *                        mettent à jour la base de croyances (bb) au fil de l'eau ;
 *   2. Options         : genererDesirs() produit les désirs compatibles avec les croyances ;
 *   3. Reconsidération : si l'intention courante n'est plus justifiée (son désir a
 *                        disparu des options), elle est abandonnée ;
 *   4. Délibération    : le désir de plus forte priorité devient l'intention, en
 *                        préemptant l'intention courante si elle est moins prioritaire ;
 *   5. Exécution       : un pas du plan associé à l'intention (executer).
 *
 * Stratégie d'engagement : « ouverte » — l'agent reste engagé tant que le désir
 * est justifié, sauf si un désir plus prioritaire apparaît.
 */
public abstract class BDIAgent extends Agent {

    protected final BeliefBase bb = new BeliefBase();
    protected volatile Intention intention = null;

    /** Désirs (options) compatibles avec les croyances actuelles. */
    protected abstract List<Desire> genererDesirs();

    /** Exécute un pas du plan de l'intention. Renvoie true si le but est atteint. */
    protected abstract boolean executer(Intention i);

    /** Appelé quand une intention est adoptée (préemption ou nouvelle). */
    protected void intentionAdoptee(Intention nouvelle, Intention ancienne) {}

    /** Appelé quand une intention est abandonnée car plus justifiée par les croyances. */
    protected void intentionAbandonnee(Intention i) {}

    protected void demarrerCycleBDI(long periodeMs) {
        addBehaviour(new TickerBehaviour(this, periodeMs) {
            @Override protected void onTick() { cycle(); }
        });
    }

    private void cycle() {
        List<Desire> options = genererDesirs();

        // Reconsidération de l'intention courante
        if (intention != null) {
            if (intention.achieved) {
                intention = null;
            } else if (!options.contains(intention.desire)) {
                Intention ancienne = intention;
                intention = null;
                intentionAbandonnee(ancienne);
            }
        }

        // Délibération : désir le plus prioritaire
        Desire meilleur = null;
        for (Desire d : options)
            if (meilleur == null || d.priority > meilleur.priority) meilleur = d;

        if (meilleur != null && (intention == null || meilleur.priority > intention.desire.priority)) {
            Intention ancienne = intention;
            intention = new Intention(meilleur);
            intentionAdoptee(intention, ancienne);
        }

        // Exécution d'un pas du plan
        if (intention != null && executer(intention)) {
            intention.markAchieved();
        }
    }

    public String intentionCourante() {
        Intention i = intention;
        return i == null ? "-" : i.toString();
    }
}
