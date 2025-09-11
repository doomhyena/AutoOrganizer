package com.example.autoorganizer;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.beans.property.*;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.input.DragEvent;
import javafx.scene.input.Dragboard;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.*;
import javafx.stage.DirectoryChooser;
import javafx.stage.Stage;
import org.controlsfx.control.Notifications;
import org.controlsfx.control.StatusBar;
import org.controlsfx.control.TaskProgressView;
import org.kordamp.bootstrapfx.BootstrapFX;
import org.kordamp.ikonli.javafx.FontIcon;
import org.kordamp.ikonli.fontawesome5.FontAwesomeSolid;
import net.synedra.validatorfx.Validator;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

public class DoomSorterApp extends Application {

    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private final Path configDir = Path.of(System.getProperty("user.home"), ".doomsorter");
    private final Path configFile = configDir.resolve("config.json");
    private AppSettings settings;
    private final ObservableList<Task<?>> runningTasks = FXCollections.observableArrayList();
    
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2, r -> {
        Thread t = new Thread(r);
        t.setDaemon(true);
        t.setName("DoomSorter-Scheduler");
        return t;
    });

    private StatusBar statusBar;
    private TaskProgressView<Task<?>> taskProgressView;

    @Override
    public void start(Stage stage) {
        loadOrInitSettings();

        TabPane tabs = new TabPane(
                tabOrganizer(),
                tabRenamer(),
                tabDuplicates(),
                tabBackup(),
                tabSettings()
        );
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);

        statusBar = new StatusBar();
        statusBar.setText("Készen áll.");
        taskProgressView = new TaskProgressView<>();
        taskProgressView.setPrefHeight(80);

        VBox root = new VBox(tabs, taskProgressView, statusBar);
        VBox.setVgrow(tabs, Priority.ALWAYS);
        root.getStylesheets().add(BootstrapFX.bootstrapFXStylesheet());
        root.setPadding(new Insets(12));
        root.setSpacing(8);

        Scene scene = new Scene(root, 1100, 720);
        stage.setTitle("DoomSorter — fájlrendező / renamer / backup / duplikátum");
        stage.setScene(scene);
        stage.show();

        scheduleBackupIfEnabled();
    }

    private Tab tabOrganizer() {
        var dirField = new TextField();
        dirField.setPromptText("Válassz mappát vagy dobd ide…");
        Button pickBtn = btn("Mappa kiválasztása", FontAwesomeSolid.FOLDER_OPEN, () -> {
            DirectoryChooser dc = new DirectoryChooser();
            File f = dc.showDialog(null);
            if (f != null) dirField.setText(f.getAbsolutePath());
        });
        HBox pickRow = row(dirField, pickBtn);

        addDirDragDrop(dirField);

        Button runBtn = btn("Rendezés indítása", FontAwesomeSolid.MAGIC, () -> {
            Path base = Path.of(dirField.getText().trim());
            if (!Files.isDirectory(base)) {
                toast("Hibás mappa", "Adj meg létező mappát");
                return;
            }
            Task<Void> task = new Task<>() {
                @Override
                protected Void call() throws Exception {
                    updateMessage("Fájlok keresése…");
                    Map<String, String> categories = settings.categoryMap; // ext -> targetFolder
                    Map<String, List<Path>> classified = new HashMap<>();
                    try (Stream<Path> s = Files.walk(base)) {
                        List<Path> files = s.filter(Files::isRegularFile).collect(Collectors.toList());
                        int total = files.size();
                        int i = 0;
                        for (Path p : files) {
                            i++;
                            updateProgress(i, total);
                            updateMessage("Vizsgálat: " + p.getFileName());
                            String ext = ext(p);
                            String target = categories.getOrDefault(ext.toLowerCase(), categories.getOrDefault("*", null));
                            if (target != null) {
                                classified.computeIfAbsent(target, k -> new ArrayList<>()).add(p);
                            }
                        }
                    }
                    for (var e : classified.entrySet()) {
                        Path targetDir = base.resolve(e.getKey());
                        Files.createDirectories(targetDir);
                        int total = e.getValue().size();
                        int i = 0;
                        for (Path p : e.getValue()) {
                            i++;
                            updateProgress(i, total);
                            updateMessage("Mozgatás: " + p.getFileName() + " -> " + targetDir.getFileName());
                            moveFileSafe(p, targetDir.resolve(p.getFileName()));
                        }
                    }
                    return null;
                }
            };
            runTask(task, () -> toast("Kész!", "Rendezés befejezve"));
        });

        VBox box = section("Fájl-rendező", pickRow, runBtn);
        return new Tab("Rendező", box);
    }

    private Tab tabRenamer() {
        var dirField = new TextField();
        dirField.setPromptText("Képek mappája");
        Button pickBtn = btn("Mappa kiválasztása", FontAwesomeSolid.FOLDER_OPEN, () -> {
            DirectoryChooser dc = new DirectoryChooser();
            File f = dc.showDialog(null);
            if (f != null) dirField.setText(f.getAbsolutePath());
        });
        addDirDragDrop(dirField);

        ToggleGroup mode = new ToggleGroup();
        RadioButton rbDate = new RadioButton("Dátum/idő alapján");
        rbDate.setToggleGroup(mode);
        rbDate.setSelected(true);
        RadioButton rbPrefix = new RadioButton("Prefix + sorszám");
        rbPrefix.setToggleGroup(mode);

        var datePattern = new TextField("yyyyMMdd_HHmmss");
        var prefixField = new TextField("Nyaralas");
        Spinner<Integer> startIndex = new Spinner<>(1, Integer.MAX_VALUE, 1);

        Button runBtn = btn("Átnevezés", FontAwesomeSolid.SYNC, () -> {
            Path dir = Path.of(dirField.getText().trim());
            if (!Files.isDirectory(dir)) {
                toast("Hibás mappa", "Adj meg létező mappát");
                return;
            }
            Task<Void> task = new Task<>() {
                @Override
                protected Void call() throws Exception {
                    List<Path> files;
                    try (Stream<Path> s = Files.list(dir)) {
                        files = s.filter(p -> isImage(ext(p))).sorted().collect(Collectors.toList());
                    }
                    int total = files.size();
                    int i = 0;
                    for (Path p : files) {
                        i++;
                        updateProgress(i, total);
                        String newName;
                        if (rbDate.isSelected()) {
                            String pat = datePattern.getText().trim();
                            FileTime ft = Files.getLastModifiedTime(p);
                            String stamp = new SimpleDateFormat(pat).format(new Date(ft.toMillis()));
                            newName = stamp + suffix(p);
                        } else {
                            int idx = startIndex.getValue().intValue() + i - 1;
                            newName = prefixField.getText().trim() + "_" + idx + suffix(p);
                        }
                        Path target = p.resolveSibling(newName);
                        updateMessage(p.getFileName() + " -> " + newName);
                        moveFileSafe(p, target);
                    }
                    return null;
                }
            };
            runTask(task, () -> toast("Kész!", "Átnevezés befejezve"));
        });

        Validator validator = new Validator();
        validator.createCheck().withMethod(c -> {
            if (dirField.getText().trim().isEmpty()) c.error("Mappa kötelező");
        }).decorates(dirField).immediate();

        GridPane gp = new GridPane();
        gp.setHgap(8);
        gp.setVgap(8);
        gp.add(new Label("Mappa:"), 0, 0);
        gp.add(row(dirField, pickBtn), 1, 0);
        gp.add(new Label("Mód:"), 0, 1);
        gp.add(row(rbDate, rbPrefix), 1, 1);
        gp.add(new Label("Dátumminta:"), 0, 2);
        gp.add(datePattern, 1, 2);
        gp.add(new Label("Prefix / induló sorszám:"), 0, 3);
        gp.add(row(prefixField, startIndex), 1, 3);

        VBox box = section("Kép-átnevező", gp, runBtn);
        return new Tab("Renamer", box);
    }

    private Tab tabDuplicates() {
        TableView<DupeRow> table = new TableView<>();
        TableColumn<DupeRow, String> hashCol = new TableColumn<>("Hash");
        hashCol.setCellValueFactory(new PropertyValueFactory<>("hash"));
        hashCol.setPrefWidth(380);
        TableColumn<DupeRow, String> pathCol = new TableColumn<>("Fájl");
        pathCol.setCellValueFactory(new PropertyValueFactory<>("path"));
        pathCol.setPrefWidth(600);
        TableColumn<DupeRow, Long> sizeCol = new TableColumn<>("Méret");
        sizeCol.setCellValueFactory(new PropertyValueFactory<>("size"));
        sizeCol.setPrefWidth(100);
        table.getColumns().addAll(hashCol, pathCol, sizeCol);
        ObservableList<DupeRow> model = FXCollections.observableArrayList();
        table.setItems(model);

        TextField rootField = new TextField();
        rootField.setPromptText("Gyökérmappa (teljes lemezhez válassz C:/ vagy /)");
        addDirDragDrop(rootField);
        Button pick = btn("Mappa kiválasztása", FontAwesomeSolid.FOLDER_OPEN, () -> {
            DirectoryChooser dc = new DirectoryChooser();
            File f = dc.showDialog(null);
            if (f != null) rootField.setText(f.getAbsolutePath());
        });
        Button scan = btn("Duplikátum keresés", FontAwesomeSolid.SEARCH, () -> {
            Path root = Path.of(rootField.getText().trim());
            if (!Files.isDirectory(root)) {
                toast("Hibás mappa", "Adj meg létező mappát");
                return;
            }
            model.clear();
            Task<Void> task = new Task<>() {
                @Override
                protected Void call() throws Exception {
                    updateMessage("Fájlok bejárása…");
                    Map<String, List<Path>> byHash = new HashMap<>();
                    List<Path> all;
                    try (Stream<Path> s = Files.walk(root)) {
                        all = s.filter(Files::isRegularFile).collect(Collectors.toList());
                    }
                    int total = all.size();
                    int i = 0;
                    for (Path p : all) {
                        i++;
                        updateProgress(i, total);
                        updateMessage("Hash: " + p.getFileName());
                        String h = sha256(p);
                        byHash.computeIfAbsent(h, k -> new ArrayList<>()).add(p);
                    }
                    List<DupeRow> rows = new ArrayList<>();
                    for (var e : byHash.entrySet()) {
                        if (e.getValue().size() > 1) {
                            for (Path p : e.getValue()) {
                                rows.add(new DupeRow(e.getKey(), p.toString(), fileSize(p)));
                            }
                        }
                    }
                    Platform.runLater(() -> model.setAll(rows));
                    return null;
                }
            };
            runTask(task, () -> toast("Kész!", "Duplikátum lista frissítve"));
        });
        Button deleteSel = btn("Kijelöltek törlése", FontAwesomeSolid.TRASH, () -> {
            List<DupeRow> sel = new ArrayList<>(table.getSelectionModel().getSelectedItems());
            if (sel.isEmpty()) {
                toast("Semmi nincs kijelölve", "Jelölj ki sorokat");
                return;
            }
            ConfirmDialog.show("Biztosan törlöd a kijelölteket?", () -> {
                Task<Void> task = new Task<>() {
                    @Override
                    protected Void call() throws Exception {
                        int total = sel.size();
                        int i = 0;
                        for (DupeRow r : sel) {
                            i++;
                            updateProgress(i, total);
                            Files.deleteIfExists(Path.of(r.getPath()));
                        }
                        return null;
                    }
                };
                runTask(task, () -> {
                    model.removeAll(sel);
                    toast("Törölve", "A kijelölt fájlok kukázva");
                });
            });
        });
        table.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);

        VBox box = section("Duplikált fájlok", row(rootField, pick), row(scan, deleteSel), table);
        return new Tab("Duplikátumok", box);
    }

    private Tab tabBackup() {
        CheckBox enable = new CheckBox("Backup engedélyezése");
        enable.setSelected(settings.backupEnabled);
        TextField src = new TextField(Optional.ofNullable(settings.backupSource).orElse(""));
        src.setPromptText("Forrás mappa");
        addDirDragDrop(src);
        TextField dst = new TextField(Optional.ofNullable(settings.backupTarget).orElse(""));
        dst.setPromptText("Cél mappa (pendrive/HDD)");
        addDirDragDrop(dst);
        Button pickSrc = btn("Forrás…", FontAwesomeSolid.FOLDER_OPEN, () -> chooseDirInto(src));
        Button pickDst = btn("Cél…", FontAwesomeSolid.FOLDER_OPEN, () -> chooseDirInto(dst));

        Spinner<Integer> hour = new Spinner<>(0, 23, settings.backupHour);
        Spinner<Integer> minute = new Spinner<>(0, 59, settings.backupMinute);
        Button runNow = btn("Backup most", FontAwesomeSolid.CLOUD_UPLOAD_ALT, () -> startBackupNow(src.getText(), dst.getText()));

        enable.selectedProperty().addListener((obs, o, n) -> {
            settings.backupEnabled = n;
            saveSettings();
            scheduleBackupIfEnabled();
        });
        Runnable saveTime = () -> {
            settings.backupHour = hour.getValue();
            settings.backupMinute = minute.getValue();
            saveSettings();
            scheduleBackupIfEnabled();
        };
        hour.valueProperty().addListener((o, a, b) -> saveTime.run());
        minute.valueProperty().addListener((o, a, b) -> saveTime.run());
        src.textProperty().addListener((o, a, b) -> {
            settings.backupSource = b;
            saveSettings();
        });
        dst.textProperty().addListener((o, a, b) -> {
            settings.backupTarget = b;
            saveSettings();
        });

        GridPane gp = new GridPane();
        gp.setHgap(8);
        gp.setVgap(8);
        gp.add(enable, 0, 0, 2, 1);
        gp.add(new Label("Forrás:"), 0, 1);
        gp.add(row(src, pickSrc), 1, 1);
        gp.add(new Label("Cél:"), 0, 2);
        gp.add(row(dst, pickDst), 1, 2);
        gp.add(new Label("Időzítés (HH:MM):"), 0, 3);
        gp.add(row(hour, new Label(":"), minute), 1, 3);
        gp.add(runNow, 1, 4);

        VBox box = section("Backup (időzítve)", gp);
        return new Tab("Backup", box);
    }

    private Tab tabSettings() {

        TableView<MapRow> table = new TableView<>();
        TableColumn<MapRow, String> extCol = new TableColumn<>("Kiterjesztés");
        extCol.setCellValueFactory(new PropertyValueFactory<>("key"));
        TableColumn<MapRow, String> dirCol = new TableColumn<>("Cél mappa");
        dirCol.setCellValueFactory(new PropertyValueFactory<>("value"));
        table.getColumns().addAll(extCol, dirCol);
        ObservableList<MapRow> rows = FXCollections.observableArrayList();
        settings.categoryMap.forEach((k, v) -> rows.add(new MapRow(k, v)));
        table.setItems(rows);
        table.setEditable(true);

        TextField keyField = new TextField();
        keyField.setPromptText("pl. jpg");
        TextField valField = new TextField();
        valField.setPromptText("pl. Pictures");
        Button add = btn("Hozzáadás", FontAwesomeSolid.PLUS, () -> {
            String k = keyField.getText().trim().toLowerCase();
            String v = valField.getText().trim();
            if (k.isEmpty() || v.isEmpty()) {
                toast("Hopp", "Mindkét mező kell");
                return;
            }
            settings.categoryMap.put(k, v);
            rows.setAll(mapRows(settings.categoryMap));
            saveSettings();
        });
        Button remove = btn("Kijelölt törlése", FontAwesomeSolid.TRASH, () -> {
            MapRow sel = table.getSelectionModel().getSelectedItem();
            if (sel == null) return;
            settings.categoryMap.remove(sel.getKey());
            rows.setAll(mapRows(settings.categoryMap));
            saveSettings();
        });

        Label info = new Label("Wildcard: a '*' kulcs a minden egyéb fájlt ide rakja.");

        VBox box = section("Beállítások (JSON mentés)", row(keyField, valField, add, remove), table, info);
        return new Tab("Beállítások", box);
    }

    private void runTask(Task<?> task, Runnable onSucceeded) {
        runningTasks.add(task);
        task.messageProperty().addListener((o, a, b) -> statusBar.setText(b));
        task.setOnSucceeded(e -> {
            runningTasks.remove(task);
            statusBar.setText("Kész.");
            onSucceeded.run();
        });
        task.setOnFailed(e -> {
            runningTasks.remove(task);
            statusBar.setText("Hiba: " + task.getException());
            toast("Hiba", task.getException().getMessage());
        });
        new Thread(task, "DoomSorter-Task").start();
    }

    private void addDirDragDrop(TextField field) {
        field.setOnDragOver(ev -> {
            Dragboard db = ev.getDragboard();
            if (db.hasFiles() && db.getFiles().get(0).isDirectory()) ev.acceptTransferModes(TransferMode.COPY);
            ev.consume();
        });
        field.setOnDragDropped((DragEvent ev) -> {
            Dragboard db = ev.getDragboard();
            boolean success = false;
            if (db.hasFiles()) {
                field.setText(db.getFiles().get(0).getAbsolutePath());
                success = true;
            }
            ev.setDropCompleted(success);
            ev.consume();
        });
    }

    private Button btn(String text, FontAwesomeSolid icon, Runnable action) {
        Button b = new Button(text, new FontIcon("fas-folder-open"));
        b.getStyleClass().setAll("btn", "btn-primary");
        b.setOnAction(e -> action.run());
        return b;
    }

    private VBox section(String title, javafx.scene.Node... nodes) {
        Label h = new Label(title);
        h.getStyleClass().setAll("h3");
        VBox box = new VBox(8, h);
        box.getChildren().addAll(nodes);
        box.setPadding(new Insets(10));
        box.setFillWidth(true);
        return box;
    }

    private HBox row(javafx.scene.Node... nodes) {
        HBox hb = new HBox(8, nodes);
        hb.setAlignment(Pos.CENTER_LEFT);
        return hb;
    }

    private void toast(String title, String text) {
        Notifications.create().title(title).text(text).showInformation();
    }

    private static String ext(Path p) {
        String n = p.getFileName().toString();
        int i = n.lastIndexOf('.');
        return i >= 0 ? n.substring(i + 1) : "";
    }

    private static String suffix(Path p) {
        String n = p.getFileName().toString();
        int i = n.lastIndexOf('.');
        return i >= 0 ? n.substring(i) : "";
    }

    private static boolean isImage(String ext) {
        return Set.of("jpg", "jpeg", "png", "gif", "bmp", "heic", "webp", "tif", "tiff").contains(ext.toLowerCase());
    }

    private static long fileSize(Path p) {
        try {
            return Files.size(p);
        } catch (IOException e) {
            return -1;
        }
    }

    private static void moveFileSafe(Path src, Path dst) throws IOException {
        if (Files.exists(dst)) {
            String base = dst.getFileName().toString();
            String name;
            String ext = "";
            int dot = base.lastIndexOf('.');
            if (dot >= 0) {
                name = base.substring(0, dot);
                ext = base.substring(dot);
            } else name = base;
            int i = 1;
            Path alt;
            do {
                alt = dst.getParent().resolve(name + "_" + i + ext);
                i++;
            } while (Files.exists(alt));
            dst = alt;
        }
        Files.createDirectories(dst.getParent());
        Files.move(src, dst, StandardCopyOption.ATOMIC_MOVE);
    }

    private static String sha256(Path p) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            try (InputStream in = Files.newInputStream(p); DigestInputStream dis = new DigestInputStream(in, md)) {
                byte[] buf = new byte[8192];
                while (dis.read(buf) != -1) { /* stream */ }
            }
            byte[] digest = md.digest();
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return "ERROR:" + e.getMessage();
        }
    }

    private void startBackupNow(String source, String target) {
        if (source == null || source.isBlank() || target == null || target.isBlank()) {
            toast("Hiányzó beállítás", "Forrás és cél mappa kell");
            return;
        }
        Path src = Path.of(source);
        Path dst = Path.of(target);
        if (!Files.isDirectory(src)) {
            toast("Hibás forrás", "Nem mappa: " + source);
            return;
        }
        Task<Void> task = new Task<>() {
            @Override
            protected Void call() throws Exception {
                List<Path> files;
                try (Stream<Path> s = Files.walk(src)) {
                    files = s.filter(Files::isRegularFile).collect(Collectors.toList());
                }
                int total = files.size();
                int i = 0;
                for (Path f : files) {
                    i++;
                    updateProgress(i, total);
                    updateMessage("Másolás: " + src.relativize(f));
                    Path rel = src.relativize(f);
                    Path out = dst.resolve(rel);
                    Files.createDirectories(out.getParent());
                    Files.copy(f, out, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
                }
                return null;
            }
        };
        runTask(task, () -> toast("Backup kész", "Minden lemásolva"));
    }

    private ScheduledFuture<?> scheduledBackup;

    private void scheduleBackupIfEnabled() {
        if (scheduledBackup != null && !scheduledBackup.isCancelled()) scheduledBackup.cancel(false);
        if (!settings.backupEnabled) return;
        LocalTime t = LocalTime.of(settings.backupHour, settings.backupMinute);
        long initialDelay = millisUntil(t);
        long period = TimeUnit.DAYS.toMillis(1);
        scheduledBackup = scheduler.scheduleAtFixedRate(() -> Platform.runLater(() -> startBackupNow(settings.backupSource, settings.backupTarget)),
                initialDelay, period, TimeUnit.MILLISECONDS);
        statusBar.setText("Backup ütemezve: " + t);
    }

    private static long millisUntil(LocalTime time) {
        ZonedDateTime now = ZonedDateTime.now();
        ZonedDateTime next = now.withHour(time.getHour()).withMinute(time.getMinute()).withSecond(0).withNano(0);
        if (!next.isAfter(now)) next = next.plusDays(1);
        return Duration.between(now, next).toMillis();
    }

    private void chooseDirInto(TextField field) {
        DirectoryChooser dc = new DirectoryChooser();
        File f = dc.showDialog(null);
        if (f != null) field.setText(f.getAbsolutePath());
    }

    private void loadOrInitSettings() {
        try {
            if (Files.exists(configFile)) {
                try (Reader r = Files.newBufferedReader(configFile, StandardCharsets.UTF_8)) {
                    settings = gson.fromJson(r, AppSettings.class);
                }
            }
        } catch (Exception ignored) {
        }
        if (settings == null) {
            settings = AppSettings.defaultSettings();
            saveSettings();
        }
    }

    private void saveSettings() {
        try {
            Files.createDirectories(configDir);
        } catch (IOException ignored) {
        }
        try (Writer w = Files.newBufferedWriter(configFile, StandardCharsets.UTF_8)) {
            gson.toJson(settings, w);
        } catch (IOException e) {
            toast("JSON hiba", e.getMessage());
        }
    }

    private static List<MapRow> mapRows(Map<String, String> map) {
        return map.entrySet().stream().sorted(Map.Entry.comparingByKey()).map(e -> new MapRow(e.getKey(), e.getValue())).collect(Collectors.toList());
    }

    public static class AppSettings {
        Map<String, String> categoryMap = new LinkedHashMap<>();
        boolean backupEnabled = false;
        String backupSource = "";
        String backupTarget = "";
        int backupHour = 20;
        int backupMinute = 0;

        static AppSettings defaultSettings() {
            AppSettings s = new AppSettings();
            s.categoryMap.put("jpg", "Pictures");
            s.categoryMap.put("jpeg", "Pictures");
            s.categoryMap.put("png", "Pictures");
            s.categoryMap.put("gif", "Pictures");
            s.categoryMap.put("mp4", "Videos");
            s.categoryMap.put("mov", "Videos");
            s.categoryMap.put("mkv", "Videos");
            s.categoryMap.put("pdf", "Docs");
            s.categoryMap.put("docx", "Docs");
            s.categoryMap.put("xlsx", "Docs");
            s.categoryMap.put("zip", "Archives");
            s.categoryMap.put("rar", "Archives");
            s.categoryMap.put("*", "Misc");
            return s;
        }
    }

    public static class MapRow {
        private final SimpleStringProperty key = new SimpleStringProperty();
        private final SimpleStringProperty value = new SimpleStringProperty();

        public MapRow(String k, String v) {
            key.set(k);
            value.set(v);
        }

        public String getKey() {
            return key.get();
        }

        public String getValue() {
            return value.get();
        }
    }

    public static class DupeRow {
        private final SimpleStringProperty hash = new SimpleStringProperty();
        private final SimpleStringProperty path = new SimpleStringProperty();
        private final SimpleLongProperty size = new SimpleLongProperty();

        public DupeRow(String hash, String path, long size) {
            this.hash.set(hash);
            this.path.set(path);
            this.size.set(size);
        }

        public String getHash() {
            return hash.get();
        }

        public String getPath() {
            return path.get();
        }

        public long getSize() {
            return size.get();
        }
    }
    static class ConfirmDialog {
        static void show(String text, Runnable onYes) {
            Alert a = new Alert(Alert.AlertType.CONFIRMATION, text, ButtonType.YES, ButtonType.NO);
            a.setHeaderText("Megerősítés");
            a.showAndWait().ifPresent(bt -> {
                if (bt == ButtonType.YES) onYes.run();
            });
        }
    }
}
