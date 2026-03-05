import javax.swing.*;
import javax.swing.border.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.*;
import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.Properties;

/**
 * Nimbus Prime — Premium Main UI
 * 
 * Hardware-Adaptive Minecraft Launcher with modern Glassmorphism aesthetics.
 */
public class NimbusPrime extends JFrame {

    // ── Theme System ──────────────────────────────────────────────
    public record Theme(String name, Color top, Color bottom, Color accent) {}
    private static final Theme VOID = new Theme("Void", new Color(20, 20, 30), new Color(10, 10, 15), new Color(74, 158, 255));
    private static final Theme EMERALD = new Theme("Emerald", new Color(10, 30, 20), new Color(5, 15, 10), new Color(80, 255, 150));
    private static final Theme RUBY = new Theme("Ruby", new Color(40, 15, 15), new Color(20, 5, 5), new Color(255, 80, 80));
    private static final Theme AMETHYST = new Theme("Amethyst", new Color(30, 15, 40), new Color(15, 5, 20), new Color(180, 100, 255));
    
    private Theme currentTheme = VOID;
    private Image logo;

    private static final Color BG_TOP        = VOID.top;
    private static final Color BG_BOTTOM     = VOID.bottom;
    private static final Color ACCENT        = VOID.accent;
    private static final Color TEXT_MAIN     = new Color(240, 240, 255);
    private static final Color TEXT_DIM      = new Color(150, 150, 180);
    private static final Color BORDER        = new Color(255, 255, 255, 20);
    private static final Color FIELD_BG      = new Color(255, 255, 255, 10);

    // ── Fonts ──────────────────────────────────────────────────────
    private static final Font FONT_TITLE  = new Font("Segoe UI Semibold", Font.PLAIN, 28);
    private static final Font FONT_REG    = new Font("Segoe UI", Font.PLAIN, 14);
    private static final Font FONT_SMALL  = new Font("Segoe UI", Font.PLAIN, 12);
    private static final Font FONT_MONO   = new Font("Consolas", Font.PLAIN, 12);

    private final NimbusTextField usernameField;
    private final NimbusTextField versionField;
    private final NimbusTextField mcDirField;
    private final NimbusTextField javaPathField;
    private final NimbusButton launchBtn;
    private final JLabel statusLabel;
    private final LaunchEngine.HardwareProfile profile;

    public NimbusPrime() {
        super("Nimbus Prime");
        
        // Load Logo — use nimbus.home so it resolves regardless of working directory
        String nimbusHome = System.getProperty("nimbus.home", ".");
        try {
            logo = new ImageIcon(nimbusHome + File.separator + "logo.webp").getImage();
            if (logo.getWidth(null) <= 0) logo = null; // ImageIcon silently creates broken Images
        } catch (Exception e) {
            System.err.println("Could not load logo: " + e.getMessage());
        }

        // Warmup engine in background
        LaunchEngine.warmup();
        profile = LaunchEngine.auditHardware();

        setUndecorated(true);
        setBackground(new Color(0, 0, 0, 0));
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        
        JPanel root = new JPanel(new BorderLayout()) {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                
                // Gradient Background
                GradientPaint gp = new GradientPaint(0, 0, currentTheme.top, 0, getHeight(), currentTheme.bottom);
                g2.setPaint(gp);
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), 24, 24);
                
                // Subtle Glow
                g2.setColor(new Color(currentTheme.accent.getRed(), currentTheme.accent.getGreen(), currentTheme.accent.getBlue(), 15));
                g2.fillOval(getWidth() - 200, -100, 400, 400);

                // Border
                g2.setColor(BORDER);
                g2.setStroke(new BasicStroke(1.5f));
                g2.drawRoundRect(1, 1, getWidth()-2, getHeight()-2, 24, 24);
                g2.dispose();
            }
        };
        root.setOpaque(false);
        root.setBorder(new EmptyBorder(30, 40, 30, 40));

        // ── Header ──────────────────────────────────────────────────
        JPanel header = new JPanel(new BorderLayout(15, 0));
        header.setOpaque(false);
        
        // Drag Handle
        JLabel handle = new JLabel("⠿", SwingConstants.CENTER);
        handle.setForeground(BORDER);
        handle.setFont(new Font("Monospaced", Font.BOLD, 18));
        header.add(handle, BorderLayout.NORTH);

        // Logo — 60×60 with a subtle accent-ring border for premium look
        if (logo != null) {
            JLabel logoLabel = new JLabel() {
                final Image scaled = logo.getScaledInstance(56, 56, Image.SCALE_SMOOTH);
                float pulse = 0f;
                javax.swing.Timer pulseTimer = new javax.swing.Timer(50, e -> {
                    pulse += 0.05f;
                    repaint();
                });
                { pulseTimer.start(); }

                @Override protected void paintComponent(Graphics g) {
                    Graphics2D g2 = (Graphics2D) g.create();
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    
                    float glowOpacity = 0.3f + 0.15f * (float)Math.sin(pulse);
                    
                    // Accent glow ring
                    g2.setColor(new Color(currentTheme.accent.getRed(),
                        currentTheme.accent.getGreen(), currentTheme.accent.getBlue(), (int)(255 * glowOpacity)));
                    g2.fillOval(0, 0, 62, 62);
                    
                    g2.setColor(new Color(currentTheme.accent.getRed(),
                        currentTheme.accent.getGreen(), currentTheme.accent.getBlue(), 120));
                    g2.setStroke(new BasicStroke(2.0f));
                    g2.drawOval(1, 1, 60, 60);
                    
                    // Clip to circle
                    g2.setClip(new java.awt.geom.Ellipse2D.Float(3, 3, 56, 56));
                    g2.drawImage(scaled, 3, 3, this);
                    g2.dispose();
                }
                @Override public Dimension getPreferredSize() { return new Dimension(66, 66); }
            };
            header.add(logoLabel, BorderLayout.WEST);
        }

        JLabel title = new JLabel("NIMBUS");
        title.setFont(FONT_TITLE);
        title.setForeground(TEXT_MAIN);
        
        JLabel subtitle = new JLabel(profile.label());
        subtitle.setFont(FONT_SMALL);
        subtitle.setForeground(currentTheme.accent);
        
        JPanel titleGroup = new JPanel(new GridLayout(2, 1));
        titleGroup.setOpaque(false);
        titleGroup.add(title);
        titleGroup.add(subtitle);
        
        header.add(titleGroup, BorderLayout.CENTER);
        
        JPanel controls = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0));
        controls.setOpaque(false);

        // Theme Switcher Component
        String[] themes = {"VOID", "EMERALD", "RUBY", "AMETHYST"};
        JComboBox<String> themeBox = new JComboBox<>(themes);
        themeBox.setFont(FONT_SMALL);
        themeBox.setBackground(BG_TOP);
        themeBox.setForeground(TEXT_MAIN);
        themeBox.addActionListener(e -> {
            switch((String)themeBox.getSelectedItem()) {
                case "VOID" -> currentTheme = VOID;
                case "EMERALD" -> currentTheme = EMERALD;
                case "RUBY" -> currentTheme = RUBY;
                case "AMETHYST" -> currentTheme = AMETHYST;
            }
            repaint();
            updateComponentThemes();
        });
        controls.add(themeBox);

        JButton closeBtn = new JButton("×");
        closeBtn.setFont(new Font("Arial", Font.PLAIN, 24));
        closeBtn.setForeground(TEXT_DIM);
        closeBtn.setBorder(null);
        closeBtn.setContentAreaFilled(false);
        closeBtn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        closeBtn.addActionListener(e -> System.exit(0));
        controls.add(closeBtn);

        header.add(controls, BorderLayout.EAST);

        // ── Body ────────────────────────────────────────────────────
        JPanel body = new JPanel(new GridBagLayout());
        body.setOpaque(false);
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.insets = new Insets(10, 0, 10, 0);
        gbc.weightx = 1.0;
        gbc.gridx = 0;

        usernameField = new NimbusTextField("Username", "Player");
        versionField  = new NimbusTextField("Game Version", "1.21");
        mcDirField    = new NimbusTextField(".minecraft Directory", defaultMinecraftDir());
        javaPathField = new NimbusTextField("Java Runtime", detectJavaPath());

        int row = 0;
        gbc.gridy = row++; body.add(usernameField, gbc);
        gbc.gridy = row++; body.add(versionField, gbc);
        gbc.gridy = row++; body.add(mcDirField, gbc);
        gbc.gridy = row++; body.add(javaPathField, gbc);

        // ── Footer ──────────────────────────────────────────────────
        JPanel footer = new JPanel(new BorderLayout(0, 15));
        footer.setOpaque(false);
        
        launchBtn = new NimbusButton("LAUNCH MINECRAFT");
        launchBtn.addActionListener(e -> onLaunchClicked());
        
        footer.add(launchBtn, BorderLayout.CENTER);
        
        JPanel bottomGroup = new JPanel(new GridLayout(2, 1, 0, 5));
        bottomGroup.setOpaque(false);
        
        statusLabel = new JLabel("SYSTEM READY", SwingConstants.CENTER);
        statusLabel.setFont(FONT_SMALL);
        statusLabel.setForeground(TEXT_DIM);
        
        JLabel creditLabel = new JLabel("Made by Nishant with ❤️ for Minecraft community", SwingConstants.CENTER);
        creditLabel.setFont(new Font("Segoe UI", Font.ITALIC, 11));
        creditLabel.setForeground(TEXT_DIM);
        
        bottomGroup.add(statusLabel);
        bottomGroup.add(creditLabel);
        
        footer.add(bottomGroup, BorderLayout.SOUTH);

        // Drag Handle Support
        final Point dragOffset = new Point();
        header.addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) {
                dragOffset.x = e.getX();
                dragOffset.y = e.getY();
            }
        });
        header.addMouseMotionListener(new MouseMotionAdapter() {
            @Override public void mouseDragged(MouseEvent e) {
                setLocation(e.getXOnScreen() - dragOffset.x, e.getYOnScreen() - dragOffset.y);
            }
        });

        root.add(header, BorderLayout.NORTH);
        root.add(body, BorderLayout.CENTER);
        root.add(footer, BorderLayout.SOUTH);

        setContentPane(root);
        loadSettings();
        pack();
        setSize(520, 640);
        setLocationRelativeTo(null);

        // Window dragging
        MouseAdapter ma = new MouseAdapter() {
            private Point clickPoint;
            @Override public void mousePressed(MouseEvent e) { clickPoint = e.getPoint(); }
            @Override public void mouseDragged(MouseEvent e) {
                Point p = e.getLocationOnScreen();
                setLocation(p.x - clickPoint.x, p.y - clickPoint.y);
            }
        };
        root.addMouseListener(ma);
        root.addMouseMotionListener(ma);
    }

    private void onLaunchClicked() {
        if (usernameField.getText().isEmpty()) {
            setStatus("ERROR: Username required", Color.RED);
            return;
        }
        launchBtn.setEnabled(false);
        saveSettings();
        showEmotionalExit();
    }

    private void showEmotionalExit() {
        String user    = usernameField.getText();
        String ver     = versionField.getText();
        Path   mcDir   = Path.of(mcDirField.getText());
        Path   javaExe = Path.of(javaPathField.getText());

        // ── Cinematic overlay panel ───────────────────────────────
        JPanel glass = new JPanel(null) {
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                GradientPaint gp = new GradientPaint(0, 0, BG_TOP, 0, getHeight(), new Color(5, 5, 10));
                g2.setPaint(gp);
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), 24, 24);
                g2.dispose();
            }
        };
        glass.setOpaque(false);
        setContentPane(glass);
        revalidate();

        // ── Emotional copy ────────────────────────────────────────
        JLabel line1 = new JLabel("I've given you every ounce of my strength\u2026", SwingConstants.CENTER);
        line1.setFont(new Font("Segoe UI", Font.ITALIC, 16));
        line1.setForeground(new Color(255, 255, 255, 0));

        JLabel star = new JLabel("\u2736", SwingConstants.CENTER);
        star.setFont(new Font("Segoe UI", Font.PLAIN, 22));
        star.setForeground(new Color(currentTheme.accent.getRed(),
            currentTheme.accent.getGreen(), currentTheme.accent.getBlue(), 0));

        JLabel line2 = new JLabel("Your journey begins", SwingConstants.CENTER);
        line2.setFont(new Font("Segoe UI Semibold", Font.PLAIN, 26));
        line2.setForeground(new Color(currentTheme.accent.getRed(),
            currentTheme.accent.getGreen(), currentTheme.accent.getBlue(), 0));

        JLabel progressLine = new JLabel("", SwingConstants.CENTER);
        progressLine.setFont(FONT_SMALL);
        progressLine.setForeground(new Color(150, 150, 180, 200));

        // Vertical centering: window is 500×600
        line1.setBounds(0, 215, 500, 30);
        star.setBounds(0,  258, 500, 30);
        line2.setBounds(0,  298, 500, 44);
        progressLine.setBounds(0, 548, 500, 24);

        glass.add(line1);
        glass.add(star);
        glass.add(line2);
        glass.add(progressLine);

        javax.swing.Timer anim = new javax.swing.Timer(30, null);
        final int[]     frame        = {0};
        final int[]     exitDelay    = {0};
        final boolean[] launchStarted = {false};
        final boolean[] launchDone    = {false};
        final long      startTime     = System.currentTimeMillis();

        anim.addActionListener(e -> {
            frame[0]++;

            // Phase A — line 1 fades in over 60 frames (~1.8 s)
            if (frame[0] <= 60) {
                int a = (int)(255 * frame[0] / 60f);
                line1.setForeground(new Color(255, 255, 255, a));
            }

            // Phase B — kick off background launch at frame 10 (invisible to user)
            if (frame[0] == 10 && !launchStarted[0]) {
                launchStarted[0] = true;
                new SwingWorker<Void, String>() {
                    @Override protected Void doInBackground() throws Exception {
                        LaunchEngine.ensureEnvironment(mcDir, ver, this::publish);
                        String json = Files.readString(
                            mcDir.resolve("versions").resolve(ver).resolve(ver + ".json"),
                            StandardCharsets.UTF_8);
                        LaunchEngine.launchGame(mcDir, ver, user, javaExe, profile, json);
                        LaunchEngine.waitForLogMarker("Sound engine started", 60);
                        long duration = System.currentTimeMillis() - startTime;
                        LaunchEngine.logLoadTime(duration);
                        return null;
                    }
                    @Override protected void process(List<String> chunks) {
                        progressLine.setText(chunks.get(chunks.size() - 1).toUpperCase());
                    }
                    @Override protected void done() {
                        try { get(); launchDone[0] = true; }
                        catch (Exception ex) {
                            JOptionPane.showMessageDialog(NimbusPrime.this,
                                "Launch Failed: " + ex.getCause().getMessage());
                            System.exit(1);
                        }
                    }
                }.execute();
            }

            // Phase C — game is alive: fade out progress, pulse in star then line 2
            if (launchDone[0]) {
                // Fade out progress text
                Color pc = progressLine.getForeground();
                if (pc.getAlpha() > 0)
                    progressLine.setForeground(new Color(pc.getRed(), pc.getGreen(),
                        pc.getBlue(), Math.max(0, pc.getAlpha() - 8)));

                // Star pulses in slowly
                Color sc = star.getForeground();
                if (sc.getAlpha() < 255)
                    star.setForeground(new Color(sc.getRed(), sc.getGreen(), sc.getBlue(),
                        Math.min(255, sc.getAlpha() + 5)));

                // Line 2 begins once star is half-visible
                Color l2 = line2.getForeground();
                if (star.getForeground().getAlpha() > 100 && l2.getAlpha() < 255)
                    line2.setForeground(new Color(l2.getRed(), l2.getGreen(), l2.getBlue(),
                        Math.min(255, l2.getAlpha() + 5)));

                // Phase D — everything visible → buffer → exit
                if (line2.getForeground().getAlpha() >= 255) {
                    exitDelay[0]++;
                    if (exitDelay[0] > 110) { // ~3.3 s
                        anim.stop();
                        System.exit(0);
                    }
                }
            }
            repaint();
        });
        anim.start();
    }


    private void updateComponentThemes() {
        launchBtn.repaint();
    }

    private void setStatus(String text, Color color) {
        statusLabel.setText(text.toUpperCase());
        statusLabel.setForeground(color);
    }

    // ── Custom Premium Components ─────────────────────────────────

    class NimbusTextField extends JPanel {
        private final JTextField field;
        public NimbusTextField(String label, String initial) {
            setLayout(new BorderLayout(0, 5));
            setOpaque(false);
            
            JLabel lbl = new JLabel(label.toUpperCase());
            lbl.setFont(FONT_SMALL);
            lbl.setForeground(TEXT_DIM);
            add(lbl, BorderLayout.NORTH);
            
            field = new JTextField(initial) {
                @Override protected void paintComponent(Graphics g) {
                    Graphics2D g2 = (Graphics2D) g.create();
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    g2.setColor(FIELD_BG);
                    g2.fillRoundRect(0, 0, getWidth(), getHeight(), 12, 12);
                    if (isFocusOwner()) {
                        g2.setColor(ACCENT);
                        g2.drawRoundRect(0, 0, getWidth()-1, getHeight()-1, 12, 12);
                    }
                    g2.dispose();
                    super.paintComponent(g);
                }
            };
            field.setFont(FONT_REG);
            field.setForeground(TEXT_MAIN);
            field.setCaretColor(ACCENT);
            field.setOpaque(false);
            field.setBorder(new EmptyBorder(10, 15, 10, 15));
            add(field, BorderLayout.CENTER);
        }
        public String getText() { return field.getText().trim(); }
        public void setText(String t) { field.setText(t); }
    }

    class NimbusButton extends JButton {
        private float hoverAlpha = 0f;
        public NimbusButton(String text) {
            super(text);
            setFont(FONT_REG);
            setForeground(Color.WHITE);
            setContentAreaFilled(false);
            setBorderPainted(false);
            setFocusPainted(false);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            
            addMouseListener(new MouseAdapter() {
                @Override public void mouseEntered(MouseEvent e) { animateHover(true); }
                @Override public void mouseExited(MouseEvent e) { animateHover(false); }
            });
        }

        private void animateHover(boolean in) {
            javax.swing.Timer t = new javax.swing.Timer(15, null);
            t.addActionListener(e -> {
                hoverAlpha += in ? 0.1f : -0.1f;
                if (hoverAlpha >= 1f) { hoverAlpha = 1f; t.stop(); }
                if (hoverAlpha <= 0f) { hoverAlpha = 0f; t.stop(); }
                repaint();
            });
            t.start();
        }

        @Override protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            
            // Base fill
            g2.setColor(NimbusPrime.this.currentTheme.accent);
            g2.fillRoundRect(0, 0, getWidth(), getHeight(), 12, 12);
            
            // Hover Overlay
            g2.setColor(new Color(255, 255, 255, (int)(30 * hoverAlpha)));
            g2.fillRoundRect(0, 0, getWidth(), getHeight(), 12, 12);
            
            // Glow
            if (hoverAlpha > 0) {
                g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, hoverAlpha));
                g2.setColor(new Color(NimbusPrime.this.currentTheme.accent.getRed(), 
                                     NimbusPrime.this.currentTheme.accent.getGreen(), 
                                     NimbusPrime.this.currentTheme.accent.getBlue(), 40));
                g2.setStroke(new BasicStroke(4f));
                g2.drawRoundRect(0, 0, getWidth(), getHeight(), 12, 12);
            }
            
            g2.dispose();
            super.paintComponent(g);
        }
    }

    private static String defaultMinecraftDir() {
        String os = System.getProperty("os.name").toLowerCase();
        String home = System.getProperty("user.home");
        if (os.contains("win")) return home + "\\AppData\\Roaming\\.minecraft";
        if (os.contains("mac")) return home + "/Library/Application Support/minecraft";
        return home + "/.minecraft";
    }

    private static String detectJavaPath() {
        return ProcessHandle.current().info().command()
            .orElse(System.getProperty("java.home") + File.separator + "bin" + File.separator + "java" + 
                (System.getProperty("os.name").toLowerCase().contains("win") ? ".exe" : ""));
    }

    private void loadSettings() {
        Properties props = new Properties();
        try (InputStream in = new FileInputStream("config.properties")) {
            props.load(in);
            usernameField.setText(props.getProperty("username", "Player"));
            versionField.setText(props.getProperty("version", "1.21"));
            mcDirField.setText(props.getProperty("mcDir", defaultMinecraftDir()));
            javaPathField.setText(props.getProperty("javaPath", detectJavaPath()));
        } catch (IOException ignored) {}
    }

    private void saveSettings() {
        Properties props = new Properties();
        props.setProperty("username", usernameField.getText());
        props.setProperty("version", versionField.getText());
        props.setProperty("mcDir", mcDirField.getText());
        props.setProperty("javaPath", javaPathField.getText());
        try (OutputStream out = new FileOutputStream("config.properties")) {
            props.store(out, "Nimbus Prime Settings");
        } catch (IOException ignored) {}
    }

    public static void main(String[] args) {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {}
        SwingUtilities.invokeLater(() -> new NimbusPrime().setVisible(true));
    }
}
