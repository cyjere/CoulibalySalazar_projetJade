package utils;

import jade.core.AID;
import jade.core.Agent;
import jade.lang.acl.ACLMessage;

/** Envoie une ligne de journal à la console et à l'agent d'affichage. */
public class Journal {
    public static void log(Agent a, String texte) {
        System.out.println("[" + a.getLocalName() + "] " + texte);
        ACLMessage m = new ACLMessage(ACLMessage.INFORM);
        m.addReceiver(new AID(Ontology.DISPLAY, AID.ISLOCALNAME));
        m.setOntology(Ontology.LOG);
        m.setContent(texte);
        a.send(m);
    }
}
