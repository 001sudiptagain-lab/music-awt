import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;
import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSlider;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

/** YouTube search and playback in a dark, music-focused desktop interface. */
public class YouTubeMusicPlayer extends JFrame {
    private static final String YOUTUBE = "https://www.youtube.com/watch?v=";
    private static final Color BACKGROUND = new Color(15, 15, 15);
    private static final Color SURFACE = new Color(25, 25, 25);
    private static final Color MUTED = new Color(170, 170, 170);
    private static final Color ACCENT = new Color(255, 54, 72);
    private static final int RECENT_LIMIT = 50;

    private final HintTextField searchField = new HintTextField("Search songs, albums, artists...");
    private final DefaultListModel<Track> tracks = new DefaultListModel<Track>();
    private final DefaultListModel<Track> recentTracks = new DefaultListModel<Track>();
    private final JList<Track> resultsList = new JList<Track>(tracks);
    private final Preferences libraryPreferences = Preferences.userRoot().node("YouTubeMusicPlayer");
    private final JLabel status = new JLabel("Search YouTube to find something to play.");
    private final JLabel nowPlaying = new JLabel("Nothing playing");
    private final JLabel resultCount = new JLabel("Search to get started");
    private final JLabel elapsedLabel = new JLabel("0:00");
    private final JLabel durationLabel = new JLabel("--:--");
    private final JSlider progress = new JSlider(0, 1000, 0);
    private final Timer progressTimer = new Timer(500, e -> updatePlaybackProgress());
    private final ExecutorService worker = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "youtube-search-worker");
        t.setDaemon(true);
        return t;
    });

    private volatile Process playbackProcess;
    private volatile int playbackGeneration;
    private Track currentTrack;
    private int playbackOffsetSeconds;
    private long playbackStartedAtMillis;
    private boolean updatingProgress;
    private JButton searchButton;
    private JButton playButton;
    private JButton previousButton;
    private JButton nextButton;
    private JButton homeNav;
    private JButton exploreNav;
    private JButton libraryNav;
    private JLabel pageTitle;
    private JPanel pageContent;
    private JScrollPane pageScroll;
    private Page currentPage = Page.HOME;

    public YouTubeMusicPlayer() {
        super("Music");
        setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        setMinimumSize(new Dimension(900, 620));
        setSize(1100, 760);
        getContentPane().setBackground(BACKGROUND);
        loadRecentTracks();

        JPanel root = new JPanel(new BorderLayout());
        root.setBackground(BACKGROUND);
        root.add(createSidebar(), BorderLayout.WEST);
        root.add(createMainPanel(), BorderLayout.CENTER);
        root.add(createPlayerBar(), BorderLayout.SOUTH);
        setContentPane(root);

        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                stopPlayback();
                worker.shutdownNow();
                dispose();
            }
        });
        setLocationRelativeTo(null);
        setVisible(true);
    }

    private JPanel createSidebar() {
        JPanel sidebar = new JPanel(new BorderLayout());
        sidebar.setBackground(new Color(10, 10, 10));
        sidebar.setPreferredSize(new Dimension(220, 0));
        sidebar.setBorder(BorderFactory.createEmptyBorder(24, 18, 18, 14));

        JPanel top = new JPanel();
        top.setOpaque(false);
        top.setLayout(new javax.swing.BoxLayout(top, javax.swing.BoxLayout.Y_AXIS));

        JLabel brand = new JLabel("  \u25b6  Music");
        brand.setForeground(Color.WHITE);
        brand.setFont(new Font("Dialog", Font.BOLD, 23));
        brand.setAlignmentX(Component.LEFT_ALIGNMENT);
        top.add(brand);
        top.add(javax.swing.Box.createVerticalStrut(38));
        homeNav = navItem("\u2302", "Home");
        exploreNav = navItem("\u25ce", "Explore");
        libraryNav = navItem("\u266b", "Library");
        homeNav.addActionListener(e -> showPage(Page.HOME));
        exploreNav.addActionListener(e -> showPage(Page.EXPLORE));
        libraryNav.addActionListener(e -> showPage(Page.LIBRARY));
        top.add(homeNav);
        top.add(javax.swing.Box.createVerticalStrut(8));
        top.add(exploreNav);
        top.add(javax.swing.Box.createVerticalStrut(8));
        top.add(libraryNav);

        JPanel bottom = new JPanel(new BorderLayout());
        bottom.setOpaque(false);
        JLabel note = new JLabel("<html><div style='width:155px'>Search and play music from YouTube.<br><br>Use content you have permission to access.</div></html>");
        note.setForeground(new Color(125, 125, 125));
        note.setFont(new Font("Dialog", Font.PLAIN, 11));
        bottom.add(note, BorderLayout.SOUTH);

        sidebar.add(top, BorderLayout.NORTH);
        sidebar.add(bottom, BorderLayout.SOUTH);
        return sidebar;
    }

    private JButton navItem(String icon, String text) {
        JButton item = new JButton("  " + icon + "     " + text);
        item.setHorizontalAlignment(SwingConstants.LEFT);
        item.setFont(new Font("Dialog", Font.PLAIN, 14));
        item.setForeground(new Color(180, 180, 180));
        item.setBackground(new Color(10, 10, 10));
        item.setBorder(BorderFactory.createEmptyBorder(12, 8, 12, 8));
        item.setFocusPainted(false);
        item.setBorderPainted(false);
        item.setOpaque(true);
        item.setCursor(new java.awt.Cursor(java.awt.Cursor.HAND_CURSOR));
        item.setAlignmentX(Component.LEFT_ALIGNMENT);
        item.setMaximumSize(new Dimension(Integer.MAX_VALUE, 46));
        return item;
    }

    private JPanel createMainPanel() {
        JPanel main = new JPanel(new BorderLayout());
        main.setBackground(BACKGROUND);

        JPanel header = new JPanel(new BorderLayout(24, 0));
        header.setBackground(BACKGROUND);
        header.setBorder(BorderFactory.createEmptyBorder(18, 28, 16, 28));
        pageTitle = new JLabel("Home");
        pageTitle.setForeground(Color.WHITE);
        pageTitle.setFont(new Font("Dialog", Font.BOLD, 22));

        JPanel search = new JPanel(new BorderLayout(10, 0));
        search.setOpaque(false);
        searchField.setFont(new Font("Dialog", Font.PLAIN, 14));
        searchField.setForeground(Color.WHITE);
        searchField.setCaretColor(Color.WHITE);
        searchField.setBackground(new Color(38, 38, 38));
        searchField.setBorder(BorderFactory.createEmptyBorder(0, 16, 0, 14));
        searchField.addActionListener(e -> search());
        searchButton = new RoundedButton("Search", ACCENT, Color.WHITE);
        searchButton.setPreferredSize(new Dimension(100, 42));
        searchButton.addActionListener(e -> search());
        search.add(searchField, BorderLayout.CENTER);
        search.add(searchButton, BorderLayout.EAST);
        search.setPreferredSize(new Dimension(470, 44));
        header.add(pageTitle, BorderLayout.WEST);
        header.add(search, BorderLayout.EAST);

        resultsList.setBackground(BACKGROUND);
        resultsList.setForeground(Color.WHITE);
        resultsList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        resultsList.setFixedCellHeight(68);
        resultsList.setCellRenderer(new TrackRenderer());
        resultsList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting() && resultsList.getSelectedValue() != null) {
                Track selected = resultsList.getSelectedValue();
                if (currentTrack == null || playbackProcess == null) {
                    nowPlaying.setText(selected.title);
                    durationLabel.setText(formatTime(selected.durationSeconds));
                }
            }
            updateNavigationButtons();
        });
        resultsList.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                if (e.getClickCount() == 1) playSelected();
            }
        });

        pageContent = new JPanel();
        pageContent.setBackground(BACKGROUND);
        pageContent.setBorder(BorderFactory.createEmptyBorder());
        pageContent.setLayout(new javax.swing.BoxLayout(pageContent, javax.swing.BoxLayout.Y_AXIS));
        pageScroll = new JScrollPane(pageContent);
        pageScroll.setBorder(BorderFactory.createEmptyBorder());
        pageScroll.getViewport().setBackground(BACKGROUND);
        pageScroll.getVerticalScrollBar().setUnitIncrement(18);

        main.add(header, BorderLayout.NORTH);
        main.add(pageScroll, BorderLayout.CENTER);
        showPage(Page.HOME);
        return main;
    }

    private JPanel createHomeContent() {
        JPanel content = createPageContent();
        content.add(createHero("YOUR PERSONAL SOUNDTRACK", "Music for every mood",
                "Search a song or artist and let the next track find you."));
        content.add(javax.swing.Box.createVerticalStrut(30));
        content.add(createTrackListSection("Search results"));
        return content;
    }

    private JPanel createExploreContent() {
        JPanel content = createPageContent();
        content.add(createHero("FIND YOUR NEXT FAVORITE", "Explore music",
                "Browse genres or try a quick search on YouTube."));
        content.add(javax.swing.Box.createVerticalStrut(24));
        content.add(sectionHeading("Browse by genre"));
        content.add(chipRow(new String[] {
                "Pop", "Hip-hop", "Rock", "R&B", "Electronic",
                "Indie", "Jazz", "Classical", "Country", "Reggae"
        }, true));
        content.add(javax.swing.Box.createVerticalStrut(22));
        content.add(sectionHeading("Trending searches"));
        JLabel note = new JLabel("Quick searches on YouTube; results reflect the latest available videos.");
        note.setForeground(MUTED);
        note.setFont(new Font("Dialog", Font.PLAIN, 12));
        note.setAlignmentX(Component.LEFT_ALIGNMENT);
        content.add(note);
        content.add(javax.swing.Box.createVerticalStrut(8));
        content.add(chipRow(new String[] {
                "Trending songs", "New music", "Viral hits",
                "Chill playlist", "Workout playlist", "Throwback hits"
        }, false));
        return content;
    }

    private JPanel createLibraryContent() {
        JPanel content = createPageContent();
        if (recentTracks.isEmpty()) {
            content.add(sectionHeading("Recently played"));
            JLabel empty = new JLabel("<html><div style='padding:14px 0'>Tracks you play will appear here on this device.</div></html>");
            empty.setForeground(MUTED);
            empty.setFont(new Font("Dialog", Font.PLAIN, 13));
            empty.setAlignmentX(Component.LEFT_ALIGNMENT);
            content.add(empty);
        } else {
            content.add(createTrackListSection("Recently played"));
        }
        return content;
    }

    private JPanel createPageContent() {
        JPanel content = new JPanel();
        content.setBackground(BACKGROUND);
        content.setBorder(BorderFactory.createEmptyBorder(2, 28, 20, 28));
        content.setLayout(new javax.swing.BoxLayout(content, javax.swing.BoxLayout.Y_AXIS));
        content.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));
        content.setAlignmentX(Component.LEFT_ALIGNMENT);
        return content;
    }

    private JPanel createHero(String eyebrowText, String headlineText, String subtitleText) {
        HeroPanel hero = new HeroPanel();
        hero.setLayout(new BorderLayout());
        hero.setBorder(BorderFactory.createEmptyBorder(28, 32, 28, 32));
        JPanel heroText = new JPanel();
        heroText.setOpaque(false);
        heroText.setLayout(new javax.swing.BoxLayout(heroText, javax.swing.BoxLayout.Y_AXIS));
        JLabel eyebrow = new JLabel(eyebrowText);
        eyebrow.setForeground(new Color(255, 221, 225));
        eyebrow.setFont(new Font("Dialog", Font.BOLD, 11));
        JLabel headline = new JLabel(headlineText);
        headline.setForeground(Color.WHITE);
        headline.setFont(new Font("Dialog", Font.BOLD, 30));
        JLabel subtitle = new JLabel(subtitleText);
        subtitle.setForeground(new Color(245, 229, 231));
        subtitle.setFont(new Font("Dialog", Font.PLAIN, 14));
        heroText.add(eyebrow);
        heroText.add(javax.swing.Box.createVerticalStrut(10));
        heroText.add(headline);
        heroText.add(javax.swing.Box.createVerticalStrut(7));
        heroText.add(subtitle);
        hero.add(heroText, BorderLayout.CENTER);
        hero.setMaximumSize(new Dimension(Integer.MAX_VALUE, 190));
        hero.setAlignmentX(Component.LEFT_ALIGNMENT);
        return hero;
    }

    private JPanel createTrackListSection(String title) {
        JPanel section = new JPanel();
        section.setOpaque(false);
        section.setLayout(new javax.swing.BoxLayout(section, javax.swing.BoxLayout.Y_AXIS));
        JPanel sectionHeader = new JPanel(new BorderLayout());
        sectionHeader.setOpaque(false);
        sectionHeader.setMaximumSize(new Dimension(Integer.MAX_VALUE, 38));
        JLabel sectionTitle = new JLabel(title);
        sectionTitle.setForeground(Color.WHITE);
        sectionTitle.setFont(new Font("Dialog", Font.BOLD, 21));
        resultCount.setForeground(MUTED);
        resultCount.setFont(new Font("Dialog", Font.PLAIN, 12));
        sectionHeader.add(sectionTitle, BorderLayout.WEST);
        sectionHeader.add(resultCount, BorderLayout.EAST);
        section.add(sectionHeader);
        section.add(javax.swing.Box.createVerticalStrut(12));

        JScrollPane listScroll = new JScrollPane(resultsList);
        listScroll.setBorder(BorderFactory.createEmptyBorder());
        listScroll.getViewport().setBackground(BACKGROUND);
        listScroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        JPanel resultsArea = new JPanel(new BorderLayout());
        resultsArea.setOpaque(false);
        resultsArea.add(listScroll, BorderLayout.CENTER);
        resultsArea.setPreferredSize(new Dimension(900, 320));
        resultsArea.setMaximumSize(new Dimension(Integer.MAX_VALUE, 420));
        section.add(resultsArea);
        section.setAlignmentX(Component.LEFT_ALIGNMENT);
        return section;
    }

    private JLabel sectionHeading(String text) {
        JLabel heading = new JLabel(text);
        heading.setForeground(Color.WHITE);
        heading.setFont(new Font("Dialog", Font.BOLD, 20));
        heading.setAlignmentX(Component.LEFT_ALIGNMENT);
        return heading;
    }

    private JPanel chipRow(String[] labels, boolean genreSearch) {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 8));
        row.setOpaque(false);
        for (String label : labels) {
            JButton chip = new RoundedButton(label, new Color(48, 48, 48), Color.WHITE);
            chip.setPreferredSize(new Dimension(Math.max(100, label.length() * 9 + 30), 40));
            chip.addActionListener(e -> runQuickSearch(label, genreSearch));
            row.add(chip);
        }
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 110));
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        return row;
    }

    private void runQuickSearch(String term, boolean genreSearch) {
        searchField.setText(genreSearch ? term + " music" : term);
        search();
    }

    private void showPage(Page page) {
        currentPage = page;
        if (pageTitle == null || pageContent == null) return;
        pageTitle.setText(page.title);
        resultsList.setModel(page == Page.LIBRARY ? recentTracks : tracks);
        pageContent.removeAll();
        if (page == Page.HOME) {
            pageContent.add(createHomeContent());
        } else if (page == Page.EXPLORE) {
            pageContent.add(createExploreContent());
        } else {
            pageContent.add(createLibraryContent());
        }
        pageScroll.getViewport().setViewPosition(new java.awt.Point(0, 0));
        pageContent.revalidate();
        pageContent.repaint();
        updateNavigationStyle(homeNav, page == Page.HOME);
        updateNavigationStyle(exploreNav, page == Page.EXPLORE);
        updateNavigationStyle(libraryNav, page == Page.LIBRARY);
        resultCount.setText(activeQueue().size() + (activeQueue().size() == 1 ? " track" : " tracks"));
        updateNavigationButtons();
    }

    private void updateNavigationStyle(JButton button, boolean selected) {
        button.setFont(new Font("Dialog", selected ? Font.BOLD : Font.PLAIN, 14));
        button.setForeground(selected ? Color.WHITE : new Color(180, 180, 180));
        button.setBackground(selected ? new Color(45, 45, 45) : new Color(10, 10, 10));
    }

    private JPanel createPlayerBar() {
        JPanel bar = new JPanel(new BorderLayout(20, 0));
        bar.setBackground(new Color(24, 24, 24));
        bar.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, new Color(48, 48, 48)));
        bar.setPreferredSize(new Dimension(0, 108));

        JPanel seekPanel = new JPanel(new BorderLayout(10, 0));
        seekPanel.setOpaque(false);
        seekPanel.setBorder(BorderFactory.createEmptyBorder(10, 24, 0, 24));
        elapsedLabel.setForeground(MUTED);
        elapsedLabel.setFont(new Font("Dialog", Font.PLAIN, 10));
        durationLabel.setForeground(MUTED);
        durationLabel.setFont(new Font("Dialog", Font.PLAIN, 10));
        progress.setOpaque(false);
        progress.setFocusable(false);
        progress.setEnabled(false);
        progress.setForeground(ACCENT);
        progress.setPreferredSize(new Dimension(400, 18));
        progress.addChangeListener(e -> {
            if (!updatingProgress && !progress.getValueIsAdjusting()) {
                seekTo(progress.getValue());
            }
        });
        seekPanel.add(elapsedLabel, BorderLayout.WEST);
        seekPanel.add(progress, BorderLayout.CENTER);
        seekPanel.add(durationLabel, BorderLayout.EAST);

        JPanel trackInfo = new JPanel();
        trackInfo.setOpaque(false);
        trackInfo.setLayout(new javax.swing.BoxLayout(trackInfo, javax.swing.BoxLayout.Y_AXIS));
        trackInfo.setBorder(BorderFactory.createEmptyBorder(8, 24, 8, 0));
        JLabel trackLabel = new JLabel("NOW PLAYING");
        trackLabel.setForeground(MUTED);
        trackLabel.setFont(new Font("Dialog", Font.BOLD, 9));
        nowPlaying.setForeground(Color.WHITE);
        nowPlaying.setFont(new Font("Dialog", Font.BOLD, 13));
        status.setForeground(MUTED);
        status.setFont(new Font("Dialog", Font.PLAIN, 11));
        trackInfo.add(trackLabel);
        trackInfo.add(javax.swing.Box.createVerticalStrut(4));
        trackInfo.add(nowPlaying);
        trackInfo.add(status);

        JPanel controls = new JPanel(new FlowLayout(FlowLayout.CENTER, 7, 5));
        controls.setOpaque(false);
        previousButton = new RoundedButton("|\u25c0", new Color(53, 53, 53), Color.WHITE);
        previousButton.setToolTipText("Play previous search result");
        previousButton.setPreferredSize(new Dimension(58, 38));
        previousButton.addActionListener(e -> playPrevious());
        playButton = new RoundedButton("\u25b6  Play", Color.WHITE, new Color(20, 20, 20));
        playButton.setPreferredSize(new Dimension(100, 38));
        playButton.addActionListener(e -> playSelected());
        nextButton = new RoundedButton("\u25b6|", new Color(53, 53, 53), Color.WHITE);
        nextButton.setToolTipText("Play next search result");
        nextButton.setPreferredSize(new Dimension(58, 38));
        nextButton.addActionListener(e -> playNext());
        JButton stopButton = new RoundedButton("\u25a0  Stop", new Color(53, 53, 53), Color.WHITE);
        stopButton.setPreferredSize(new Dimension(86, 38));
        stopButton.addActionListener(e -> stopPlayback());
        JButton openButton = new RoundedButton("Open on YouTube", new Color(53, 53, 53), Color.WHITE);
        openButton.setPreferredSize(new Dimension(140, 38));
        openButton.addActionListener(e -> openSelectedOnYoutube());
        controls.add(previousButton);
        controls.add(playButton);
        controls.add(nextButton);
        controls.add(stopButton);
        controls.add(openButton);

        JPanel bottom = new JPanel(new BorderLayout());
        bottom.setOpaque(false);
        bottom.add(trackInfo, BorderLayout.WEST);
        bottom.add(controls, BorderLayout.CENTER);

        bar.add(seekPanel, BorderLayout.NORTH);
        bar.add(bottom, BorderLayout.CENTER);
        updateNavigationButtons();
        return bar;
    }

    private void search() {
        String query = searchField.getText().trim();
        if (query.isEmpty()) {
            showMessage("Type a song or artist name first.");
            return;
        }
        showPage(Page.HOME);
        searchButton.setEnabled(false);
        status.setText("Searching YouTube...");
        resultCount.setText("Searching...");
        tracks.clear();
        worker.submit(() -> {
            try {
                String ytdlp = findTool("yt-dlp", "yt-dlp.exe");
                ProcessBuilder builder = new ProcessBuilder(ytdlp, "--no-warnings", "--flat-playlist",
                        "--dump-json", "ytsearch10:" + query);
                builder.redirectErrorStream(true);
                Process process = builder.start();
                ArrayList<Track> found = new ArrayList<Track>();
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                        process.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        String id = jsonValue(line, "id");
                        if (id == null || id.isEmpty()) continue;
                        String title = jsonValue(line, "title");
                        String duration = jsonValue(line, "duration_string");
                        if (duration == null) duration = "";
                        int durationSeconds = parseDuration(jsonValue(line, "duration"));
                        found.add(new Track(id, title == null ? "Untitled video" : title, duration,
                                durationSeconds));
                    }
                }
                int exitCode = process.waitFor();
                if (exitCode != 0 && found.isEmpty()) {
                    throw new IllegalStateException("yt-dlp could not search. Check your internet connection and yt-dlp installation.");
                }
                SwingUtilities.invokeLater(() -> showResults(found));
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> {
                    searchButton.setEnabled(true);
                    status.setText("Search failed.");
                    resultCount.setText("Search failed");
                    showMessage(ex.getMessage() == null ? ex.toString() : ex.getMessage());
                });
            }
        });
    }

    private void showResults(ArrayList<Track> found) {
        searchButton.setEnabled(true);
        tracks.clear();
        for (Track track : found) tracks.addElement(track);
        resultCount.setText(found.size() + (found.size() == 1 ? " track" : " tracks"));
        status.setText(found.isEmpty() ? "No results found. Try another search." : "Choose a track and press Play.");
        updateNavigationButtons();
    }

    private void playSelected() {
        Track track = resultsList.getSelectedValue();
        if (track == null) {
            showMessage("Select a search result first.");
            return;
        }
        startPlayback(track, 0);
    }

    private void startPlayback(Track track, int startSeconds) {
        stopPlayback();
        currentTrack = track;
        startSeconds = Math.max(0, Math.min(startSeconds, Math.max(0, track.durationSeconds - 1)));
        final int offsetSeconds = startSeconds;
        resultsList.setSelectedValue(track, true);
        nowPlaying.setText(track.title);
        durationLabel.setText(formatTime(track.durationSeconds));
        setProgressValue(offsetSeconds);
        status.setText("Preparing your track...");
        playButton.setEnabled(false);
        int generation = playbackGeneration;
        worker.submit(() -> {
            try {
                String ytdlp = findTool("yt-dlp", "yt-dlp.exe");
                ProcessBuilder resolver = new ProcessBuilder(ytdlp, "--no-warnings", "--format", "bestaudio",
                        "--get-url", YOUTUBE + track.id);
                resolver.redirectErrorStream(true);
                Process resolveProcess = resolver.start();
                String mediaUrl = null;
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                        resolveProcess.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (line.startsWith("https://") || line.startsWith("http://")) mediaUrl = line;
                    }
                }
                int code = resolveProcess.waitFor();
                if (code != 0 || mediaUrl == null) {
                    throw new IllegalStateException("Could not get an audio stream for this result. Try Open on YouTube.");
                }
                if (generation != playbackGeneration) return;
                String ffplay = findTool("ffplay", "ffplay.exe");
                ArrayList<String> command = new ArrayList<String>();
                command.add(ffplay);
                command.add("-nodisp");
                command.add("-autoexit");
                command.add("-loglevel");
                command.add("error");
                if (offsetSeconds > 0) {
                    command.add("-ss");
                    command.add(String.valueOf(offsetSeconds));
                }
                command.add(mediaUrl);
                Process player = new ProcessBuilder(command).redirectErrorStream(true).start();
                if (generation != playbackGeneration) {
                    player.destroyForcibly();
                    return;
                }
                playbackProcess = player;
                playbackOffsetSeconds = offsetSeconds;
                playbackStartedAtMillis = System.currentTimeMillis();
                SwingUtilities.invokeLater(() -> {
                    if (generation != playbackGeneration) return;
                    playButton.setEnabled(true);
                    boolean saved = rememberRecent(track);
                    status.setText(saved ? "Playing now" : "Playing; recent history could not be saved.");
                    progress.setEnabled(track.durationSeconds > 0);
                    progressTimer.start();
                    updatePlaybackProgress();
                });
                StringBuilder playbackOutput = new StringBuilder();
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                        player.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (playbackOutput.length() < 4000) playbackOutput.append(line).append('\n');
                    }
                }
                int playbackExit = player.waitFor();
                if (playbackProcess == player && generation == playbackGeneration) {
                    playbackProcess = null;
                    SwingUtilities.invokeLater(() -> {
                        if (generation != playbackGeneration) return;
                        progressTimer.stop();
                        playButton.setEnabled(true);
                        progress.setEnabled(false);
                        if (playbackExit == 0) {
                            status.setText("Playback finished.");
                        } else {
                            String details = playbackOutput.toString().trim();
                            status.setText("Playback failed.");
                            showMessage(details.isEmpty()
                                    ? "ffplay exited with code " + playbackExit + ". Check the audio output device and try again."
                                    : "ffplay could not play this track:\n" + details);
                        }
                        if (playbackExit == 0 && track.durationSeconds > 0) {
                            setProgressValue(track.durationSeconds);
                        }
                    });
                }
            } catch (Exception ex) {
                if (generation != playbackGeneration) return;
                SwingUtilities.invokeLater(() -> {
                    if (generation != playbackGeneration) return;
                    playButton.setEnabled(true);
                    status.setText("Could not start playback.");
                    progress.setEnabled(false);
                    showMessage(ex.getMessage() == null ? ex.toString() : ex.getMessage());
                });
            }
        });
    }

    private void playPrevious() {
        DefaultListModel<Track> queue = activeQueue();
        int index = currentQueueIndex();
        if (index > 0) {
            startPlayback(queue.get(index - 1), 0);
        } else if (index == 0) {
            startPlayback(queue.get(0), 0);
        } else if (currentTrack != null) {
            startPlayback(currentTrack, 0);
        }
    }

    private void playNext() {
        DefaultListModel<Track> queue = activeQueue();
        int index = currentQueueIndex();
        if (index + 1 >= 0 && index + 1 < queue.size()) {
            startPlayback(queue.get(index + 1), 0);
        } else {
            status.setText("There is no next track in this list.");
        }
    }

    private int currentQueueIndex() {
        DefaultListModel<Track> queue = activeQueue();
        int selected = resultsList.getSelectedIndex();
        if (selected >= 0) return selected;
        if (currentTrack != null) {
            for (int i = 0; i < queue.size(); i++) {
                if (queue.get(i).id.equals(currentTrack.id)) return i;
            }
        }
        return -1;
    }

    private DefaultListModel<Track> activeQueue() {
        return currentPage == Page.LIBRARY ? recentTracks : tracks;
    }

    private void updateNavigationButtons() {
        if (previousButton == null || nextButton == null) return;
        boolean hasTracks = activeQueue().size() > 0;
        previousButton.setEnabled(hasTracks || currentTrack != null);
        nextButton.setEnabled(hasTracks);
    }

    private void seekTo(int seconds) {
        if (currentTrack == null || playbackProcess == null || currentTrack.durationSeconds <= 0) return;
        startPlayback(currentTrack, seconds);
    }

    private void updatePlaybackProgress() {
        if (playbackProcess == null || currentTrack == null) return;
        int seconds = playbackOffsetSeconds
                + (int) ((System.currentTimeMillis() - playbackStartedAtMillis) / 1000L);
        if (currentTrack.durationSeconds > 0) {
            seconds = Math.min(seconds, currentTrack.durationSeconds);
        }
        elapsedLabel.setText(formatTime(seconds));
        if (currentTrack.durationSeconds > 0) setProgressValue(seconds);
    }

    private void setProgressValue(int seconds) {
        int max = currentTrack != null && currentTrack.durationSeconds > 0
                ? currentTrack.durationSeconds : 1000;
        progress.setMaximum(Math.max(max, 1));
        updatingProgress = true;
        progress.setValue(Math.max(0, Math.min(seconds, max)));
        updatingProgress = false;
        elapsedLabel.setText(formatTime(seconds));
    }

    private void openSelectedOnYoutube() {
        Track track = resultsList.getSelectedValue();
        if (track == null) {
            showMessage("Select a search result first.");
            return;
        }
        try {
            java.awt.Desktop.getDesktop().browse(new URI(YOUTUBE + track.id));
            status.setText("Opened the selected video in your browser.");
        } catch (Exception ex) {
            showMessage("Could not open the browser: " + ex.getMessage());
        }
    }

    private void stopPlayback() {
        playbackGeneration++;
        progressTimer.stop();
        Process old = playbackProcess;
        playbackProcess = null;
        if (old != null) old.destroyForcibly();
        playbackOffsetSeconds = 0;
        if (playButton != null) playButton.setEnabled(true);
        if (progress != null) {
            progress.setEnabled(false);
            setProgressValue(0);
        }
        if (status != null) status.setText("Playback stopped.");
        updateNavigationButtons();
    }

    private void loadRecentTracks() {
        int count = Math.min(libraryPreferences.getInt("recent.count", 0), RECENT_LIMIT);
        for (int i = 0; i < count; i++) {
            String prefix = "recent." + i + ".";
            String id = libraryPreferences.get(prefix + "id", null);
            String title = libraryPreferences.get(prefix + "title", null);
            if (id == null || title == null) continue;
            String duration = libraryPreferences.get(prefix + "duration", "");
            int durationSeconds = libraryPreferences.getInt(prefix + "seconds", -1);
            recentTracks.addElement(new Track(id, title, duration, durationSeconds));
        }
    }

    private boolean rememberRecent(Track track) {
        for (int i = recentTracks.size() - 1; i >= 0; i--) {
            if (recentTracks.get(i).id.equals(track.id)) recentTracks.remove(i);
        }
        recentTracks.insertElementAt(track, 0);
        while (recentTracks.size() > RECENT_LIMIT) recentTracks.remove(RECENT_LIMIT);

        try {
            libraryPreferences.putInt("recent.count", recentTracks.size());
            for (int i = 0; i < recentTracks.size(); i++) {
                Track recent = recentTracks.get(i);
                String prefix = "recent." + i + ".";
                libraryPreferences.put(prefix + "id", recent.id);
                libraryPreferences.put(prefix + "title", recent.title);
                libraryPreferences.put(prefix + "duration", recent.duration);
                libraryPreferences.putInt(prefix + "seconds", recent.durationSeconds);
            }
            libraryPreferences.flush();
        } catch (BackingStoreException ex) {
            showMessage("Could not save recent tracks to this device: " + ex.getMessage());
            return false;
        }

        if (currentPage == Page.LIBRARY) {
            showPage(Page.LIBRARY);
            resultsList.setSelectedValue(track, true);
            resultCount.setText(recentTracks.size() + (recentTracks.size() == 1 ? " track" : " tracks"));
        }
        return true;
    }

    private String findTool(String command, String windowsName) {
        File local = new File("tools", windowsName);
        if (local.isFile()) return local.getAbsolutePath();
        return command;
    }

    private String jsonValue(String json, String key) {
        String marker = "\"" + key + "\"";
        int at = json.indexOf(marker);
        if (at < 0) return null;
        at = json.indexOf(':', at + marker.length());
        if (at < 0) return null;
        at++;
        while (at < json.length() && Character.isWhitespace(json.charAt(at))) at++;
        if (at >= json.length() || json.charAt(at) == 'n') return null;
        if (json.charAt(at) != '"') {
            int end = at;
            while (end < json.length() && json.charAt(end) != ',' && json.charAt(end) != '}') end++;
            return json.substring(at, end).trim();
        }
        StringBuilder value = new StringBuilder();
        for (int i = at + 1; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '"') return value.toString();
            if (c == '\\' && i + 1 < json.length()) {
                char escaped = json.charAt(++i);
                if (escaped == 'n') value.append('\n');
                else if (escaped == 'r') value.append('\r');
                else if (escaped == 't') value.append('\t');
                else value.append(escaped);
            } else value.append(c);
        }
        return value.toString();
    }

    private static int parseDuration(String value) {
        if (value == null) return -1;
        try {
            return Math.max(0, (int) Double.parseDouble(value));
        } catch (NumberFormatException ex) {
            return -1;
        }
    }

    private static String formatTime(int seconds) {
        if (seconds < 0) return "--:--";
        return (seconds / 60) + ":" + String.format("%02d", seconds % 60);
    }

    private void showMessage(String message) {
        JOptionPane.showMessageDialog(this, message, "Music", JOptionPane.INFORMATION_MESSAGE);
    }

    private enum Page {
        HOME("Home"),
        EXPLORE("Explore"),
        LIBRARY("Library");

        final String title;

        Page(String title) {
            this.title = title;
        }
    }

    private static final class Track {
        final String id;
        final String title;
        final String duration;
        final int durationSeconds;

        Track(String id, String title, String duration, int durationSeconds) {
            this.id = id;
            this.title = title;
            this.duration = duration;
            this.durationSeconds = durationSeconds;
        }
    }

    private static final class TrackRenderer extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                boolean isSelected, boolean cellHasFocus) {
            Track track = (Track) value;
            JPanel row = new JPanel(new BorderLayout(14, 0));
            row.setOpaque(true);
            row.setBackground(isSelected ? new Color(54, 54, 54) : BACKGROUND);
            row.setBorder(BorderFactory.createEmptyBorder(7, 12, 7, 14));

            JLabel artwork = new JLabel("\u266b", SwingConstants.CENTER);
            artwork.setOpaque(true);
            artwork.setBackground(index % 2 == 0 ? new Color(111, 55, 74) : new Color(57, 73, 111));
            artwork.setForeground(new Color(255, 224, 230));
            artwork.setFont(new Font("Dialog", Font.BOLD, 23));
            artwork.setPreferredSize(new Dimension(48, 48));

            JPanel labels = new JPanel();
            labels.setOpaque(false);
            labels.setLayout(new javax.swing.BoxLayout(labels, javax.swing.BoxLayout.Y_AXIS));
            JLabel title = new JLabel(track.title);
            title.setForeground(Color.WHITE);
            title.setFont(new Font("Dialog", Font.BOLD, 13));
            JLabel detail = new JLabel("YouTube" + (track.duration.isEmpty() ? "" : "  \u2022  " + track.duration));
            detail.setForeground(MUTED);
            detail.setFont(new Font("Dialog", Font.PLAIN, 11));
            labels.add(title);
            labels.add(javax.swing.Box.createVerticalStrut(5));
            labels.add(detail);

            JLabel play = new JLabel("\u25b6", SwingConstants.CENTER);
            play.setForeground(isSelected ? Color.WHITE : MUTED);
            play.setFont(new Font("Dialog", Font.BOLD, 15));
            play.setPreferredSize(new Dimension(30, 30));
            row.add(artwork, BorderLayout.WEST);
            row.add(labels, BorderLayout.CENTER);
            row.add(play, BorderLayout.EAST);
            return row;
        }
    }

    private static final class RoundedButton extends JButton {
        private final Color fill;
        private final Color text;

        RoundedButton(String label, Color fill, Color text) {
            super(label);
            this.fill = fill;
            this.text = text;
            setForeground(text);
            setFont(new Font("Dialog", Font.BOLD, 12));
            setFocusPainted(false);
            setBorderPainted(false);
            setContentAreaFilled(false);
            setOpaque(false);
            setCursor(new java.awt.Cursor(java.awt.Cursor.HAND_CURSOR));
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            Color color = isEnabled() ? fill : new Color(90, 90, 90);
            if (getModel().isPressed()) color = color.darker();
            g.setColor(color);
            g.fillRoundRect(0, 0, getWidth(), getHeight(), 24, 24);
            g.dispose();
            setForeground(isEnabled() ? text : new Color(185, 185, 185));
            super.paintComponent(graphics);
        }

        @Override
        protected void paintBorder(Graphics graphics) {
        }
    }

    private static final class HeroPanel extends JPanel {
        HeroPanel() {
            setOpaque(false);
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);
            Graphics2D g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setPaint(new GradientPaint(0, 0, new Color(126, 28, 48),
                    getWidth(), getHeight(), new Color(48, 28, 65)));
            g.fillRoundRect(0, 0, getWidth(), getHeight(), 24, 24);
            g.setColor(new Color(255, 255, 255, 18));
            g.fillOval(getWidth() - 230, -115, 310, 310);
            g.fillOval(getWidth() - 130, 45, 150, 150);
            g.dispose();
        }
    }

    private static final class HintTextField extends JTextField {
        private final String hint;

        HintTextField(String hint) {
            this.hint = hint;
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);
            if (getText().isEmpty() && !isFocusOwner()) {
                Graphics2D g = (Graphics2D) graphics.create();
                g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                g.setColor(new Color(155, 155, 155));
                g.setFont(getFont());
                g.drawString(hint, getInsets().left, getHeight() / 2 + g.getFontMetrics().getAscent() / 2 - 2);
                g.dispose();
            }
        }
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new YouTubeMusicPlayer());
    }
}
