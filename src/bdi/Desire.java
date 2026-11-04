package bdi;

import java.util.Arrays;

/**
 * Désir (option) généré à partir des croyances. Deux désirs sont égaux s'ils
 * ont le même type et les mêmes paramètres : cela permet au cycle BDI de
 * savoir si l'intention courante est toujours justifiée.
 */
public class Desire {

    public enum Type {
        // Taxi
        ENCAISSER, TRANSPORTER, ALLER_CHERCHER, ATTENDRE_DECISION,
        FAIRE_LE_PLEIN, SE_REPOSITIONNER, ATTENDRE_CLIENT,
        // Client
        PAYER, ANNULER, CHERCHER_TAXI, ATTENDRE_TAXI, ARRIVER, QUITTER
    }

    public final Type type;
    public final Object[] params;
    public final int priority;

    public Desire(Type type, int priority, Object... params) {
        this.type = type;
        this.priority = priority;
        this.params = params;
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof Desire)) return false;
        Desire d = (Desire) o;
        return type == d.type && Arrays.equals(params, d.params);
    }

    @Override
    public int hashCode() { return 31 * type.hashCode() + Arrays.hashCode(params); }

    @Override
    public String toString() { return type.name(); }
}
