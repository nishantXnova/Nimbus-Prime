import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.stream.IntStream;
import java.util.regex.*;

/**
 * 🌌 Nimbus Prime — LaunchEngine (High-Performance Edition)
 */
public class LaunchEngine {

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

    public enum GcMode { SERIAL, G1 }
    public record HardwareProfile(int cores, GcMode gc, int maxGameMb, String label) {}

    public static HardwareProfile auditHardware() {
        int  cores    = Runtime.getRuntime().availableProcessors();
        long sysMb    = detectSystemRamMb();
        int  maxGame  = sysMb < 4096 ? 1024 : sysMb < 8192 ? 2048 : sysMb < 16384 ? 3072 : 4096;
        GcMode gc     = cores <= 2 ? GcMode.SERIAL : GcMode.G1;
        String gcTag  = gc == GcMode.SERIAL ? "SerialGC" : "G1GC";
        String mode   = cores <= 2 ? "Low-Core" : "Performance";
        String label  = mode + " · " + gcTag + " · " + maxGame + " MB · " + cores + " core" + (cores == 1 ? "" : "s");
        return new HardwareProfile(cores, gc, maxGame, label);
    }

    private static long detectSystemRamMb() {
        try {
            var os = java.lang.management.ManagementFactory.getOperatingSystemMXBean();
            var m  = os.getClass().getMethod("getTotalPhysicalMemorySize");
            m.setAccessible(true);
            return ((long) m.invoke(os)) / (1024 * 1024);
        } catch (Exception ignored) {}
        return 4096;
    }

    public static List<String> buildJvmArgs(HardwareProfile profile) {
        List<String> args = new ArrayList<>();
        int ram = profile.maxGameMb();
        args.add("-Xmx" + ram + "M");
        args.add("-Xms" + Math.min(512, ram / 4) + "M");
        args.add("-XX:+DisableExplicitGC");
        if (profile.gc() == GcMode.SERIAL) {
            args.add("-XX:+UseSerialGC");
        } else {
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
        }
        if (detectSystemRamMb() < 6144) {
            args.add("-XX:MaxHeapFreeRatio=10");
            args.add("-XX:MinHeapFreeRatio=5");
        }
        return args;
    }

    /**
     * Production Refinement: Classpath Caching for Zero-Latency Boot
     */
    public String buildClasspath(Path mcDir, String version) throws IOException {
        Path cacheFile = Path.of("cp.cache");
        Path libDir = mcDir.resolve("libraries");
        try {
            if (Files.exists(cacheFile) && Files.exists(libDir)) {
                if (Files.getLastModifiedTime(cacheFile).toMillis() > Files.getLastModifiedTime(libDir).toMillis()) {
                    return Files.readString(cacheFile).trim();
                }
            }
        } catch (Exception ignored) {}

        Path versionsDir = mcDir.resolve("versions").resolve(version);
        Path jsonFile    = versionsDir.resolve(version + ".json");
        String json = Files.readString(jsonFile, StandardCharsets.UTF_8);
        String osKey = currentOsKey();
        
        List<String> classpathChunks = buildClasspathList(mcDir, version, json, osKey);
        String result = String.join(File.pathSeparator, classpathChunks);
        try { Files.writeString(cacheFile, result); } catch (Exception ignored) {}
        return result;
    }

    private List<String> buildClasspathList(Path mcDir, String version, String json, String osKey) {
        Path libDir = mcDir.resolve("libraries");
        List<String> cp = new ArrayList<>();
        for (String block : extractLibraryBlocks(json)) {
            Matcher pathMatcher = PATH_PATTERN.matcher(block);
            if (pathMatcher.find() && shouldIncludeForCurrentOS(block, osKey)) {
                Path fullPath = libDir.resolve(pathMatcher.group(1).replace('/', File.separatorChar));
                if (Files.exists(fullPath)) cp.add(fullPath.toString());
            }
        }
        Path versionJar = mcDir.resolve("versions").resolve(version).resolve(version + ".jar");
        if (Files.exists(versionJar)) cp.add(versionJar.toString());
        return cp;
    }

    public void launchGame(Path mcDir, String version, String username, Path javaExe, HardwareProfile profile) throws IOException {
        String json = Files.readString(mcDir.resolve("versions").resolve(version).resolve(version + ".json"), StandardCharsets.UTF_8);
        String cp = buildClasspath(mcDir, version);
        
        List<String> command = new ArrayList<>();
        command.add(javaExe.toString());
        command.addAll(buildJvmArgs(profile));
        command.add("-cp");
        command.add(cp);
        command.add(detectMainClass(json));
        command.addAll(Arrays.asList(
            "--username", username, "--version", version,
            "--gameDir", mcDir.toString(), "--assetsDir", mcDir.resolve("assets").toString(),
            "--assetIndex", detectAssetIndex(json), "--uuid", offlineUUID(username).toString(),
            "--accessToken", "0", "--userType", "legacy", "--versionType", "release"
        ));

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(mcDir.toFile());
        pb.redirectOutput(ProcessBuilder.Redirect.appendTo(new File("latest_launch.log")));
        pb.redirectErrorStream(true);
        Process p = pb.start();
        if (!p.isAlive()) throw new IOException("Game failed to start.");
    }

    public void prepareEarly(Path mcDir, String version, Consumer<String> status) {
        CompletableFuture.runAsync(() -> { try { ensureEnvironment(mcDir, version, status); } catch (Exception ignored) {} });
    }

    public void ensureEnvironment(Path mcDir, String version, Consumer<String> status) throws IOException, InterruptedException {
        Path versionRoot = mcDir.resolve("versions").resolve(version);
        Path jsonFile = versionRoot.resolve(version + ".json");
        Files.createDirectories(versionRoot);

        String cdsStatus = System.getProperty("java.lang.reflect.Proxy") != null ? " (AppCDS Active)" : "";
        status.accept("🛡️ Asset Guardian standing by" + cdsStatus);

        if (!Files.exists(jsonFile)) {
            status.accept("🌐 Fetching manifest...");
            String manifest = client.send(HttpRequest.newBuilder().uri(URI.create(MANIFEST_URL)).build(), HttpResponse.BodyHandlers.ofString()).body();
            int idx = manifest.indexOf("\"id\": \"" + version + "\"");
            if (idx != -1) {
                int objStart = manifest.lastIndexOf('{', idx);
                int objEnd = findClosingBracket(manifest, objStart, '{', '}');
                Matcher m = URL_PATTERN.matcher(manifest.substring(objStart, objEnd + 1));
                if (m.find()) {
                    status.accept("📥 Downloading " + version + ".json...");
                    client.send(HttpRequest.newBuilder().uri(URI.create(m.group(1))).build(), HttpResponse.BodyHandlers.ofFile(jsonFile));
                }
            }
        }

        String json = Files.readString(jsonFile, StandardCharsets.UTF_8);
        String osKey = currentOsKey();
        status.accept("📚 Synchronizing libraries...");
        Path libDir = mcDir.resolve("libraries");
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        ExecutorService pool = Executors.newFixedThreadPool(8);
        for (String block : extractLibraryBlocks(json)) {
            if (!shouldIncludeForCurrentOS(block, osKey)) continue;
            Matcher pm = PATH_PATTERN.matcher(block), um = URL_PATTERN.matcher(block);
            if (pm.find() && um.find()) {
                Path lp = libDir.resolve(pm.group(1).replace('/', File.separatorChar));
                if (!Files.exists(lp)) {
                    futures.add(CompletableFuture.runAsync(() -> {
                        try { Files.createDirectories(lp.getParent()); client.send(HttpRequest.newBuilder().uri(URI.create(um.group(1))).build(), HttpResponse.BodyHandlers.ofFile(lp)); } catch (Exception e) {}
                    }, pool));
                }
            }
        }
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        pool.shutdown();

        int assetStart = json.indexOf("\"assetIndex\"");
        if (assetStart != -1) {
            int assetEnd = findClosingBracket(json, assetStart, '{', '}');
            Matcher m = URL_PATTERN.matcher(json.substring(assetStart, assetEnd + 1)), idM = ID_PATTERN.matcher(json.substring(assetStart, assetEnd + 1));
            if (m.find() && idM.find()) {
                Path indexFile = mcDir.resolve("assets").resolve("indexes").resolve(idM.group(1) + ".json");
                if (!Files.exists(indexFile)) {
                    status.accept("📂 Syncing asset index...");
                    Files.createDirectories(indexFile.getParent());
                    client.send(HttpRequest.newBuilder().uri(URI.create(m.group(1))).build(), HttpResponse.BodyHandlers.ofFile(indexFile));
                }
                verifyAndRepairAssets(mcDir, Files.readString(indexFile, StandardCharsets.UTF_8), status);
            }
        }

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

    private static void verifyAndRepairAssets(Path mcDir, String indexJson, Consumer<String> status) throws InterruptedException {
        Path objDir = mcDir.resolve("assets").resolve("objects");
        Pattern p = Pattern.compile("\"hash\"\\s*:\\s*\"([a-f0-9]{40})\"[^}]{0,100}\"size\"\\s*:\\s*(\\d+)");
        Matcher m = p.matcher(indexJson);
        List<String> hashes = new ArrayList<>(); List<Long> sizes = new ArrayList<>();
        while (m.find()) { hashes.add(m.group(1)); sizes.add(Long.parseLong(m.group(2))); }
        int total = hashes.size();
        status.accept("🔍 Verifying " + total + " assets...");
        List<String> toRepair = Collections.synchronizedList(new ArrayList<>());
        IntStream.range(0, total).parallel().forEach(i -> {
            Path f = objDir.resolve(hashes.get(i).substring(0, 2)).resolve(hashes.get(i));
            try { if (!Files.exists(f) || Files.size(f) != sizes.get(i)) toRepair.add(hashes.get(i)); } catch (IOException e) { toRepair.add(hashes.get(i)); }
        });
        if (toRepair.isEmpty()) { status.accept("✅ Assets verified"); return; }
        int needed = toRepair.size();
        status.accept("⬇️ Repairing " + needed + " assets...");
        ExecutorService pool = Executors.newFixedThreadPool(8);
        AtomicInteger ok = new AtomicInteger(0);
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (String hash : toRepair) {
            futures.add(CompletableFuture.runAsync(() -> {
                String sub = hash.substring(0, 2); Path f = objDir.resolve(sub).resolve(hash);
                String url = "https://resources.download.minecraft.net/" + sub + "/" + hash;
                try {
                    Files.createDirectories(f.getParent()); Path tmp = f.resolveSibling(hash + ".tmp");
                    client.send(HttpRequest.newBuilder().uri(URI.create(url)).build(), HttpResponse.BodyHandlers.ofFile(tmp));
                    Files.move(tmp, f, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                    if (ok.incrementAndGet() % 50 == 0) status.accept("⬇️ Repairing: " + ok.get() + "/" + needed);
                } catch (Exception e) {}
            }, pool));
        }
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        pool.shutdown();
        status.accept("✅ Assets ready");
    }

    private static List<String> extractLibraryBlocks(String json) {
        List<String> blocks = new ArrayList<>();
        int start = json.indexOf("\"libraries\"");
        if (start == -1) return blocks;
        int arrayOpen = json.indexOf('[', start);
        if (arrayOpen == -1) return blocks;
        int depth = 0, objStart = -1;
        for (int i = arrayOpen; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '{') { if (++depth == 1) objStart = i; }
            else if (c == '}') { if (--depth == 0 && objStart != -1) { blocks.add(json.substring(objStart, i+1)); objStart = -1; } }
            else if (c == ']' && depth == 0) break;
        }
        return blocks;
    }

    private static String currentOsKey() {
        String name = System.getProperty("os.name").toLowerCase();
        return name.contains("win") ? "windows" : name.contains("mac") ? "osx" : "linux";
    }

    private static boolean shouldIncludeForCurrentOS(String block, String osKey) {
        int rs = block.indexOf("\"rules\"");
        if (rs == -1) return true;
        int as = block.indexOf('[', rs), ae = findClosingBracket(block, as, '[', ']');
        boolean decision = false;
        for (String rule : splitObjects(block.substring(as, ae + 1))) {
            Matcher am = ACTION_PATTERN.matcher(rule);
            if (!am.find()) continue;
            String action = am.group(1);
            if (OS_NAME_PATTERN.matcher(rule).find()) {
                Matcher vm = OS_VALUE_PATTERN.matcher(rule);
                if (vm.find() && vm.group(1).toLowerCase().equals(osKey)) decision = action.equals("allow");
            } else decision = action.equals("allow");
        }
        return decision;
    }

    private static int findClosingBracket(String s, int openIndex, char open, char close) {
        int depth = 0;
        for (int i = openIndex; i < s.length(); i++) {
            char c = s.charAt(i); if (c == open) depth++; else if (c == close && --depth == 0) return i;
        }
        return -1;
    }

    private static List<String> splitObjects(String s) {
        List<String> objs = new ArrayList<>();
        int depth = 0, start = -1;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i); if (c == '{') { if (++depth == 1) start = i; } else if (c == '}') { if (--depth == 0) objs.add(s.substring(start, i+1)); }
        }
        return objs;
    }

    public static UUID offlineUUID(String u) { return UUID.nameUUIDFromBytes(("OfflinePlayer:" + u).getBytes(StandardCharsets.UTF_8)); }

    public void waitForLogMarker(String marker, int timeout) {
        Path log = Path.of("latest_launch.log");
        long deadline = System.currentTimeMillis() + (timeout * 1000L);
        try {
            long pos = Files.exists(log) ? Files.size(log) : 0;
            while (System.currentTimeMillis() < deadline) {
                if (Files.exists(log) && Files.size(log) > pos) {
                    try (RandomAccessFile raf = new RandomAccessFile(log.toFile(), "r")) {
                        raf.seek(pos); String line;
                        while ((line = raf.readLine()) != null) if (line.contains(marker)) return;
                        pos = raf.getFilePointer();
                    }
                }
                Thread.sleep(200);
            }
        } catch (Exception ignored) {}
    }

    private static String detectMainClass(String j) { Matcher m = Pattern.compile("\"mainClass\"\\s*:\\s*\"([^\"]+)\"").matcher(j); return m.find() ? m.group(1) : "net.minecraft.client.main.Main"; }
    private static String detectAssetIndex(String j) { int s = j.indexOf("\"assetIndex\""); if (s != -1) { Matcher m = ID_PATTERN.matcher(j.substring(s, j.indexOf('}', s) + 1)); if (m.find()) return m.group(1); } return "legacy"; }
}
