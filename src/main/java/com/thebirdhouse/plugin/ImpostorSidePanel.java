package com.thebirdhouse.plugin;

import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;

import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.border.EmptyBorder;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * In-game verbs for The Impostor that do not depend on the right-click menu.
 *
 * Eliminate still has to pass the server's 8-tile check. This panel only lists
 * people already standing that close, so a click is not a claim — it is a request
 * the server can still refuse.
 */
@Singleton
public class ImpostorSidePanel extends JPanel {

    private static final Color COLOR_MUTED = new Color(160, 160, 160);
    private static final Color COLOR_DEAD = new Color(255, 90, 90);
    private static final Color COLOR_IMPOSTOR = new Color(220, 70, 70);

    @Inject
    private ImpostorPositionTracker tracker;

    @Inject
    private ImpostorActions actions;

    @Inject
    private DropMatcher dropMatcher;

    @Inject
    private BirdhouseApiClient apiClient;

    private final JLabel statusLabel = line();
    private final JLabel taskLabel = line();
    private final JLabel hintLabel = line();
    private final JPanel verbs = new JPanel();
    private final Timer nearbyTimer;

    private String lastFingerprint = "";

    public ImpostorSidePanel() {
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setBackground(ColorScheme.DARK_GRAY_COLOR);
        setBorder(new EmptyBorder(0, 0, 8, 0));
        setAlignmentX(Component.LEFT_ALIGNMENT);

        JLabel title = new JLabel("The Impostor");
        title.setForeground(Color.WHITE);
        title.setFont(uiFont(Font.BOLD, 14f));
        title.setAlignmentX(Component.LEFT_ALIGNMENT);
        add(title);

        statusLabel.setFont(uiFont(Font.BOLD, 13f));
        add(statusLabel);
        add(taskLabel);
        add(hintLabel);

        verbs.setLayout(new BoxLayout(verbs, BoxLayout.Y_AXIS));
        verbs.setBackground(ColorScheme.DARK_GRAY_COLOR);
        verbs.setAlignmentX(Component.LEFT_ALIGNMENT);
        add(verbs);

        nearbyTimer = new Timer(1000, e -> rebuild());
        nearbyTimer.setRepeats(true);
        setVisible(false);
    }

    void onBoard(BoardData board) {
        SwingUtilities.invokeLater(() -> {
            boolean live = board != null && "impostor".equals(board.getGameType());
            setVisible(live);
            if (live) {
                if (!nearbyTimer.isRunning()) {
                    nearbyTimer.start();
                }
                rebuild();
            } else {
                nearbyTimer.stop();
                lastFingerprint = "";
            }
        });
    }

    void stop() {
        nearbyTimer.stop();
    }

    private void rebuild() {
        BoardData board = dropMatcher.getActiveBoard();
        String room = dropMatcher.getActiveRoomCode();
        if (board == null || !"impostor".equals(board.getGameType()) || room == null) {
            setVisible(false);
            nearbyTimer.stop();
            return;
        }

        boolean dead = tracker.isDead();
        boolean impostor = tracker.isImpostor();
        boolean round = ImpostorActions.commandsAllowed(board, tracker.getPhase());
        boolean blackout = tracker.isBlackout();
        boolean meeting = tracker.isMeeting();
        List<String> nearby = impostor && round && !dead
            ? tracker.getNearbyNames()
            : List.of();
        String task = currentTask(board);
        String fingerprint = String.join("|",
            Boolean.toString(dead), Boolean.toString(impostor), Boolean.toString(round),
            Boolean.toString(blackout), Boolean.toString(meeting),
            tracker.getPhase() == null ? "" : tracker.getPhase(),
            task, String.join(",", nearby));
        if (fingerprint.equals(lastFingerprint)) {
            return;
        }
        lastFingerprint = fingerprint;

        if (dead) {
            setWrapped(statusLabel, "You are out. Stay muted.");
            statusLabel.setForeground(COLOR_DEAD);
            setWrapped(taskLabel, " ");
            setWrapped(hintLabel, "Nothing you do from here counts.");
            hintLabel.setForeground(COLOR_MUTED);
            verbs.removeAll();
            revalidate();
            repaint();
            return;
        }

        if (meeting) {
            setWrapped(statusLabel, "Meeting — vote on the website");
            statusLabel.setForeground(new Color(255, 180, 80));
            setWrapped(taskLabel, task.isEmpty() ? " " : task);
            setWrapped(hintLabel, " ");
            verbs.removeAll();
            revalidate();
            repaint();
            return;
        }

        if (impostor) {
            setWrapped(statusLabel, "You are the impostor");
            statusLabel.setForeground(COLOR_IMPOSTOR);
        } else {
            setWrapped(statusLabel, "You are crew");
            statusLabel.setForeground(new Color(120, 200, 255));
        }
        setWrapped(taskLabel, task.isEmpty() ? " " : task);
        taskLabel.setForeground(Color.WHITE);

        verbs.removeAll();
        if (!round) {
            setWrapped(hintLabel, "Waiting for the round to start.");
            hintLabel.setForeground(COLOR_MUTED);
            revalidate();
            repaint();
            return;
        }

        if (impostor) {
            if (nearby.isEmpty()) {
                setWrapped(hintLabel, "Stand within 8 tiles of someone to eliminate them. The server checks.");
                hintLabel.setForeground(COLOR_MUTED);
            } else {
                setWrapped(hintLabel, "Next to you — eliminate still has to land on the server.");
                hintLabel.setForeground(COLOR_MUTED);
                for (String name : nearby) {
                    verbs.add(verb("Eliminate " + name, COLOR_IMPOSTOR, () -> {
                        if (!confirm("Eliminate " + name + "?",
                            "They have to be standing next to you. A ping from both of you is what decides it, not this button.")) {
                            return;
                        }
                        actions.runAction(apiClient.impostorKill(room, name));
                    }));
                }
            }
            verbs.add(Box.createVerticalStrut(4));
            verbs.add(verb("Cut the lights", Color.WHITE, () ->
                actions.runAction(apiClient.impostorSabotage(room))));
        } else {
            setWrapped(hintLabel, blackout
                ? "Lights out. Names are hidden."
                : "Report a body when you find one, or call everyone in.");
            hintLabel.setForeground(COLOR_MUTED);
        }

        verbs.add(verb("Report body", new Color(255, 140, 140), () -> {
            if (!confirm("Report a body?",
                "You have to be standing near where they died.")) {
                return;
            }
            actions.runAction(apiClient.impostorReport(room));
        }));
        verbs.add(verb("Call a meeting", new Color(255, 200, 120), () -> {
            if (!confirm("Call a meeting?",
                "This pulls everyone in and opens the vote.")) {
                return;
            }
            actions.runAction(apiClient.impostorMeeting(room));
        }));

        revalidate();
        repaint();
    }

    private JButton verb(String label, Color fg, Runnable action) {
        JButton button = new JButton(label);
        button.setAlignmentX(Component.LEFT_ALIGNMENT);
        button.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
        button.setForeground(fg);
        button.setFont(uiFont(Font.PLAIN, 12f));
        button.setFocusPainted(false);
        button.addActionListener(e -> action.run());
        return button;
    }

    private boolean confirm(String title, String message) {
        return JOptionPane.showConfirmDialog(
            this, message, title, JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION;
    }

    private static String currentTask(BoardData board) {
        if (board.getTiles() == null || board.getTiles().isEmpty()) {
            return "";
        }
        BoardTile tile = board.getTiles().get(0);
        if (tile == null || tile.getName() == null) {
            return "";
        }
        if (tile.getQuantity() > 0) {
            return tile.getName() + "  " + tile.getCurrentQty() + "/" + tile.getQuantity();
        }
        return tile.getName();
    }

    private static JLabel line() {
        JLabel label = new JLabel(" ");
        label.setForeground(COLOR_MUTED);
        label.setFont(uiFont(Font.PLAIN, 12f));
        label.setAlignmentX(Component.LEFT_ALIGNMENT);
        // BoxLayout ignores wrap unless the label is width-capped.
        label.setMaximumSize(new Dimension(PluginPanel.PANEL_WIDTH, Integer.MAX_VALUE));
        return label;
    }

    /**
     * The RuneScape bitmap font ghosts its last letters when Swing draws it in
     * the sidebar. DefaultFont is what the rest of the panel already uses for
     * anything that has to be readable at 225px.
     */
    private static Font uiFont(int style, float size) {
        return FontManager.getDefaultFont().deriveFont(style, size);
    }

    private static void setWrapped(JLabel label, String text) {
        if (text == null || text.isBlank()) {
            label.setText(" ");
            return;
        }
        label.setText("<html><div style='width:190px'>" + escapeHtml(text) + "</div></html>");
    }

    private static String escapeHtml(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /** Exposed for tests — the panel never lists anyone outside this set. */
    static List<String> namesInRange(List<String> nearby) {
        return nearby == null ? new ArrayList<>() : new ArrayList<>(nearby);
    }

    static boolean sameTargets(List<String> a, List<String> b) {
        return Objects.equals(a, b);
    }
}
