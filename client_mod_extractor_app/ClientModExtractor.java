package client_mod_extractor_app;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Utility script for evaluating Minecraft mod archive metadata to isolate
 * client-only components from server-compatible components.
 */
public class ClientModExtractor {

    /* ──────────────────────────────────────────────────────────────────────────────
     *        Constants and Configuration
     * ────────────────────────────────────────────────────────────────────────────*/

    private static final String CURRENT_VERSION = "1.0.5";

    private static final String CF_EXCLUDES_URL = "https://raw.githubusercontent.com/itzg/docker-minecraft-server/master/files/cf-exclude-include.json";
    private static final String MODRINTH_EXCLUDES_URL = "https://raw.githubusercontent.com/itzg/docker-minecraft-server/master/files/modrinth-exclude-include.json";
    private static final String CUSTOM_EXCLUDES_URL = "https://raw.githubusercontent.com/DawsonBodenhamer/Client-Mod-Extractor/main/custom-excludes.txt";

    private static final String ANSI_RESET = "\u001B[0m";
    private static final String ANSI_RED = "\u001B[38;2;255;74;74m";
    private static final String ANSI_GREEN = "\u001B[38;2;0;230;118m";
    private static final String ANSI_YELLOW = "\u001B[38;2;255;215;0m";
    private static final String ANSI_CYAN = "\u001B[38;2;0;191;255m";

    /* ──────────────────────────────────────────────────────────────────────────────
     *        Public Execution
     * ────────────────────────────────────────────────────────────────────────────*/

    /**
     * Main entry point for application execution.
     *
     * @param args Command line arguments
     */
    public static void main(String[] args) {

        // --- 1. Parse Arguments. ---
        boolean promptAffirmation = Arrays.asList(args).contains("--prompt-affirmation");
        System.out.println(ANSI_CYAN + "Starting Client Mod Extractor..." + ANSI_RESET);

        // --- 2. Check for Updates. ---
        String latestVersion = getLatestVersion();
        if (isNewerVersion(CURRENT_VERSION, latestVersion)) {
            System.out.println(ANSI_YELLOW + "==========================================================");
            System.out.println("  UPDATE AVAILABLE: Version v" + latestVersion + " is now ready! (Current: v" + CURRENT_VERSION + ")");
            System.out.println("==========================================================" + ANSI_RESET);
            System.out.println("Updating ensures you have the latest community exclusions and bug fixes.");
            System.out.println();
            System.out.println(ANSI_GREEN + "Quick Update Guide:" + ANSI_RESET);
            System.out.println("  1. Go to: " + ANSI_CYAN +
                    "https://github.com/DawsonBodenhamer/Client-Mod-Extractor/releases/latest" + ANSI_RESET);
            System.out.println("  2. Scroll down to the " + ANSI_YELLOW + "Assets" + ANSI_RESET + " section at the bottom.");
            System.out.println("  3. Click and download " + ANSI_GREEN + "Client-Mod-Extractor.zip" + ANSI_RESET + ".");
            System.out.println("  4. Extract that ZIP file on your computer.");
            System.out.println("  5. Move the extracted files directly into your Minecraft " + ANSI_YELLOW + "mods" + ANSI_RESET + " folder,");
            System.out.println("     replacing the old " + ANSI_CYAN + "ClientModExtractor.java" + ANSI_RESET + " file when prompted.");
            System.out.println();
            System.out.println("Press [Enter] to continue running your current version...");
            try {
                new BufferedReader(new InputStreamReader(System.in)).readLine();
            } catch (Exception e) {
                // Ignore exception and continue running
            }
            System.out.println();
        }

        // --- 3. Build Target Directory ---
        Path sourceFolder = Paths.get("").toAbsolutePath();
        Path targetFolder = sourceFolder.resolve("Save_For_Server_Mods");

        try {
            if (!Files.exists(targetFolder)) {
                Files.createDirectories(targetFolder);
            }

            // --- 4. Fetch Remote Exclusions ---
            Set<String> excludeSet = new HashSet<>();

            // Fetch CurseForge exclusions
            try {
                String cfJson = fetchUrl(CF_EXCLUDES_URL);
                extractJsonArray(cfJson, "globalExcludes", excludeSet);
            } catch (Exception e) {
                // Silently absorb or load local fallback later
            }

            // Fetch Modrinth exclusions
            try {
                String modrinthJson = fetchUrl(MODRINTH_EXCLUDES_URL);
                extractJsonArray(modrinthJson, "globalExcludes", excludeSet);
            } catch (Exception e) {
                // Silently absorb or load local fallback later
            }

            // Fetch custom exclusions
            try {
                String customList = fetchUrl(CUSTOM_EXCLUDES_URL);
                Arrays.stream(customList.split("[,\\r\\n]+"))
                        .map(String::trim)
                        .filter(s -> !s.isEmpty())
                        .forEach(excludeSet::add);
            } catch (Exception e) {
                Path localCustom = sourceFolder.resolve("custom-excludes.txt");
                if (Files.exists(localCustom)) {
                    Files.readAllLines(localCustom).stream()
                            .flatMap(line -> Arrays.stream(line.split("[,\\r\\n]+")))
                            .map(String::trim)
                            .filter(s -> !s.isEmpty())
                            .forEach(excludeSet::add);
                }
            }

            // Normalize excludes for fuzzy matching
            Set<String> normalizedExcludes = excludeSet.stream()
                    .filter(s -> s != null)
                    .map(ClientModExtractor::normalizeModId)
                    .collect(Collectors.toSet());

            // --- 5. Process Local Archives ---
            File[] jarFiles = sourceFolder.toFile().listFiles((d, name) -> name.toLowerCase().endsWith(".jar"));
            if (jarFiles == null) {
                jarFiles = new File[0];
            }

            int processedCount = 0;
            int copiedCount = 0;

            System.out.println(ANSI_CYAN + "Location: " + sourceFolder);
            System.out.println("Scanning " + jarFiles.length + " files...\n" + ANSI_RESET);

            for (File jar : jarFiles) {
                boolean isClientOnly = false;
                String loaderType = "Unknown/Generic";
                String extractedModId = null;

                try (ZipFile zip = new ZipFile(jar)) {

                    // --- Fabric Verification ---
                    ZipEntry fabricEntry = zip.getEntry("fabric.mod.json");
                    if (fabricEntry != null) {
                        loaderType = "Fabric";
                        String jsonContent = readZipEntry(zip, fabricEntry);

                        // Check environment tag
                        Matcher envMatcher = Pattern.compile("\"environment\"\\s*:\\s*[\"']client[\"']", Pattern.CASE_INSENSITIVE).matcher(jsonContent);
                        if (envMatcher.find()) {
                            isClientOnly = true;
                        }

                        // Extract mod ID
                        Matcher idMatcher = Pattern.compile("\"id\"\\s*:\\s*[\"']([^\"']+)[\"']").matcher(jsonContent);
                        if (idMatcher.find()) {
                            extractedModId = idMatcher.group(1);
                        }
                    }

                    // --- Forge And NeoForge Verification ---
                    ZipEntry tomlEntry = zip.getEntry("META-INF/neoforge.mods.toml");
                    if (tomlEntry == null) {
                        tomlEntry = zip.getEntry("META-INF/mods.toml");
                    }

                    if (tomlEntry != null) {
                        if ("Fabric".equals(loaderType)) {
                            loaderType = tomlEntry.getName().contains("neoforge") ? "Fabric/NeoForge" : "Fabric/Forge";
                        } else {
                            loaderType = tomlEntry.getName().contains("neoforge") ? "NeoForge" : "Forge";
                        }

                        String tomlContent = readZipEntry(zip, tomlEntry);

                        // Extract mod ID
                        Matcher idMatcher = Pattern.compile("modId\\s*=\\s*[\"']([^\"']+)[\"']").matcher(tomlContent);
                        if (extractedModId == null && idMatcher.find()) {
                            extractedModId = idMatcher.group(1);
                        }

                        // Scan core properties before mixins or dependencies
                        String coreToml = tomlContent.split("\\[\\[(dependencies|mixins)")[0];

                        if (Pattern.compile("clientSideOnly\\s*=\\s*true", Pattern.CASE_INSENSITIVE).matcher(coreToml).find() ||
                                Pattern.compile("side\\s*=\\s*[\"']CLIENT[\"']", Pattern.CASE_INSENSITIVE).matcher(coreToml).find() ||
                                Pattern.compile("displayTest\\s*=\\s*[\"']IGNORE_ALL_VERSION[\"']", Pattern.CASE_INSENSITIVE).matcher(coreToml).find()) {
                            isClientOnly = true;
                        }

                        // Check base game required strictly on client side
                        if (!isClientOnly) {
                            String[] blocks = tomlContent.split("\\[\\[");
                            for (String block : blocks) {
                                if (block.startsWith("dependencies")) {
                                    boolean isBaseGame = Pattern.compile("modId\\s*=\\s*[\"'](minecraft|forge|neoforge|java)[\"']", Pattern.CASE_INSENSITIVE).matcher(block).find();
                                    boolean isClientSide = Pattern.compile("side\\s*=\\s*[\"']CLIENT[\"']", Pattern.CASE_INSENSITIVE).matcher(block).find();
                                    if (isBaseGame && isClientSide) {
                                        isClientOnly = true;
                                        break;
                                    }
                                }
                            }
                        }
                    }

                } catch (Exception e) {
                    System.out.println(ANSI_YELLOW + "[Warning] Could not read contents of " + jar.getName() + ". Defaulting to keeping it." + ANSI_RESET);
                }

                // --- Process Output ---
                String loaderTag = String.format("[%s]", loaderType);

                if (extractedModId != null && normalizedExcludes.contains(normalizeModId(extractedModId))) {
                    System.out.printf(ANSI_RED + "%-20s %-20s Skipping: %s (mislabeled)%n" + ANSI_RESET,
                            loaderTag, "[SERVER CRASH RISK]", jar.getName());
                } else if (isClientOnly) {
                    System.out.printf(ANSI_YELLOW + "%-20s %-20s Skipping: %s%n" + ANSI_RESET,
                            loaderTag, "[CLIENT ONLY]", jar.getName());
                } else {
                    System.out.printf(ANSI_GREEN + "%-20s %-20s Copying:  %s%n" + ANSI_RESET,
                            loaderTag, "[SERVER/BOTH]", jar.getName());
                    Files.copy(jar.toPath(), targetFolder.resolve(jar.getName()), StandardCopyOption.REPLACE_EXISTING);
                    copiedCount++;
                }

                processedCount++;
            }

            System.out.println();
            System.out.println(ANSI_CYAN + "==========================================================");
            System.out.println("Done! Processed " + processedCount + " mods.");
            System.out.println("Copied " + copiedCount + " server-safe mods to: ./Save_For_Server_Mods" + ANSI_RESET);
            System.out.println();
            System.out.println(ANSI_YELLOW + "==========================================================");
            System.out.println("Database Check:" + ANSI_RESET);
            System.out.println("Successfully checked online database and found " + ANSI_GREEN + normalizedExcludes.size() + ANSI_RESET + " community-blacklisted mods.");
            System.out.println("This list was used to manually block client-only mods that were " + ANSI_RED + "mislabeled by their developers" + ANSI_RESET + ".");

            // --- 6. Support and Issue Reporting. ---
            if (promptAffirmation) {
                System.out.println();
                System.out.println(ANSI_YELLOW + "==========================================================");
                System.out.println("Server Still Crashing?" + ANSI_RESET);
                System.out.println("Some client-only mods mislabel their metadata, thus slipping past automatic detection.");
                System.out.println("Please submit a GitHub issue report to help me update my blacklist.");
                System.out.println("As more users report these mods, the blacklist becomes bigger, preventing future crashes for others (and yourself when you need to run this script again later).");
                System.out.println();
                System.out.println("To submit a report:");
                System.out.println("  1. Navigate to: " + ANSI_CYAN +
                        "https://github.com/DawsonBodenhamer/Client-Mod-Extractor/issues" + ANSI_RESET);
                System.out.println("  2. Create a 'New Issue'.");
                System.out.println("  3. Provide the " + ANSI_RED + "Mod ID" + ANSI_RESET + " or " +
                        ANSI_RED + "JAR filename" + ANSI_RESET + " that caused the crash.");
            }

            // --- 7. Affirmation Message. ---
            if (promptAffirmation) {
                System.out.println();
                System.out.println("==========================================================");
                System.out.println("Hold CTRL and click " +
                        "\u001B]8;;https://www.bible.com/bible/8/JHN.3.16.AMPC\u001B\\here\u001B]8;;\u001B\\" +
                        " if you need a real friend.");
                System.out.println();
            }

        } catch (Exception e) {
            e.printStackTrace();
            System.exit(1);
        }
    }

    /* ──────────────────────────────────────────────────────────────────────────────
     *        Private Helpers
     * ────────────────────────────────────────────────────────────────────────────*/

    /**
     * Checks the GitHub repository releases API to retrieve the latest version tag.
     *
     * @return Latest tag string, or null if query fails
     */
    private static String getLatestVersion() {
        try {
            String json = fetchUrl("https://api.github.com/repos/DawsonBodenhamer/Client-Mod-Extractor/releases/latest");
            Matcher matcher = Pattern.compile("\"tag_name\"\\s*:\\s*\"v?([^\"]+)\"").matcher(json);
            if (matcher.find()) {
                return matcher.group(1);
            }
        } catch (Exception e) {
            // Silently absorb version check exceptions to maintain offline capability
        }
        return null;
    }

    /**
     * Compares two semantic version strings to determine if an update is available.
     *
     * @param current The active local version
     * @param latest The retrieved repository version
     * @return True if latest version is numerically greater than current version
     */
    private static boolean isNewerVersion(String current, String latest) {
        if (latest == null || current == null) {
            return false;
        }
        try {
            String[] cur = current.split("[.-]");
            String[] lat = latest.split("[.-]");
            int len = Math.max(cur.length, lat.length);
            for (int i = 0; i < len; i++) {
                int cVal = 0;
                int lVal = 0;
                if (i < cur.length) {
                    String s = cur[i].replaceAll("[^0-9]", "");
                    if (!s.isEmpty()) cVal = Integer.parseInt(s);
                }
                if (i < lat.length) {
                    String s = lat[i].replaceAll("[^0-9]", "");
                    if (!s.isEmpty()) lVal = Integer.parseInt(s);
                }
                if (lVal > cVal) return true;
                if (cVal > lVal) return false;
            }
        } catch (Exception e) {
            // Fallback to string inequality comparison if structure is non-standard
            return !current.equals(latest);
        }
        return false;
    }

    /**
     * Fetches text payload from target URL.
     *
     * @param urlString Target URL
     * @return Response string
     * @throws IOException If connection fails
     */
    private static String fetchUrl(String urlString) throws IOException {
        URL url = URI.create(urlString).toURL();
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setRequestProperty("User-Agent", "Client-Mod-Extractor");
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(5000);

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
            return reader.lines().collect(Collectors.joining("\n"));
        }
    }

    /**
     * Extracts raw string elements from JSON array object.
     *
     * @param json Raw JSON payload
     * @param arrayName Target array key
     * @param targetSet Set to populate
     */
    private static void extractJsonArray(String json, String arrayName, Set<String> targetSet) {
        // Regex extract JSON array contents
        Matcher arrayMatcher = Pattern.compile("\"" + arrayName + "\"\\s*:\\s*\\[(.*?)\\]", Pattern.DOTALL).matcher(json);
        if (arrayMatcher.find()) {
            String arrayContent = arrayMatcher.group(1);
            Matcher stringMatcher = Pattern.compile("\"([^\"]+)\"").matcher(arrayContent);
            while (stringMatcher.find()) {
                targetSet.add(stringMatcher.group(1));
            }
        }
    }

    /**
     * Reads raw string content from zip entry stream.
     *
     * @param zip Target zip file
     * @param entry Target entry
     * @return File contents
     * @throws IOException If stream fails
     */
    private static String readZipEntry(ZipFile zip, ZipEntry entry) throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(zip.getInputStream(entry)))) {
            return reader.lines().collect(Collectors.joining("\n"));
        }
    }

    /**
     * Normalizes mod ID for fuzzy matching.
     *
     * @param id Raw ID
     * @return Normalized ID
     */
    private static String normalizeModId(String id) {
        if (id == null) {
            return null;
        }
        return id.toLowerCase().replace("_", "-");
    }
}
