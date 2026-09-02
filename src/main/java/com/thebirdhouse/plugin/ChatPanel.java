package com.thebirdhouse.plugin;

import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Read and reply to your team's chat without leaving the client.
 *
 * A view only: it renders whatever thread it is handed and reports what the player
 * typed. One poller in BirdhousePanel feeds every instance, the same way the board
 * is mirrored into the pop-out window, so having this in two places does not double
 * the request rate.
 *
 * Messages are player-authored and land inside Swing HTML labels, so every field is
 * escaped on the way in. Nothing here writes to the game chatbox: incoming messages
 * are only ever drawn in this panel.
 */
public class ChatPanel extends JPanel {

    /** Matches the cap the backend and the database rules both enforce. */
    static final int MAX_LENGTH = 300;

    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm");
    private static final Color COLOR_SELF = new Color(120, 200, 255);
    private static final Color COLOR_OTHER = new Color(255, 195, 90);
    private static final Color COLOR_MUTED = new Color(150, 150, 150);
    private static final Color COLOR_ERROR = new Color(255, 120, 120);

    /** How the panel hands a typed message off to whoever owns the connection. */
    public interface Sender {
        /** Resolves with null on success, or a short message to show the player. */
        void send(String text, Consumer<String> onResult);
    }

    private final Sender sender;
    private final int wrapWidth;

    private final JPanel messagesPanel;
    private final JScrollPane scrollPane;
    private final JLabel titleLabel;
    private final JLabel statusLabel;
    private final JTextField input;
    private final JButton sendButton;

    /** Ids of what is currently drawn, so an unchanged poll does not rebuild the view. */
    private List<String> renderedIds = new ArrayList<>();
    private String selfName = "";
    private boolean sending;
    private boolean loaded;

    public ChatPanel(Sender sender, int wrapWidth) {
        this.sender = sender;
        this.wrapWidth = wrapWidth;

        setLayout(new BorderLayout(0, 4));
        setBackground(ColorScheme.DARK_GRAY_COLOR);

        titleLabel = new JLabel("Team Chat");
        titleLabel.setForeground(Color.WHITE);
        titleLabel.setFont(FontManager.getRunescapeBoldFont());
        titleLabel.setBorder(new EmptyBorder(0, 2, 2, 2));
        add(titleLabel, BorderLayout.NORTH);

        messagesPanel = new JPanel();
        messagesPanel.setLayout(new BoxLayout(messagesPanel, BoxLayout.Y_AXIS));
        messagesPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        messagesPanel.setBorder(new EmptyBorder(4, 4, 4, 4));

        scrollPane = new JScrollPane(messagesPanel,
            ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
            ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scrollPane.setBorder(BorderFactory.createLineBorder(ColorScheme.MEDIUM_GRAY_COLOR));
        scrollPane.getVerticalScrollBar().setUnitIncrement(12);
        scrollPane.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        add(scrollPane, BorderLayout.CENTER);

        JPanel bottom = new JPanel(new BorderLayout(4, 2));
        bottom.setBackground(ColorScheme.DARK_GRAY_COLOR);

        JPanel inputRow = new JPanel(new BorderLayout(4, 0));
        inputRow.setBackground(ColorScheme.DARK_GRAY_COLOR);

        input = new JTextField();
        input.setToolTipText("Message your team (" + MAX_LENGTH + " characters)");
        // Enter sends, which is what anyone typing in a chat box expects. This is a
        // Swing field in the plugin's own panel; nothing is ever typed into the game.
        input.addActionListener(this::onSend);
        input.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { syncSendEnabled(); }
            public void removeUpdate(DocumentEvent e) { syncSendEnabled(); }
            public void changedUpdate(DocumentEvent e) { syncSendEnabled(); }
        });
        inputRow.add(input, BorderLayout.CENTER);

        sendButton = new JButton("Send");
        sendButton.setFocusPainted(false);
        sendButton.setEnabled(false);
        sendButton.addActionListener(this::onSend);
        inputRow.add(sendButton, BorderLayout.EAST);

        bottom.add(inputRow, BorderLayout.CENTER);

        statusLabel = new JLabel(" ");
        statusLabel.setForeground(COLOR_MUTED);
        statusLabel.setFont(FontManager.getRunescapeSmallFont());
        statusLabel.setBorder(new EmptyBorder(1, 2, 0, 2));
        bottom.add(statusLabel, BorderLayout.SOUTH);

        add(bottom, BorderLayout.SOUTH);

        showStatus("Loading\u2026", COLOR_MUTED);
    }

    /** The player's own roster name, so their messages read differently to everyone else's. */
    public void setSelfName(String name) {
        this.selfName = name != null ? name : "";
    }

    public void showStatus(String message, Color color) {
        statusLabel.setText(message == null || message.isEmpty() ? " " : message);
        statusLabel.setForeground(color != null ? color : COLOR_MUTED);
    }

    /**
     * Hand over the current thread.
     *
     * Rebuilt only when the message list actually changed: a poll that brings nothing
     * new would otherwise throw away the scroll position several times a minute while
     * someone is reading back through it.
     */
    public void setThread(ChatData chat) {
        List<ChatMessage> messages = chat != null && chat.getMessages() != null
            ? chat.getMessages()
            : new ArrayList<>();

        String team = chat != null && chat.getTeamName() != null ? chat.getTeamName() : null;
        titleLabel.setText(team != null ? "Team Chat \u2014 " + team : "Team Chat");

        // Clear the startup placeholder once, rather than on every poll: after this the
        // status line belongs to send feedback, and a poll must not wipe an error.
        if (!loaded) {
            loaded = true;
            showStatus("", null);
        }

        List<String> ids = new ArrayList<>(messages.size());
        for (ChatMessage m : messages) {
            ids.add(m.getId());
        }
        if (ids.equals(renderedIds)) {
            return;
        }

        boolean pinnedToBottom = isScrolledToBottom();
        renderedIds = ids;

        messagesPanel.removeAll();
        if (messages.isEmpty()) {
            JLabel empty = new JLabel("No messages yet.");
            empty.setForeground(COLOR_MUTED);
            empty.setFont(FontManager.getRunescapeSmallFont());
            empty.setAlignmentX(Component.LEFT_ALIGNMENT);
            messagesPanel.add(empty);
        } else {
            for (ChatMessage m : messages) {
                messagesPanel.add(messageRow(m));
                messagesPanel.add(Box.createRigidArea(new Dimension(0, 3)));
            }
        }
        messagesPanel.revalidate();
        messagesPanel.repaint();

        // Someone who has scrolled up to re-read is not dragged back down by a new
        // message; anyone sitting at the bottom follows the conversation.
        if (pinnedToBottom) {
            SwingUtilities.invokeLater(this::scrollToBottom);
        }
    }

    /** Called when the room has no team thread at all, so the reply box is pointless. */
    public void setUnavailable(String reason) {
        renderedIds = new ArrayList<>();
        titleLabel.setText("Team Chat");
        messagesPanel.removeAll();
        JLabel note = new JLabel(BoardRenderer.wrapHtml(
            BoardRenderer.escapeHtml(reason), wrapWidth, "left"));
        note.setForeground(COLOR_MUTED);
        note.setFont(FontManager.getRunescapeSmallFont());
        note.setAlignmentX(Component.LEFT_ALIGNMENT);
        messagesPanel.add(note);
        messagesPanel.revalidate();
        messagesPanel.repaint();
        input.setEnabled(false);
        sendButton.setEnabled(false);
        showStatus("", null);
    }

    public void setSendEnabled(boolean enabled) {
        input.setEnabled(enabled);
        syncSendEnabled();
    }

    private JPanel messageRow(ChatMessage m) {
        JPanel row = new JPanel();
        row.setLayout(new BoxLayout(row, BoxLayout.Y_AXIS));
        row.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        row.setAlignmentX(Component.LEFT_ALIGNMENT);

        String name = m.getName() != null ? m.getName() : "?";
        boolean mine = !selfName.isEmpty() && selfName.equalsIgnoreCase(name);

        JLabel header = new JLabel(name + "  " + clock(m.getTimestamp()));
        header.setForeground(mine ? COLOR_SELF : COLOR_OTHER);
        header.setFont(FontManager.getRunescapeSmallFont());
        header.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.add(header);

        // An image-only message would otherwise render as a name with nothing under it.
        // The panel does not download chat images, so it says one was posted instead.
        String text = m.getText() != null ? m.getText() : "";
        StringBuilder body = new StringBuilder();
        if (!text.isEmpty()) {
            body.append(BoardRenderer.escapeHtml(text));
        }
        if (m.isHasImage()) {
            if (body.length() > 0) {
                body.append("<br>");
            }
            body.append("<i>[image \u2014 open the room on the site to view]</i>");
        }
        if (body.length() == 0) {
            body.append("<i>[empty]</i>");
        }

        JLabel textLabel = new JLabel(BoardRenderer.wrapHtml(body.toString(), wrapWidth, "left"));
        textLabel.setForeground(Color.WHITE);
        textLabel.setFont(FontManager.getRunescapeSmallFont());
        textLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.add(textLabel);

        return row;
    }

    private static String clock(long timestamp) {
        if (timestamp <= 0) {
            return "";
        }
        try {
            return CLOCK.format(Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()));
        } catch (RuntimeException e) {
            return "";
        }
    }

    private void onSend(ActionEvent event) {
        if (sending) {
            return;
        }
        String text = input.getText().trim();
        if (text.isEmpty()) {
            return;
        }
        if (text.length() > MAX_LENGTH) {
            showStatus("Too long by " + (text.length() - MAX_LENGTH) + " characters", COLOR_ERROR);
            return;
        }

        sending = true;
        sendButton.setEnabled(false);
        sendButton.setText("\u2026");
        showStatus("Sending\u2026", COLOR_MUTED);

        sender.send(text, error -> SwingUtilities.invokeLater(() -> {
            sending = false;
            sendButton.setText("Send");
            if (error == null) {
                // Cleared only on success, so a failed send does not lose what was typed.
                input.setText("");
                showStatus("", null);
            } else {
                showStatus(error, COLOR_ERROR);
            }
            syncSendEnabled();
        }));
    }

    private void syncSendEnabled() {
        if (sending) {
            return;
        }
        int length = input.getText().trim().length();
        sendButton.setEnabled(input.isEnabled() && length > 0 && length <= MAX_LENGTH);
        if (length > MAX_LENGTH) {
            showStatus("Too long by " + (length - MAX_LENGTH) + " characters", COLOR_ERROR);
        } else if (COLOR_ERROR.equals(statusLabel.getForeground())) {
            showStatus("", null);
        }
    }

    private boolean isScrolledToBottom() {
        JScrollBar bar = scrollPane.getVerticalScrollBar();
        if (!bar.isVisible()) {
            return true;
        }
        // A few pixels of slack: dragging to the very end rarely lands exactly on it.
        return bar.getValue() + bar.getVisibleAmount() >= bar.getMaximum() - 8;
    }

    private void scrollToBottom() {
        JScrollBar bar = scrollPane.getVerticalScrollBar();
        bar.setValue(bar.getMaximum());
    }
}
