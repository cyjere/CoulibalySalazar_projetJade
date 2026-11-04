package agents;

import jade.core.AID;
import jade.core.Agent;
import jade.core.behaviours.CyclicBehaviour;
import jade.core.behaviours.TickerBehaviour;
import jade.domain.DFService;
import jade.domain.FIPAAgentManagement.*;
import jade.domain.FIPAException;
import jade.lang.acl.*;
import utils.Ontology;

import javax.swing.*;
import java.awt.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Carte de la ville + tableau de bord (états, intentions BDI, carburant, recettes) + journal.
 * Interroge périodiquement taxis et clients (etat?) et écoute les messages LOG / TRAFIC.
 * Argument optionnel : nom du scénario (affiché dans le titre).
 */
public class GuiDisplayAgent extends Agent {

    private static final int CELL = 36;
    private static final int W = Ontology.GRILLE_W, H = Ontology.GRILLE_H;

    private static final Color C_LIBRE    = new Color(67, 160, 71);
    private static final Color C_RESERVE  = new Color(253, 216, 53);
    private static final Color C_APPROCHE = new Color(251, 140, 0);
    private static final Color C_COURSE   = new Color(229, 57, 53);
    private static final Color C_PAIEMENT = new Color(142, 36, 170);
    private static final Color C_PLEIN    = new Color(84, 110, 122);
    private static final Color C_CLIENT   = new Color(30, 136, 229);

    // nom -> champs de la réponse etat!
    //   taxi   : x;y;etat;tx;ty;solde;courses;client;nom;carburant;intention
    //   client : x;y;etat;dx;dy;budget;taxi;intention
    private final Map<String, String[]> taxis   = new ConcurrentHashMap<>();
    private final Map<String, String[]> clients = new ConcurrentHashMap<>();
    private final Set<String> nomsTaxis = ConcurrentHashMap.newKeySet();
    private volatile int congestion = 0;
    private volatile int servis = 0, abandons = 0, annulations = 0;
    private String scenario = "";

    private JPanel carte;
    private JLabel tableau;
    private JTextArea journal;
    private final SimpleDateFormat hms = new SimpleDateFormat("HH:mm:ss");

    @Override
    protected void setup() {
        Object[] args = getArguments();
        if (args != null && args.length > 0) scenario = args[0].toString();
        if (!GraphicsEnvironment.isHeadless()) SwingUtilities.invokeLater(this::creerFenetre);

        // Interrogation périodique
        addBehaviour(new TickerBehaviour(this, 400) {
            @Override
            protected void onTick() {
                List<AID> t = chercher(Ontology.SERVICE_TAXI);
                List<AID> c = chercher(Ontology.SERVICE_CLIENT);
                Set<String> nt = new HashSet<>(), nc = new HashSet<>();
                for (AID a : t) nt.add(a.getLocalName());
                for (AID a : c) nc.add(a.getLocalName());
                nomsTaxis.addAll(nt);
                taxis.keySet().retainAll(nt);
                clients.keySet().retainAll(nc);   // les clients partis disparaissent

                if (!t.isEmpty() || !c.isEmpty()) {
                    ACLMessage req = new ACLMessage(ACLMessage.REQUEST);
                    req.setOntology(Ontology.ETAT_QUERY);
                    for (AID a : t) req.addReceiver(a);
                    for (AID a : c) req.addReceiver(a);
                    send(req);
                }
                rafraichir();
            }
        });

        // Réception asynchrone des réponses, du journal et du trafic
        addBehaviour(new CyclicBehaviour(this) {
            @Override
            public void action() {
                ACLMessage m = receive();
                if (m == null) { block(); return; }
                String onto = m.getOntology();
                String nom = m.getSender().getLocalName();
                if (Ontology.ETAT_RESPONSE.equals(onto)) {
                    String[] p = m.getContent().split(";");
                    if (nomsTaxis.contains(nom)) { if (p.length >= 11) taxis.put(nom, p); }
                    else if (p.length >= 8) clients.put(nom, p);
                } else if (Ontology.LOG.equals(onto)) {
                    String txt = m.getContent();
                    if (txt.startsWith("arrivé à destination")) servis++;
                    if (txt.startsWith("abandonne après")) abandons++;
                    if (txt.startsWith("annule ")) annulations++;
                    ajouterJournal(nom + " : " + txt);
                } else if (Ontology.TRAFIC.equals(onto)) {
                    congestion = Integer.parseInt(m.getContent().trim());
                }
            }
        });
    }

    // ---------------------------------------------------------------------
    // Swing
    // ---------------------------------------------------------------------
    private void creerFenetre() {
        JFrame frame = new JFrame("Taxis BDI — JADE — scénario : " + scenario);
        frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);

        carte = new JPanel() {
            @Override protected void paintComponent(Graphics g) { super.paintComponent(g); dessiner((Graphics2D) g); }
        };
        carte.setPreferredSize(new Dimension(W * CELL + 1, H * CELL + 1));

        tableau = new JLabel();
        tableau.setVerticalAlignment(SwingConstants.TOP);
        tableau.setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));

        journal = new JTextArea(12, 40);
        journal.setEditable(false);
        journal.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
        JScrollPane sp = new JScrollPane(journal);
        sp.setBorder(BorderFactory.createTitledBorder("Journal"));

        JPanel droite = new JPanel(new BorderLayout());
        droite.setPreferredSize(new Dimension(470, H * CELL));
        droite.add(tableau, BorderLayout.NORTH);
        droite.add(sp, BorderLayout.CENTER);

        JLabel legende = new JLabel("<html><b>Taxis :</b> "
                + "<font color='#43A047'>&#9632;</font> libre &nbsp; <font color='#FDD835'>&#9632;</font> réservé &nbsp; "
                + "<font color='#FB8C00'>&#9632;</font> en approche &nbsp; <font color='#E53935'>&#9632;</font> en course &nbsp; "
                + "<font color='#8E24AA'>&#9632;</font> encaisse &nbsp; <font color='#546E7A'>&#9632;</font> plein &nbsp; "
                + "(barre = carburant) &nbsp;&nbsp; <b>Clients :</b> <font color='#1E88E5'>&#9679;</font> attend, "
                + "drapeau = destination &nbsp;&nbsp; <b>S</b> = station-service</html>");
        legende.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));

        frame.getContentPane().setLayout(new BorderLayout());
        frame.getContentPane().add(carte, BorderLayout.CENTER);
        frame.getContentPane().add(droite, BorderLayout.EAST);
        frame.getContentPane().add(legende, BorderLayout.SOUTH);
        frame.pack();
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
    }

    private void rafraichir() {
        if (carte == null) return;
        final String html = construireTableau();
        SwingUtilities.invokeLater(() -> { tableau.setText(html); carte.repaint(); });
    }

    private void ajouterJournal(String ligne) {
        if (journal == null) return;
        final String l = hms.format(new Date()) + "  " + ligne + "\n";
        SwingUtilities.invokeLater(() -> {
            journal.append(l);
            journal.setCaretPosition(journal.getDocument().getLength());
        });
    }

    private String construireTableau() {
        StringBuilder sb = new StringBuilder("<html><table cellpadding=2>");
        sb.append("<tr><th align=left>Taxi</th><th align=left>État</th><th align=left>Intention</th>"
                + "<th>Carb.</th><th>Courses</th><th>Solde</th></tr>");
        int ca = 0, total = 0;
        for (String nom : new TreeSet<>(taxis.keySet())) {
            String[] p = taxis.get(nom);
            if (p == null) continue;
            int solde = Integer.parseInt(p[5]), nb = Integer.parseInt(p[6]);
            ca += solde; total += nb;
            String etat = libelleTaxi(p[2]) + ("-".equals(p[7]) ? "" : " (" + p[7] + ")");
            sb.append("<tr><td>").append(p[8]).append("</td><td>").append(etat)
              .append("</td><td><font size=2>").append(p[10]).append("</font></td><td align=right>")
              .append(p[9]).append("%</td><td align=center>").append(nb)
              .append("</td><td align=right>").append(solde).append(" €</td></tr>");
        }
        int rech = 0, att = 0, bord = 0;
        for (String[] c : clients.values()) {
            if ("recherche".equals(c[2])) rech++;
            else if ("attente".equals(c[2])) att++;
            else if ("a_bord".equals(c[2])) bord++;
        }
        sb.append("</table><br>");
        sb.append("<b>Courses payées :</b> ").append(total).append(" &nbsp; <b>Recette totale :</b> ").append(ca).append(" €<br>");
        sb.append("<b>Clients :</b> ").append(rech).append(" cherchent, ").append(att)
          .append(" attendent leur taxi, ").append(bord).append(" à bord<br>");
        sb.append("<b>Servis :</b> ").append(servis).append(" &nbsp; <b>Annulations :</b> ").append(annulations)
          .append(" &nbsp; <b>Abandons :</b> ").append(abandons).append("<br>");
        sb.append("<b>Trafic :</b> ").append(congestion).append("/9 ")
          .append(congestion >= 6 ? "(embouteillages)" : congestion <= 2 ? "(fluide)" : "(dense)");
        return sb.append("</html>").toString();
    }

    private static String libelleTaxi(String e) {
        switch (e) {
            case "libre":     return "libre";
            case "reserve":   return "réservé";
            case "approche":  return "en approche";
            case "en_course": return "en course";
            case "paiement":  return "encaisse";
            case "plein":     return "va faire le plein";
            default:          return e;
        }
    }

    private static Color couleurTaxi(String e) {
        switch (e) {
            case "libre":     return C_LIBRE;
            case "reserve":   return C_RESERVE;
            case "approche":  return C_APPROCHE;
            case "en_course": return C_COURSE;
            case "paiement":  return C_PAIEMENT;
            case "plein":     return C_PLEIN;
            default:          return Color.GRAY;
        }
    }

    private static int cx(int x) { return x * CELL + CELL / 2; }

    private void dessiner(Graphics2D g2) {
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        // Fond teinté selon le trafic
        int t = congestion * 6;
        g2.setColor(new Color(246, 246 - t / 2, 250 - t));
        g2.fillRect(0, 0, carte.getWidth(), carte.getHeight());
        g2.setColor(new Color(222, 222, 232));
        for (int i = 0; i <= W; i++) g2.drawLine(i * CELL, 0, i * CELL, H * CELL);
        for (int j = 0; j <= H; j++) g2.drawLine(0, j * CELL, W * CELL, j * CELL);

        // Station-service
        int sx = Ontology.STATION_X * CELL, sy = Ontology.STATION_Y * CELL;
        g2.setColor(new Color(207, 216, 220));
        g2.fillRect(sx + 1, sy + 1, CELL - 1, CELL - 1);
        g2.setColor(C_PLEIN);
        g2.setFont(g2.getFont().deriveFont(Font.BOLD, 16f));
        g2.drawString("S", sx + CELL / 2 - 5, sy + CELL / 2 + 6);

        Stroke tirets = new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 1f, new float[]{6f, 5f}, 0f);
        Stroke plein = new BasicStroke(2f);

        // Clients : trajet demandé (pointillé léger) + drapeau de destination
        for (Map.Entry<String, String[]> e : clients.entrySet()) {
            String[] p = e.getValue();
            String etat = p[2];
            if ("arrive".equals(etat) || "abandon".equals(etat)) continue;
            int x = Integer.parseInt(p[0]), y = Integer.parseInt(p[1]);
            int dx = Integer.parseInt(p[3]), dy = Integer.parseInt(p[4]);
            if (!"a_bord".equals(etat)) {
                g2.setColor(new Color(30, 136, 229, 70));
                g2.setStroke(tirets);
                g2.drawLine(cx(x), cx(y), cx(dx), cx(y));
                g2.drawLine(cx(dx), cx(y), cx(dx), cx(dy));
            }
            drapeau(g2, dx, dy, e.getKey());
        }

        // Taxis : route vers la cible de l'intention courante
        for (String[] p : taxis.values()) {
            String etat = p[2];
            boolean repositionne = "libre".equals(etat) && "SE_REPOSITIONNER".equals(p[10]);
            if (!"approche".equals(etat) && !"en_course".equals(etat) && !"plein".equals(etat) && !repositionne) continue;
            int x = Integer.parseInt(p[0]), y = Integer.parseInt(p[1]);
            int tx = Integer.parseInt(p[3]), ty = Integer.parseInt(p[4]);
            g2.setColor(repositionne ? new Color(67, 160, 71, 120) : couleurTaxi(etat));
            g2.setStroke(tirets);
            g2.drawLine(cx(x), cx(y), cx(tx), cx(y));
            g2.drawLine(cx(tx), cx(y), cx(tx), cx(ty));
        }

        // Clients au sol
        g2.setFont(g2.getFont().deriveFont(Font.PLAIN, 11f));
        for (Map.Entry<String, String[]> e : clients.entrySet()) {
            String[] p = e.getValue();
            String etat = p[2];
            if ("a_bord".equals(etat)) continue;   // dessiné dans le taxi
            int x = Integer.parseInt(p[0]) * CELL, y = Integer.parseInt(p[1]) * CELL;
            g2.setStroke(plein);
            if ("arrive".equals(etat)) {
                g2.setColor(C_LIBRE);
                g2.drawOval(x + 9, y + 9, CELL - 18, CELL - 18);
            } else {
                g2.setColor("abandon".equals(etat) ? Color.LIGHT_GRAY : C_CLIENT);
                g2.fillOval(x + 9, y + 9, CELL - 18, CELL - 18);
                g2.setColor(Color.DARK_GRAY);
                g2.drawOval(x + 9, y + 9, CELL - 18, CELL - 18);
            }
            g2.setColor(Color.DARK_GRAY);
            g2.drawString(e.getKey(), x + 2, y + CELL + 10);
        }

        // Taxis
        for (String[] p : taxis.values()) {
            int x = Integer.parseInt(p[0]) * CELL, y = Integer.parseInt(p[1]) * CELL;
            g2.setColor(couleurTaxi(p[2]));
            g2.fillRoundRect(x + 3, y + 3, CELL - 6, CELL - 9, 9, 9);
            g2.setColor(Color.BLACK);
            g2.setStroke(plein);
            g2.drawRoundRect(x + 3, y + 3, CELL - 6, CELL - 9, 9, 9);
            if ("en_course".equals(p[2])) {           // passager à bord
                g2.setColor(Color.WHITE);
                g2.fillOval(x + CELL / 2 - 5, y + CELL / 2 - 7, 10, 10);
            }
            // Jauge de carburant
            int carb = Integer.parseInt(p[9]);
            int lw = CELL - 8;
            g2.setColor(new Color(0, 0, 0, 60));
            g2.fillRect(x + 4, y + CELL - 5, lw, 4);
            g2.setColor(carb < Ontology.SEUIL_PLEIN ? C_COURSE : new Color(0, 150, 136));
            g2.fillRect(x + 4, y + CELL - 5, lw * carb / Ontology.RESERVOIR, 4);

            g2.setColor(Color.BLACK);
            g2.drawString(p[8], x + 2, y < CELL ? y + CELL + 11 : y - 2);   // rangée du haut : nom en dessous
        }
    }

    private void drapeau(Graphics2D g2, int x, int y, String nom) {
        int px = x * CELL + CELL / 2 - 4, py = y * CELL + 6;
        g2.setStroke(new BasicStroke(2f));
        g2.setColor(Color.DARK_GRAY);
        g2.drawLine(px, py, px, py + CELL - 12);
        g2.setColor(C_CLIENT);
        g2.fillPolygon(new int[]{px, px + 12, px}, new int[]{py, py + 5, py + 10}, 3);
        g2.setColor(new Color(60, 60, 60));
        g2.setFont(g2.getFont().deriveFont(Font.PLAIN, 9f));
        g2.drawString(nom.replace("client", "c"), px + 4, py + CELL - 8);
        g2.setFont(g2.getFont().deriveFont(Font.PLAIN, 11f));
    }

    private List<AID> chercher(String type) {
        DFAgentDescription t = new DFAgentDescription();
        ServiceDescription sd = new ServiceDescription();
        sd.setType(type);
        t.addServices(sd);
        List<AID> res = new ArrayList<>();
        try { for (DFAgentDescription d : DFService.search(this, t)) res.add(d.getName()); }
        catch (FIPAException ignored) {}
        return res;
    }
}
