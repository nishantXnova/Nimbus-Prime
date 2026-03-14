import javax.swing.*;
import javax.swing.Timer;
import java.awt.*;
import java.io.*;
import java.nio.file.Path;
import java.util.*;
import java.util.List;

/**
 * 🌌 Nimbus Prime — Production Release (v1.1.0-HP+)
 * 
 * Hardware-adaptive Minecraft launcher with Zero-Latency "Self-Destruct" sequence.
 */
public class NimbusPrime extends JFrame {

    private static final Color BG_DARK      = new Color(10, 10, 15);
    private static final Color ACCENT_BLUE  = new Color(60, 120, 255);
    private static final Color TEXT_DIM     = new Color(160, 160, 180);
    private static final Font  FONT_TITLE   = new Font("Inter", Font.BOLD, 22);
    private static final Font  FONT_SMALL   = new Font("Inter", Font.PLAIN, 12);

    private final NimbusTextField usernameField;
    private final NimbusTextField versionField;
    private final NimbusTextField mcDirField;
    private final NimbusTextField javaPathField;
    private final JCheckBox priorityBoostCheck;
    private final JCheckBox autoCleanLogsCheck;
    private final NimbusButton launchBtn;
    private final JLabel statusLabel;
    private final LaunchEngine engine = new LaunchEngine();
    private final LaunchEngine.HardwareProfile profile;

    public NimbusPrime() {
        setTitle("Nimbus Prime");
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setResizable(false);
        profile = LaunchEngine.auditHardware();

        // ── Root Panel ──────────────────────────────────────────────
        JPanel root = new JPanel(new BorderLayout(0, 20)) {
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setPaint(new GradientPaint(0, 0, BG_DARK, 0, getHeight(), BG_DARK.darker()));
                g2.fillRect(0, 0, getWidth(), getHeight());
                g2.dispose();
            }
        };
        root.setBorder(BorderFactory.createEmptyBorder(30, 30, 30, 30));

        // ── Header ──────────────────────────────────────────────────
        JLabel titleLabel = new JLabel("NIMBUS PRIME");
        titleLabel.setFont(FONT_TITLE);
        titleLabel.setForeground(Color.WHITE);
        titleLabel.setHorizontalAlignment(SwingConstants.CENTER);
        root.add(titleLabel, BorderLayout.NORTH);

        // ── Body ────────────────────────────────────────────────────
        JPanel body = new JPanel(new GridBagLayout());
        body.setOpaque(false);
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.insets = new Insets(5, 0, 5, 0);
        gbc.weightx = 1.0;
        int row = 0;

        usernameField = new NimbusTextField("Username", "NimbusPlayer");
        versionField = new NimbusTextField("Version", "1.21");
        mcDirField = new NimbusTextField("Minecraft Path", defaultMinecraftDir());
        javaPathField = new NimbusTextField("Java Path", "java");

        gbc.gridy = row++; body.add(usernameField, gbc);
        gbc.gridy = row++; body.add(versionField, gbc);
        gbc.gridy = row++; body.add(mcDirField, gbc);
        gbc.gridy = row++; body.add(javaPathField, gbc);

        JPanel settingsPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 20, 10));
        settingsPanel.setOpaque(false);
        priorityBoostCheck = new JCheckBox("PRIORITY BOOST", true);
        priorityBoostCheck.setForeground(TEXT_DIM);
        priorityBoostCheck.setOpaque(false);
        autoCleanLogsCheck = new JCheckBox("AUTO-CLEAN LOGS", false);
        autoCleanLogsCheck.setForeground(TEXT_DIM);
        autoCleanLogsCheck.setOpaque(false);
        settingsPanel.add(priorityBoostCheck);
        settingsPanel.add(autoCleanLogsCheck);
        gbc.gridy = row++; body.add(settingsPanel, gbc);

        root.add(body, BorderLayout.CENTER);

        // ── Footer ──────────────────────────────────────────────────
        JPanel footer = new JPanel(new BorderLayout(0, 15));
        footer.setOpaque(false);

        statusLabel = new JLabel(profile.label());
        statusLabel.setFont(FONT_SMALL);
        statusLabel.setForeground(ACCENT_BLUE);
        statusLabel.setHorizontalAlignment(SwingConstants.CENTER);
        footer.add(statusLabel, BorderLayout.NORTH);

        launchBtn = new NimbusButton("LAUNCH ENGINE");
        launchBtn.addActionListener(e -> onLaunchClicked());
        footer.add(launchBtn, BorderLayout.SOUTH);

        root.add(footer, BorderLayout.SOUTH);

        engine.prepareEarly(Path.of(defaultMinecraftDir()), "1.21", statusLabel::setText);

        setContentPane(root);
        loadSettings();
        pack();
        setLocationRelativeTo(null);
    }

    private void onLaunchClicked() {
        saveSettings();
        launchBtn.setEnabled(false);
        showEmotionalExit();
    }

    private void showEmotionalExit() {
        final String user = usernameField.getText();
        final String ver = versionField.getText();
        final Path mcDir = Path.of(mcDirField.getText());
        final Path javaPath = Path.of(javaPathField.getText());
        final long startTime = System.currentTimeMillis();

        new SwingWorker<Void, String>() {
            @Override protected Void doInBackground() throws Exception {
                publish("🛡️ Asset Guardian standing by...");
                engine.ensureEnvironment(mcDir, ver, this::publish);
                
                publish("🚀 Launching Minecraft...");
                engine.launchGame(mcDir, ver, user, javaPath, profile);
                
                // Scientific Sync: Wait for the game to actually reach the sound engine
                engine.waitForLogMarker("Sound engine started", 60);
                return null;
            }

            @Override protected void process(List<String> chunks) {
                statusLabel.setText(chunks.get(chunks.size() - 1));
            }

            @Override protected void done() {
                long duration = System.currentTimeMillis() - startTime;
                System.out.println("[Nimbus] Zero-Latency Launch: " + duration + "ms");
                System.exit(0);
            }
        }.execute();

        // Immediate Alpha Fade
        new Timer(20, e -> {
            float alpha = getOpacity();
            if (alpha > 0.1f) {
                setOpacity(alpha - 0.05f);
            } else {
                ((Timer)e.getSource()).stop();
            }
        }).start();
    }

    private String defaultMinecraftDir() {
        return System.getProperty("user.home") + "\\AppData\\Roaming\\.minecraft";
    }

    private void loadSettings() {
        Properties props = new Properties();
        try (InputStream in = new FileInputStream("config.properties")) {
            props.load(in);
            usernameField.setText(props.getProperty("username", "NimbusPlayer"));
            versionField.setText(props.getProperty("version", "1.21"));
            mcDirField.setText(props.getProperty("mcDir", defaultMinecraftDir()));
            javaPathField.setText(props.getProperty("javaPath", "java"));
            priorityBoostCheck.setSelected(Boolean.parseBoolean(props.getProperty("priorityBoost", "true")));
            autoCleanLogsCheck.setSelected(Boolean.parseBoolean(props.getProperty("autoCleanLogs", "false")));
        } catch (IOException ignored) {}
    }

    private void saveSettings() {
        Properties props = new Properties();
        props.setProperty("username", usernameField.getText());
        props.setProperty("version", versionField.getText());
        props.setProperty("mcDir", mcDirField.getText());
        props.setProperty("javaPath", javaPathField.getText());
        props.setProperty("priorityBoost", String.valueOf(priorityBoostCheck.isSelected()));
        props.setProperty("autoCleanLogs", String.valueOf(autoCleanLogsCheck.isSelected()));
        try (OutputStream out = new FileOutputStream("config.properties")) {
            props.store(out, null);
        } catch (IOException ignored) {}
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
                new NimbusPrime().setVisible(true);
            } catch (Exception ignored) {}
        });
    }

    // ── Custom Components ──────────────────────────────────────────
    static class NimbusTextField extends JPanel {
        private final JTextField field;
        public NimbusTextField(String label, String value) {
            setLayout(new BorderLayout(5, 5));
            setOpaque(false);
            JLabel l = new JLabel(label);
            l.setFont(FONT_SMALL);
            l.setForeground(TEXT_DIM);
            add(l, BorderLayout.NORTH);
            field = new JTextField(value);
            field.setBackground(new Color(30, 30, 40));
            field.setForeground(Color.WHITE);
            field.setCaretColor(ACCENT_BLUE);
            field.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(50, 50, 70)),
                BorderFactory.createEmptyBorder(5, 10, 5, 10)));
            add(field, BorderLayout.CENTER);
        }
        public String getText() { return field.getText(); }
        public void setText(String t) { field.setText(t); }
    }

    static class NimbusButton extends JButton {
        public NimbusButton(String text) {
            super(text);
            setFont(FONT_TITLE.deriveFont(14f));
            setForeground(Color.WHITE);
            setBackground(ACCENT_BLUE);
            setFocusPainted(false);
            setBorder(BorderFactory.createEmptyBorder(12, 0, 12, 0));
            setCursor(new Cursor(Cursor.HAND_CURSOR));
        }
        @Override protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(getBackground());
            if (getModel().isPressed()) g2.setColor(getBackground().darker());
            else if (getModel().isRollover()) g2.setColor(getBackground().brighter());
            g2.fillRoundRect(0, 0, getWidth(), getHeight(), 10, 10);
            g2.setColor(Color.WHITE);
            FontMetrics fm = g2.getFontMetrics();
            int x = (getWidth() - fm.stringWidth(getText())) / 2;
            int y = (getHeight() + fm.getAscent() - fm.getDescent()) / 2;
            g2.drawString(getText(), x, y);
            g2.dispose();
        }
    }
}
