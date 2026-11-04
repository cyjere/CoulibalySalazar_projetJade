package bdi;

/** Désir auquel l'agent s'est engagé ; exécuté pas à pas par le cycle BDI. */
public class Intention {
    public final Desire desire;
    public boolean achieved = false;
    public final long startedAt = System.currentTimeMillis();

    public Intention(Desire d) { this.desire = d; }

    public void markAchieved() { achieved = true; }

    public long age() { return System.currentTimeMillis() - startedAt; }

    @Override
    public String toString() { return desire == null ? "-" : desire.toString(); }
}
