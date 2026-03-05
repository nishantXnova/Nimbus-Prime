import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.regex.*;

/**
 * Nimbus Prime — LaunchEngine
 *
 * Hardware-adaptive Minecraft 1.21+ launch engine.
 * Zero external dependencies — compiles with plain javac (Java 21+).
 *
 * Subsystems:
 *   1. Hardware Auditor    — detects CPU cores, selects optimal GC strategy.
 *   2. Ghost Classpath     — regex-based version.json parser + Windows OS-rule filter.
 *   3. Offline Identity    — deterministic UUID (matches Minecraft's offline algorithm).
 *   4. Self-Destruct       — ProcessBuilder launch → confirmed alive → System.exit(0).
 */
public class LaunchEngine {

    // Optimized Regex for performance (pre-compiled)
    private static final Pattern PATH_PATTERN = Pattern.compile("\"path\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern OS_NAME_PATTERN = Pattern.compile("\"name\"\\s*:\\s*\"os\"");
    private static final Pattern OS_VALUE_PATTERN = Pattern.compile("\"value\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern ACTION_PATTERN = Pattern.compile("\"action\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern URL_PATTERN = Pattern.compile("\"url\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern ID_PATTERN = Pattern.compile("\"id\"\\s*:\\s*\"([^\"]+)\"");

    private static final String MANIFEST_URL = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json";
    private static final HttpClient client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(java.time.Duration.ofSeconds(10))
            .build();

    // ─────────────────────────────────────────────────────────────
    // 1. HARDWARE AUDITOR
    // ─────────────────────────────────────────────────────────────

    public enum GcMode { SERIAL, G1 }

    /**
     * Snapshot of the detected hardware profile and chosen GC strategy.
     *
     * @param cores      logical CPU cores visible to the JVM
     * @param gc         the selected garbage-collector mode
     * @param maxGameMb  maximum heap to allocate to the game process (MB)
     * @param label      human-readable status string for display in the UI
     */
    public record HardwareProfile(int cores, GcMode gc, int maxGameMb, String label) {}

    /**
     * Audits CPU cores, detects physical system RAM (via reflection — no
     * compile-time sun.* dependency), picks the optimal GC strategy, and
     * computes an appropriate game heap ceiling.
     *
     *   ≤2 cores  →  SerialGC  (no thread-stealing on weak CPUs)
     *   >2 cores  →  G1GC      (Aikar-tuned, pause-target 200ms)
     * RAM tiers   →  <4 GB=1 G, 4–8 GB=2 G, 8–16 GB=3 G, >16 GB=4 G
     */
    public static HardwareProfile auditHardware() {
        int  cores    = Runtime.getRuntime().availableProcessors();
        long sysMb    = detectSystemRamMb();
        int  maxGame  = sysMb < 4096 ? 1024
                      : sysMb < 8192 ? 2048
                      : sysMb < 16384 ? 3072
                      : 4096;
        GcMode gc     = cores <= 2 ? GcMode.SERIAL : GcMode.G1;
        String gcTag  = gc == GcMode.SERIAL ? "SerialGC" : "G1GC";
        String mode   = cores <= 2 ? "Low-Core" : "Performance";
        String label  = mode + " · " + gcTag + " · " + maxGame + " MB · " + cores + " core" + (cores == 1 ? "" : "s");
        return new HardwareProfile(cores, gc, maxGame, label);
    }

    /** Reads total physical RAM via runtime reflection — works on all major JDKs. */
    private static long detectSystemRamMb() {
        try {
            var os = java.lang.management.ManagementFactory.getOperatingSystemMXBean();
            var m  = os.getClass().getMethod("getTotalPhysicalMemorySize");
            m.setAccessible(true);
            return ((long) m.invoke(os)) / (1024 * 1024);
        } catch (Exception ignored) {}
        return 4096; // safe default
    }

    /**
     * Builds the JVM argument list for the Minecraft child process.
     *
     * For G1GC machines, applies Aikar's flags — the most battle-tested
     * Minecraft JVM configuration, dramatically reducing GC pause stutters.
     * Memory ceiling is proportional to detected physical RAM.
     */
    public static List<String> buildJvmArgs(HardwareProfile profile) {
        List<String> args = new ArrayList<>();
        int ram = profile.maxGameMb();
        args.add("-Xmx" + ram + "M");
        args.add("-Xms" + Math.min(512, ram / 4) + "M");
        args.add("-XX:+DisableExplicitGC");

        if (profile.gc() == GcMode.SERIAL) {
            args.add("-XX:+UseSerialGC");
        } else {
            // Aikar's G1GC flags — https://aikar.co/2018/07/02/tuning-the-jvm-g1gc-garbage-collector-flags-for-minecraft/
            args.add("-XX:+UseG1GC");
            args.add("-XX:+ParallelRefProcEnabled");
            args.add("-XX:MaxGCPauseMillis=200");
            args.add("-XX:+UnlockExperimentalVMOptions");
            args.add("-XX:+AlwaysPreTouch");
            args.add("-XX:G1NewSizePercent=30");
            args.add("-XX:G1MaxNewSizePercent=40");
            args.add("-XX:G1HeapRegionSize=8M");
            args.add("-XX:G1ReservePercent=20");
            args.add("-XX:G1HeapWastePercent=5");
            args.add("-XX:G1MixedGCCountTarget=4");
            args.add("-XX:InitiatingHeapOccupancyPercent=15");
            args.add("-XX:G1MixedGCLiveThresholdPercent=90");
            args.add("-XX:G1RSetUpdatingPauseTimePercent=5");
            args.add("-XX:SurvivorRatio=32");
            args.add("-XX:+PerfDisableSharedMem");
            args.add("-XX:MaxTenuringThreshold=1");
        }
        return args;
    }

    // ─────────────────────────────────────────────────────────────
    // 2. GHOST CLASSPATH RESOLVER
    // ─────────────────────────────────────────────────────────────

    public static List<String> buildClasspath(Path dotMinecraft, String version) throws IOException {
        Path versionsDir = dotMinecraft.resolve("versions").resolve(version);
        Path jsonFile    = versionsDir.resolve(version + ".json");
        Path librariesDir = dotMinecraft.resolve("libraries");

        if (!Files.exists(jsonFile)) throw new IOException("Version manifest not found: " + jsonFile);

        String json = Files.readString(jsonFile, StandardCharsets.UTF_8);
        List<String> classpath = new ArrayList<>();
        List<String> libraryBlocks = extractLibraryBlocks(json);

        for (String block : libraryBlocks) {
            Matcher pathMatcher = PATH_PATTERN.matcher(block);
            if (!pathMatcher.find()) continue;
            String relativePath = pathMatcher.group(1).replace('/', File.separatorChar);
            Path fullPath = librariesDir.resolve(relativePath);

            if (shouldIncludeForCurrentOS(block)) {
                if (Files.exists(fullPath)) classpath.add(fullPath.toString());
            }
        }

        Path versionJar = versionsDir.resolve(version + ".jar");
        if (Files.exists(versionJar)) classpath.add(versionJar.toString());

        return classpath;
    }

    private static List<String> extractLibraryBlocks(String json) {
        List<String> blocks = new ArrayList<>();
        int libStart = json.indexOf("\"libraries\"");
        if (libStart == -1) return blocks;
        int arrayOpen = json.indexOf('[', libStart);
        if (arrayOpen == -1) return blocks;

        int depth = 0, objStart = -1;
        for (int i = arrayOpen; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '{') {
                depth++;
                if (depth == 1) objStart = i;
            } else if (c == '}') {
                depth--;
                if (depth == 0 && objStart != -1) {
                    blocks.add(json.substring(objStart, i + 1));
                    objStart = -1;
                }
            } else if (c == ']' && depth == 0) {
                break;
            }
        }
        return blocks;
    }

    private static String currentOsKey() {
        String name = System.getProperty("os.name").toLowerCase();
        if (name.contains("win")) return "windows";
        if (name.contains("mac")) return "osx";
        return "linux";
    }

    private static boolean shouldIncludeForCurrentOS(String block) {
        String osKey = currentOsKey();
        int rulesStart = block.indexOf("\"rules\"");
        if (rulesStart == -1) return true;

        int arrayStart = block.indexOf('[', rulesStart);
        if (arrayStart == -1) return true;

        int arrayEnd = findClosingBracket(block, arrayStart);
        if (arrayEnd == -1) return true;

        String rulesSection = block.substring(arrayStart, arrayEnd + 1);
        List<String> ruleBlocks = splitObjects(rulesSection);

        boolean decision = false;
        for (String rule : ruleBlocks) {
            Matcher actionMatcher = ACTION_PATTERN.matcher(rule);
            if (!actionMatcher.find()) continue;
            String action = actionMatcher.group(1);

            if (OS_NAME_PATTERN.matcher(rule).find()) {
                Matcher valueMatcher = OS_VALUE_PATTERN.matcher(rule);
                if (!valueMatcher.find()) continue;
                if (valueMatcher.group(1).toLowerCase().equals(osKey)) {
                    decision = action.equals("allow");
                }
            } else {
                decision = action.equals("allow");
            }
        }
        return decision;
    }

    private static int findClosingBracket(String s, int openIndex) {
        int depth = 0;
        for (int i = openIndex; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '[') depth++;
            else if (c == ']') {
                depth--;
                if (depth == 0) return i;
            }
        }
        return -1;
    }

    private static List<String> splitObjects(String arrayStr) {
        List<String> objects = new ArrayList<>();
        int depth = 0, objStart = -1;
        for (int i = 0; i < arrayStr.length(); i++) {
            char c = arrayStr.charAt(i);
            if (c == '{') {
                depth++;
                if (depth == 1) objStart = i;
            } else if (c == '}') {
                depth--;
                if (depth == 0 && objStart != -1) {
                    objects.add(arrayStr.substring(objStart, i + 1));
                    objStart = -1;
                }
            }
        }
        return objects;
    }

    // ─────────────────────────────────────────────────────────────
    // 3. OFFLINE IDENTITY
    // ─────────────────────────────────────────────────────────────

    public static UUID offlineUUID(String username) {
        return UUID.nameUUIDFromBytes(("OfflinePlayer:" + username).getBytes(StandardCharsets.UTF_8));
    }

    // ─────────────────────────────────────────────────────────────
    // 4. LAUNCHER CORE
    // ─────────────────────────────────────────────────────────────

    public static void launchGame(Path dotMinecraft, String version, String username, Path javaExe, HardwareProfile profile, String versionJson) throws IOException {
        String osKey = currentOsKey();
        List<String> classpath = buildClasspath(dotMinecraft, version, versionJson, osKey);
        if (classpath.isEmpty()) throw new IOException("Classpath is empty.");

        List<String> command = new ArrayList<>();
        command.add(javaExe.toString());
        command.addAll(buildJvmArgs(profile));
        command.add("-cp");
        command.add(String.join(File.pathSeparator, classpath));
        command.add(detectMainClass(versionJson));

        String assetIndex = detectAssetIndex(versionJson);
        command.addAll(Arrays.asList(
            "--username", username,
            "--version", version,
            "--gameDir", dotMinecraft.toString(),
            "--assetsDir", dotMinecraft.resolve("assets").toString(),
            "--assetIndex", assetIndex,
            "--uuid", offlineUUID(username).toString(),
            "--accessToken", "0",
            "--userType", "legacy",
            "--versionType", "release"
        ));

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(dotMinecraft.toFile());
        pb.redirectOutput(ProcessBuilder.Redirect.appendTo(new File("latest_launch.log")));
        pb.redirectErrorStream(true);

        Process p = pb.start();
        long deadline = System.currentTimeMillis() + 3000;
        while (!p.isAlive() && System.currentTimeMillis() < deadline) {
            try { Thread.sleep(100); } catch (InterruptedException ignored) {}
        }
        if (!p.isAlive()) throw new IOException("Game crashed on startup.");
    }

    public static void waitForLogMarker(String marker, int timeoutSeconds) {
        Path log = Path.of("latest_launch.log");
        long start = System.currentTimeMillis();
        long limit = start + (timeoutSeconds * 1000L);
        
        try {
            long lastPos = Files.exists(log) ? Files.size(log) : 0;
            while (System.currentTimeMillis() < limit) {
                if (Files.exists(log)) {
                    long size = Files.size(log);
                    if (size > lastPos) {
                        try (RandomAccessFile raf = new RandomAccessFile(log.toFile(), "r")) {
                            raf.seek(lastPos);
                            String line;
                            while ((line = raf.readLine()) != null) {
                                if (line.contains(marker)) return;
                            }
                            lastPos = raf.getFilePointer();
                        }
                    }
                }
                Thread.sleep(500);
            }
        } catch (Exception ignored) {}
    }

    private static String detectMainClass(String json) {
        Matcher m = Pattern.compile("\"mainClass\"\\s*:\\s*\"([^\"]+)\"").matcher(json);
        return m.find() ? m.group(1) : "net.minecraft.client.main.Main";
    }

    private static String detectAssetIndex(String json) {
        int start = json.indexOf("\"assetIndex\"");
        if (start != -1) {
            Matcher m = ID_PATTERN.matcher(json.substring(start, json.indexOf('}', start) + 1));
            if (m.find()) return m.group(1);
        }
        return "legacy";
    }

    public static void ensureEnvironment(Path dotMinecraft, String version, Consumer<String> status) throws IOException, InterruptedException {
        Path versionRoot = dotMinecraft.resolve("versions").resolve(version);
        Path jsonFile = versionRoot.resolve(version + ".json");
        Files.createDirectories(versionRoot);

        if (!Files.exists(jsonFile)) {
            status.accept("🌐 Fetching manifest...");
            HttpRequest req = HttpRequest.newBuilder().uri(URI.create(MANIFEST_URL)).build();
            String manifest = client.send(req, HttpResponse.BodyHandlers.ofString()).body();
            
            int idx = manifest.indexOf("\"id\": \"" + version + "\"");
            if (idx == -1) throw new IOException("Version not found.");
            
            int objStart = manifest.lastIndexOf('{', idx);
            int objEnd = findClosingBracket(manifest, objStart, '{', '}');
            Matcher m = URL_PATTERN.matcher(manifest.substring(objStart, objEnd + 1));
            if (m.find()) {
                status.accept("📥 Downloading " + version + ".json...");
                client.send(HttpRequest.newBuilder().uri(URI.create(m.group(1))).build(), HttpResponse.BodyHandlers.ofFile(jsonFile));
            }
        }

        String json = Files.readString(jsonFile, StandardCharsets.UTF_8);
        String osKey = currentOsKey();
        
        // --- 1. Download Libraries (Parallel) ---
        status.accept("📚 Synchronizing libraries...");
        List<String> libraryBlocks = extractLibraryBlocks(json);
        Path librariesDir = dotMinecraft.resolve("libraries");
        List<CompletableFuture<Void>> libFutures = new ArrayList<>();
        ExecutorService libPool = Executors.newFixedThreadPool(8);

        for (String block : libraryBlocks) {
            if (!shouldIncludeForCurrentOS(block, osKey)) continue;
            
            Matcher pathMatcher = PATH_PATTERN.matcher(block);
            Matcher urlMatcher = URL_PATTERN.matcher(block);
            if (pathMatcher.find() && urlMatcher.find()) {
                Path libPath = librariesDir.resolve(pathMatcher.group(1).replace('/', File.separatorChar));
                if (!Files.exists(libPath)) {
                    libFutures.add(CompletableFuture.runAsync(() -> {
                        try {
                            Files.createDirectories(libPath.getParent());
                            client.send(HttpRequest.newBuilder().uri(URI.create(urlMatcher.group(1))).build(), HttpResponse.BodyHandlers.ofFile(libPath));
                        } catch (Exception e) {
                            System.err.println("Failed to download library: " + libPath + " - " + e.getMessage());
                        }
                    }, libPool));
                }
            }
        }
        if (!libFutures.isEmpty()) {
            CompletableFuture.allOf(libFutures.toArray(new CompletableFuture[0])).join();
        }
        libPool.shutdown();

        // --- 2. Download Asset Index & Objects ---
        int assetStart = json.indexOf("\"assetIndex\"");
        if (assetStart != -1) {
            int assetEnd = findClosingBracket(json, assetStart, '{', '}');
            Matcher m = URL_PATTERN.matcher(json.substring(assetStart, assetEnd + 1));
            Matcher idM = ID_PATTERN.matcher(json.substring(assetStart, assetEnd + 1));
            if (m.find() && idM.find()) {
                Path indexFile = dotMinecraft.resolve("assets").resolve("indexes").resolve(idM.group(1) + ".json");
                if (!Files.exists(indexFile)) {
                    status.accept("📂 Syncing asset index...");
                    Files.createDirectories(indexFile.getParent());
                    client.send(HttpRequest.newBuilder().uri(URI.create(m.group(1))).build(), HttpResponse.BodyHandlers.ofFile(indexFile));
                }
                
                // Verify & repair assets
                String indexJson = Files.readString(indexFile, StandardCharsets.UTF_8);
                verifyAndRepairAssets(dotMinecraft, indexJson, status);
            }
        }

        // --- 3. Download Client JAR ---
        Path clientJar = versionRoot.resolve(version + ".jar");
        if (!Files.exists(clientJar)) {
            int start = json.indexOf("\"client\"");
            int end = findClosingBracket(json, start, '{', '}');
            Matcher m = URL_PATTERN.matcher(json.substring(start, end + 1));
            if (m.find()) {
                status.accept("📥 Downloading client jar...");
                client.send(HttpRequest.newBuilder().uri(URI.create(m.group(1))).build(), HttpResponse.BodyHandlers.ofFile(clientJar));
            }
        }
    }

    /**
     * Asset Guardian — verifies every asset object against its expected file size,
     * then re-downloads any missing or corrupt objects in parallel.
     *
     * Fast-path: compares File.size() against the index's "size" field (O(stat)).
     * Downloads: bounded thread pool, 3-attempt exponential-backoff retry,
     *            atomic tmp→rename writes to prevent half-written corruption.
     */
    private static void verifyAndRepairAssets(Path dotMinecraft, String indexJson,
                                              Consumer<String> status) throws InterruptedException {
        Path objectsDir = dotMinecraft.resolve("assets").resolve("objects");

        // Parse all {hash, size} pairs — Mojang index always has hash before size
        Pattern entryPattern = Pattern.compile(
            "\"hash\"\\s*:\\s*\"([a-f0-9]{40})\"[^}]{0,100}\"size\"\\s*:\\s*(\\d+)"
        );
        Matcher m = entryPattern.matcher(indexJson);
        List<String> hashes = new ArrayList<>();
        List<Long>   sizes  = new ArrayList<>();
        while (m.find()) {
            hashes.add(m.group(1));
            sizes.add(Long.parseLong(m.group(2)));
        }

        int total = hashes.size();
        status.accept("🔍 Verifying " + total + " assets...");

        // Identify assets that are missing or have wrong file size (corrupt / partial)
        List<String> toRepair = new ArrayList<>();
        for (int i = 0; i < total; i++) {
            String hash         = hashes.get(i);
            long   expectedSize = sizes.get(i);
            Path   file = objectsDir.resolve(hash.substring(0, 2)).resolve(hash);
            try {
                if (!Files.exists(file) || Files.size(file) != expectedSize) {
                    toRepair.add(hash);
                }
            } catch (IOException e) {
                toRepair.add(hash); // unreadable → treat as missing
            }
        }

        if (toRepair.isEmpty()) {
            status.accept("✅ All " + total + " assets verified — nothing to repair");
            return;
        }

        int needed = toRepair.size();
        status.accept("⬇️ Repairing " + needed + " assets (" + (total - needed) + "/" + total + " OK)...");

        // Parallel download pool
        int threadCount = Math.min(8, Runtime.getRuntime().availableProcessors() * 2);
        ExecutorService pool     = Executors.newFixedThreadPool(threadCount);
        AtomicInteger   repaired = new AtomicInteger(0);
        AtomicInteger   failed   = new AtomicInteger(0);
        List<CompletableFuture<Void>> futures = new ArrayList<>(needed);

        for (String hash : toRepair) {
            futures.add(CompletableFuture.runAsync(() -> {
                String sub  = hash.substring(0, 2);
                Path   file = objectsDir.resolve(sub).resolve(hash);
                String url  = "https://resources.download.minecraft.net/" + sub + "/" + hash;

                for (int attempt = 0; attempt < 3; attempt++) {
                    try {
                        Files.createDirectories(file.getParent());
                        Path tmp = file.resolveSibling(hash + ".tmp");
                        client.send(
                            HttpRequest.newBuilder().uri(URI.create(url)).build(),
                            HttpResponse.BodyHandlers.ofFile(tmp)
                        );
                        Files.move(tmp, file,
                            StandardCopyOption.ATOMIC_MOVE,
                            StandardCopyOption.REPLACE_EXISTING);
                        int n = repaired.incrementAndGet();
                        // throttle UI updates: every 50 files, or on completion
                        if (n % 50 == 0 || n == needed) {
                            status.accept("⬇️ Repairing assets: " + n + "/" + needed);
                        }
                        return; // success
                    } catch (Exception e) {
                        if (attempt < 2) {
                            try { Thread.sleep(400L * (1 << attempt)); } // 400ms, 800ms
                            catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                        }
                    }
                }
                // All 3 attempts failed
                failed.incrementAndGet();
                System.err.println("[AssetGuardian] Failed to download: " + hash);
            }, pool));
        }

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        pool.shutdown();
        pool.awaitTermination(5, TimeUnit.SECONDS);

        int ok   = repaired.get();
        int fail = failed.get();
        if (fail == 0) {
            status.accept("✅ Asset repair complete — " + ok + " objects restored");
        } else {
            status.accept("⚠️ Repair done: " + ok + " OK, " + fail + " failed (see stderr)");
        }
    }

    public static List<String> buildClasspath(Path dotMinecraft, String version, String json, String osKey) throws IOException {
        Path librariesDir = dotMinecraft.resolve("libraries");
        List<String> classpath = new ArrayList<>();
        List<String> libraryBlocks = extractLibraryBlocks(json);

        for (String block : libraryBlocks) {
            Matcher pathMatcher = PATH_PATTERN.matcher(block);
            if (!pathMatcher.find()) continue;
            String relativePath = pathMatcher.group(1).replace('/', File.separatorChar);
            Path fullPath = librariesDir.resolve(relativePath);

            if (shouldIncludeForCurrentOS(block, osKey)) {
                if (Files.exists(fullPath)) classpath.add(fullPath.toString());
            }
        }

        Path versionJar = dotMinecraft.resolve("versions").resolve(version).resolve(version + ".jar");
        if (Files.exists(versionJar)) classpath.add(versionJar.toString());

        return classpath;
    }

    private static boolean shouldIncludeForCurrentOS(String block, String osKey) {
        int rulesStart = block.indexOf("\"rules\"");
        if (rulesStart == -1) return true;

        int arrayStart = block.indexOf('[', rulesStart);
        if (arrayStart == -1) return true;

        int arrayEnd = findClosingBracket(block, arrayStart, '[', ']');
        if (arrayEnd == -1) return true;

        String rulesSection = block.substring(arrayStart, arrayEnd + 1);
        List<String> ruleBlocks = splitObjects(rulesSection);

        boolean decision = false;
        for (String rule : ruleBlocks) {
            Matcher actionMatcher = ACTION_PATTERN.matcher(rule);
            if (!actionMatcher.find()) continue;
            String action = actionMatcher.group(1);

            if (OS_NAME_PATTERN.matcher(rule).find()) {
                Matcher valueMatcher = OS_VALUE_PATTERN.matcher(rule);
                if (!valueMatcher.find()) continue;
                if (valueMatcher.group(1).toLowerCase().equals(osKey)) {
                    decision = action.equals("allow");
                }
            } else {
                decision = action.equals("allow");
            }
        }
        return decision;
    }

    private static int findClosingBracket(String s, int openIndex, char open, char close) {
        int depth = 0;
        for (int i = openIndex; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == open) depth++;
            else if (c == close) {
                depth--;
                if (depth == 0) return i;
            }
        }
        return -1;
    }

    public static void logLoadTime(long durationMs) {
        Path logFile = Path.of("nimbus_load_times.log");
        String stamp = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        String entry = String.format("[%s] Nimbus Prime Load Time: %.2f seconds\n", stamp, durationMs / 1000.0);
        try {
            Files.writeString(logFile, entry, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            System.err.println("Failed to log load time: " + e.getMessage());
        }
    }

    public static void warmup() {
        CompletableFuture.runAsync(() -> { try { auditHardware(); } catch (Exception ignored) {} });
    }
}
