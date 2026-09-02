package com.thebirdhouse.plugin;

import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.util.List;

/**
 * Who on your team is online, and what world they're on.
 *
 * A view only, fed by the same poller as the chat panel so having it in both the
 * sidebar and the pop-out window costs one request rather than two.
 *
 * Deliberately shows teammates who are not sharing rather than hiding them, so the
 * list stays a full team roster and a blank status reads as "they haven't switched it
 * on" instead of "they're offline". The server decides what a viewer is allowed to
 * see; nothing here filters, because a client-side filter protects nobody.
 */
public class TeamStatusPanel extends JPanel {

    private static final Color COLOR_ONLINE = new Color(110, 200, 110);
    private static final Color COLOR_OFFLINE = new Color(120, 120, 120);
    private static final Color COLOR_MUTED = new Color(150, 150, 150);
    private static final Color COLOR_ERROR = new Color(255, 120, 120);
    private static final Color COLOR_SELF = new Color(120, 200, 255);

    private final int wrapWidth;
    private final JPanel listPanel;
    private final JLabel titleLabel;
    private final JLabel summaryLabel;

    public TeamStatusPanel(int wrapWidth) {
        this.wrapWidth = wrapWidth;

        setLayout(new BorderLayout(0, 4));
        setBackground(ColorScheme.DARK_GRAY_COLOR);

        titleLabel = new JLabel("Team Status");
        titleLabel.setForeground(Color.WHITE);
        titleLabel.setFont(FontManager.getRunescapeBoldFont());
        titleLabel.setBorder(new EmptyBorder(0, 2, 2, 2));
        add(titleLabel, BorderLayout.NORTH);

        listPanel = new JPanel();
        listPanel.setLayout(new BoxLayout(listPanel, BoxLayout.Y_AXIS));
        listPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        listPanel.setBorder(new EmptyBorder(4, 4, 4, 4));

        JScrollPane scroll = new JScrollPane(listPanel,
            ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
            ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setBorder(BorderFactory.createLineBorder(ColorScheme.MEDIUM_GRAY_COLOR));
        scroll.getVerticalScrollBar().setUnitIncrement(12);
        scroll.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        add(scroll, BorderLayout.CENTER);

        summaryLabel = new JLabel(" ");
        summaryLabel.setForeground(COLOR_MUTED);
        summaryLabel.setFont(FontManager.getRunescapeSmallFont());
        summaryLabel.setBorder(new EmptyBorder(1, 2, 0, 2));
        add(summaryLabel, BorderLayout.SOUTH);

        showNote("Loading\u2026", COLOR_MUTED);
    }

    /** Hand over the current roster. */
    public void setPresence(PresenceData presence) {
        if (presence == null) {
            showNote("Status unavailable \u2014 still trying.", COLOR_ERROR);
            return;
        }

        String team = presence.getTeamName();
        titleLabel.setText(team != null ? "Team Status \u2014 " + team : "Team Status");

        if (presence.getTeamId() == null) {
            showNote("This game has no teams, so there is no team to show.", COLOR_MUTED);
            return;
        }

        // The read is reciprocal, so the server returns nothing until the player's own
        // sharing has registered. Saying so beats an empty list that looks broken.
        if (!presence.isSharing()) {
            showNote("Waiting for your own status to register \u2014 log in to the game and "
                + "this should fill in within a minute.", COLOR_MUTED);
            return;
        }

        List<PresenceMember> members = presence.getMembers();
        if (members == null || members.isEmpty()) {
            showNote("No teammates yet.", COLOR_MUTED);
            return;
        }

        // Rebuilt on every poll unlike the chat thread, because there is no scroll
        // position or typed text to lose here and a "did anything change" comparison
        // would have to cover worlds and relative times anyway.
        listPanel.removeAll();
        int online = 0;
        for (PresenceMember m : members) {
            if (m.isOnline()) {
                online++;
            }
            listPanel.add(memberRow(m));
            listPanel.add(Box.createRigidArea(new Dimension(0, 2)));
        }
        listPanel.revalidate();
        listPanel.repaint();

        summaryLabel.setForeground(COLOR_MUTED);
        summaryLabel.setText(online + " of " + members.size() + " online");
    }

    /** Status could not be loaded, or there is nothing to load. */
    public void showNote(String message, Color color) {
        listPanel.removeAll();
        JLabel note = new JLabel(BoardRenderer.wrapHtml(
            BoardRenderer.escapeHtml(message), wrapWidth, "left"));
        note.setForeground(color != null ? color : COLOR_MUTED);
        note.setFont(FontManager.getRunescapeSmallFont());
        note.setAlignmentX(Component.LEFT_ALIGNMENT);
        listPanel.add(note);
        listPanel.revalidate();
        listPanel.repaint();
        summaryLabel.setText(" ");
    }

    private JPanel memberRow(PresenceMember m) {
        JPanel row = new JPanel(new BorderLayout(4, 0));
        row.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 18));

        JLabel dot = new JLabel("\u25CF");
        dot.setForeground(m.isOnline() ? COLOR_ONLINE : COLOR_OFFLINE);
        dot.setFont(FontManager.getRunescapeSmallFont());
        row.add(dot, BorderLayout.WEST);

        String name = m.getName() != null ? m.getName() : "?";
        JLabel nameLabel = new JLabel(name);
        nameLabel.setForeground(m.isSelf() ? COLOR_SELF : Color.WHITE);
        nameLabel.setFont(FontManager.getRunescapeSmallFont());
        row.add(nameLabel, BorderLayout.CENTER);

        JLabel status = new JLabel(statusText(m));
        status.setForeground(m.isOnline() ? COLOR_ONLINE : COLOR_OFFLINE);
        status.setFont(FontManager.getRunescapeSmallFont());
        status.setHorizontalAlignment(SwingConstants.RIGHT);
        row.add(status, BorderLayout.EAST);

        row.setToolTipText(name + " \u2014 " + tooltip(m));
        return row;
    }

    /**
     * The right-hand column: a world when there is one, otherwise why there isn't.
     *
     * Kept short because the sidebar is 225px wide and the name has to fit too. The
     * tooltip carries the long version.
     */
    private static String statusText(PresenceMember m) {
        if (!m.isSharing()) {
            return "\u2014";
        }
        if (m.isOnline()) {
            Integer world = m.getWorld();
            return world != null ? "W" + world : "online";
        }
        return since(m.getLastSeen());
    }

    private static String tooltip(PresenceMember m) {
        if (!m.isSharing()) {
            return "not sharing their status";
        }
        if (m.isOnline()) {
            Integer world = m.getWorld();
            return world != null ? "logged in on world " + world : "logged in";
        }
        long seen = m.getLastSeen();
        return seen > 0 ? "last seen " + since(seen) : "not seen this event";
    }

    /**
     * A coarse "how long ago", which is all this is good for: the client heartbeats
     * every five minutes, so anything finer than that would be inventing precision.
     */
    private static String since(long timestamp) {
        if (timestamp <= 0) {
            return "offline";
        }
        long minutes = (System.currentTimeMillis() - timestamp) / 60000;
        if (minutes < 0) {
            return "offline";
        }
        if (minutes < 60) {
            return Math.max(minutes, 1) + "m ago";
        }
        long hours = minutes / 60;
        if (hours < 24) {
            return hours + "h ago";
        }
        return (hours / 24) + "d ago";
    }
}
