package client_mod_extractor_app;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
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

    private static final String CURRENT_VERSION = "1.0.8";

    private static final String CF_EXCLUDES_URL = configuredUrl(
            "cme.cf-excludes-url",
            "https://raw.githubusercontent.com/itzg/docker-minecraft-server/master/files/cf-exclude-include.json"
    );
    private static final String MODRINTH_EXCLUDES_URL = configuredUrl(
            "cme.modrinth-excludes-url",
            "https://raw.githubusercontent.com/itzg/docker-minecraft-server/master/files/modrinth-exclude-include.json"
    );
    private static final String CUSTOM_EXCLUDES_URL = configuredUrl(
            "cme.custom-excludes-url",
            "https://raw.githubusercontent.com/DawsonBodenhamer/Client-Mod-Extractor/main/custom-excludes.txt"
    );
    private static final String LATEST_VERSION_URL = configuredUrl(
            "cme.latest-version-url",
            "https://api.github.com/repos/DawsonBodenhamer/Client-Mod-Extractor/releases/latest"
    );
    private static final String PROJECT_RULES_FILE = "custom-excludes.txt";

    private static final Pattern FABRIC_ID_PATTERN = Pattern.compile(
            "\"id\"\\s*:\\s*[\"']([^\"']+)[\"']"
    );
    private static final Pattern FABRIC_ENVIRONMENT_PATTERN = Pattern.compile(
            "\"environment\"\\s*:\\s*[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE
    );
    private static final Pattern TOML_MODS_BLOCK_PATTERN = Pattern.compile(
            "(?ms)^\\s*\\[\\[mods\\]\\](.*?)(?=^\\s*\\[\\[|\\z)"
    );
    private static final Pattern TOML_MOD_ID_PATTERN = Pattern.compile(
            "modId\\s*=\\s*[\"']([^\"']+)[\"']"
    );
    private static final Pattern TOML_NON_CORE_BLOCK_PATTERN = Pattern.compile(
            "\\[\\[(dependencies|mixins)", Pattern.CASE_INSENSITIVE
    );
    private static final Pattern TOML_CLIENT_ONLY_PATTERN = Pattern.compile(
            "clientSideOnly\\s*=\\s*true"
                    + "|side\\s*=\\s*[\"']CLIENT[\"']"
                    + "|displayTest\\s*=\\s*[\"']IGNORE_ALL_VERSION[\"']",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern LATEST_VERSION_PATTERN = Pattern.compile(
            "\"tag_name\"\\s*:\\s*\"v?([^\"]+)\""
    );
    private static final Pattern GLOBAL_EXCLUDES_PATTERN = Pattern.compile(
            "\"globalExcludes\"\\s*:\\s*\\[(.*?)\\]", Pattern.DOTALL
    );
    private static final Pattern GLOBAL_FORCE_INCLUDES_PATTERN = Pattern.compile(
            "\"globalForceIncludes\"\\s*:\\s*\\[(.*?)\\]", Pattern.DOTALL
    );
    private static final Pattern JSON_STRING_PATTERN = Pattern.compile("\"([^\"]+)\"");

    private static final String ANSI_RESET  = "\u001B[0m";
    private static final String ANSI_RED    = "\u001B[38;2;255;74;74m";
    private static final String ANSI_GREEN  = "\u001B[38;2;0;230;118m";
    private static final String ANSI_YELLOW = "\u001B[38;2;255;215;0m";
    private static final String ANSI_CYAN   = "\u001B[38;2;0;191;255m";

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

            // --- 4. Load Classification Rules ---
            ClassificationRules rules = loadClassificationRules(sourceFolder);

            // --- 5. Process Local Archives ---
            File[] jarFiles = sourceFolder.toFile().listFiles(
                    (directory, name) -> name.toLowerCase(Locale.ROOT).endsWith(".jar")
            );
            if (jarFiles == null) {
                jarFiles = new File[0];
            }

            int processedCount = 0;
            int copiedCount = 0;

            System.out.println(ANSI_CYAN + "Location: " + sourceFolder);
            System.out.println("Scanning " + jarFiles.length + " files...\n" + ANSI_RESET);

            for (File jar : jarFiles) {
                ArchiveMetadata metadata;
                try {
                    metadata = inspectArchive(jar);
                } catch (Exception e) {
                    System.out.println(ANSI_YELLOW + "[Warning] Could not read contents of " + jar.getName() + ". Defaulting to keeping it." + ANSI_RESET);
                    metadata = ArchiveMetadata.unknown();
                }

                // --- Process Output ---
                Disposition disposition = classify(metadata, rules);
                printDisposition(jar, metadata.loaderType, disposition);
                if (disposition.shouldCopy) {
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
            System.out.println("Successfully checked online database and found " + ANSI_GREEN + rules.exclusionCount() + ANSI_RESET + " community-blacklisted mods.");
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
            String json = fetchUrl(LATEST_VERSION_URL);
            Matcher matcher = LATEST_VERSION_PATTERN.matcher(json);
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
     * Loads community and project-owned classification rules into one normalized model.
     *
     * @param sourceFolder Folder containing the optional local fallback rule file
     * @return Merged classification rules
     * @throws IOException If the local fallback exists but cannot be read
     */
    private static ClassificationRules loadClassificationRules(Path sourceFolder) throws IOException {
        ClassificationRules rules = new ClassificationRules();
        mergeCommunityRules(CF_EXCLUDES_URL, rules);
        mergeCommunityRules(MODRINTH_EXCLUDES_URL, rules);

        try {
            parseProjectRules(fetchUrl(CUSTOM_EXCLUDES_URL), rules);
        } catch (Exception e) {
            Path localRules = sourceFolder.resolve(PROJECT_RULES_FILE);
            if (Files.exists(localRules)) {
                parseProjectRules(String.join("\n", Files.readAllLines(localRules, StandardCharsets.UTF_8)), rules);
            }
        }
        return rules;
    }

    /**
     * Merges a community JSON endpoint when it is available.
     */
    private static void mergeCommunityRules(String url, ClassificationRules rules) {
        try {
            String json = fetchUrl(url);
            extractJsonArray(json, GLOBAL_EXCLUDES_PATTERN, rules.globalExcludes);
            extractJsonArray(json, GLOBAL_FORCE_INCLUDES_PATTERN, rules.forceIncludes);
        } catch (Exception e) {
            // Community endpoints are optional so the extractor remains usable offline.
        }
    }

    /**
     * Parses project rules. Bare IDs are global exclusions, +ID entries are force
     * includes, and loader:ID entries are loader-scoped exclusions.
     */
    private static void parseProjectRules(String content, ClassificationRules rules) {
        if (content == null) {
            return;
        }
        Arrays.stream(content.split("[,\\r\\n]+"))
                .map(String::trim)
                .filter(rule -> !rule.isEmpty() && !rule.startsWith("#"))
                .forEach(rule -> rules.addProjectRule(rule));
    }

    /**
     * Reads the supported loader metadata from one mod archive.
     */
    private static ArchiveMetadata inspectArchive(File jar) throws IOException {
        String loaderType = "Unknown/Generic";
        String modId = null;
        boolean clientOnly = false;

        try (ZipFile zip = new ZipFile(jar)) {
            ZipEntry fabricEntry = zip.getEntry("fabric.mod.json");
            if (fabricEntry != null) {
                loaderType = "Fabric";
                String json = readZipEntry(zip, fabricEntry);
                clientOnly = hasTopLevelClientEnvironment(json);

                Matcher idMatcher = FABRIC_ID_PATTERN.matcher(json);
                if (idMatcher.find()) {
                    modId = idMatcher.group(1);
                }
            }

            ZipEntry tomlEntry = zip.getEntry("META-INF/neoforge.mods.toml");
            if (tomlEntry == null) {
                tomlEntry = zip.getEntry("META-INF/mods.toml");
            }
            if (tomlEntry != null) {
                boolean neoForge = tomlEntry.getName().contains("neoforge");
                String tomlLoader = neoForge ? "NeoForge" : "Forge";
                loaderType = "Fabric".equals(loaderType) ? loaderType + "/" + tomlLoader : tomlLoader;

                String toml = readZipEntry(zip, tomlEntry);
                if (modId == null) {
                    modId = extractTomlModId(toml);
                }
                String coreToml = TOML_NON_CORE_BLOCK_PATTERN.split(toml, 2)[0];
                clientOnly = clientOnly || TOML_CLIENT_ONLY_PATTERN.matcher(coreToml).find();
            }
        }
        return new ArchiveMetadata(loaderType, normalizeModId(modId), clientOnly);
    }

    /**
     * Applies classification precedence in one place.
     */
    private static Disposition classify(ArchiveMetadata metadata, ClassificationRules rules) {
        if (metadata.clientOnly) {
            return Disposition.CLIENT_ONLY;
        }
        if (rules.isGloballyExcluded(metadata.modId) && !rules.isForceIncluded(metadata.modId)) {
            return Disposition.COMMUNITY_EXCLUDED;
        }
        if (rules.isLoaderExcluded(metadata.loaderType, metadata.modId)) {
            return Disposition.LOADER_EXCLUDED;
        }
        return rules.isForceIncluded(metadata.modId)
                ? Disposition.FORCE_INCLUDED
                : Disposition.SERVER_COMPATIBLE;
    }

    /**
     * Prints the classification result using the existing terminal layout.
     */
    private static void printDisposition(File jar, String loaderType, Disposition disposition) {
        String loaderTag = String.format("[%s]", loaderType);
        System.out.printf(
                disposition.color + "%-20s %-20s %s%s%n" + ANSI_RESET,
                loaderTag,
                disposition.label,
                disposition.outputPrefix,
                jar.getName() + disposition.reason
        );
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

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8)
        )) {
            return reader.lines().collect(Collectors.joining("\n"));
        }
    }

    /**
     * Extracts raw string elements from JSON array object.
     *
     * @param json Raw JSON payload
     * @param arrayPattern Pattern identifying the target array
     * @param targetSet Set to populate
     */
    private static void extractJsonArray(String json, Pattern arrayPattern, Set<String> targetSet) {
        if (json == null || arrayPattern == null || targetSet == null) {
            return;
        }
        Matcher arrayMatcher = arrayPattern.matcher(json);
        if (arrayMatcher.find()) {
            String arrayContent = arrayMatcher.group(1);
            Matcher stringMatcher = JSON_STRING_PATTERN.matcher(arrayContent);
            while (stringMatcher.find()) {
                String normalized = normalizeModId(stringMatcher.group(1));
                if (normalized != null && !normalized.isEmpty()) {
                    targetSet.add(normalized);
                }
            }
        }
    }

    /**
     * Checks the root Fabric environment without treating nested mixin or
     * entrypoint metadata as the mod's loader environment.
     *
     * @param json Raw JSON object
     * @return True when the root environment is client
     */
    private static boolean hasTopLevelClientEnvironment(String json) {
        Matcher fieldMatcher = FABRIC_ENVIRONMENT_PATTERN.matcher(json);
        while (fieldMatcher.find()) {
            if (jsonObjectDepthAt(json, fieldMatcher.start()) == 1
                    && "client".equalsIgnoreCase(fieldMatcher.group(1))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Extracts the mod ID from the authoritative NeoForge/Forge mods block.
     * Some metadata files place dependency blocks before [[mods]], so a first
     * generic modId match can otherwise return "neoforge" or "minecraft".
     *
     * @param toml Raw loader metadata
     * @return Mod ID, or null when no mods block contains one
     */
    private static String extractTomlModId(String toml) {
        Matcher modsBlockMatcher = TOML_MODS_BLOCK_PATTERN.matcher(toml);
        if (modsBlockMatcher.find()) {
            Matcher modIdMatcher = TOML_MOD_ID_PATTERN.matcher(modsBlockMatcher.group(1));
            if (modIdMatcher.find()) {
                return modIdMatcher.group(1);
            }
        }

        Matcher fallbackMatcher = TOML_MOD_ID_PATTERN.matcher(toml);
        return fallbackMatcher.find() ? fallbackMatcher.group(1) : null;
    }

    /**
     * Returns the JSON object nesting depth at a character offset.
     *
     * @param json Raw JSON object
     * @param offset Character offset
     * @return Object nesting depth
     */
    private static int jsonObjectDepthAt(String json, int offset) {
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = 0; i < offset; i++) {
            char current = json.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (current == '\\') {
                    escaped = true;
                } else if (current == '"') {
                    inString = false;
                }
            } else if (current == '"') {
                inString = true;
            } else if (current == '{') {
                depth++;
            } else if (current == '}') {
                depth--;
            }
        }
        return depth;
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
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(zip.getInputStream(entry), StandardCharsets.UTF_8)
        )) {
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
        return id.trim().toLowerCase(Locale.ROOT).replace("_", "-");
    }

    /**
     * Allows ignored regression tests to inject deterministic payload endpoints.
     *
     * @param propertyName System property used by a local test
     * @param defaultUrl Production endpoint
     * @return Configured endpoint, or the production endpoint when unavailable
     */
    private static String configuredUrl(String propertyName, String defaultUrl) {
        try {
            return System.getProperty(propertyName, defaultUrl);
        } catch (SecurityException e) {
            return defaultUrl;
        }
    }

    private static final class ClassificationRules {
        private final Set<String> globalExcludes = new HashSet<>();
        private final Set<String> forceIncludes = new HashSet<>();
        private final Map<String, Set<String>> loaderExcludes = new HashMap<>();

        private void addProjectRule(String rule) {
            if (rule.startsWith("+")) {
                addNormalized(forceIncludes, rule.substring(1));
                return;
            }

            int separator = rule.indexOf(':');
            if (separator > 0 && separator < rule.length() - 1) {
                String loader = rule.substring(0, separator).trim().toLowerCase(Locale.ROOT);
                String modId = normalizeModId(rule.substring(separator + 1));
                if (!loader.isEmpty() && modId != null && !modId.isEmpty()) {
                    loaderExcludes.computeIfAbsent(loader, ignored -> new HashSet<>()).add(modId);
                }
                return;
            }
            addNormalized(globalExcludes, rule);
        }

        private boolean isGloballyExcluded(String modId) {
            return modId != null && globalExcludes.contains(modId);
        }

        private boolean isForceIncluded(String modId) {
            return modId != null && forceIncludes.contains(modId);
        }

        private boolean isLoaderExcluded(String loaderType, String modId) {
            if (loaderType == null || modId == null) {
                return false;
            }
            String normalizedLoader = loaderType.toLowerCase(Locale.ROOT);
            return loaderExcludes.entrySet().stream()
                    .anyMatch(entry -> normalizedLoader.contains(entry.getKey())
                            && entry.getValue().contains(modId));
        }

        private int exclusionCount() {
            return globalExcludes.size()
                    + loaderExcludes.values().stream().mapToInt(Set::size).sum();
        }

        private static void addNormalized(Set<String> target, String value) {
            String normalized = normalizeModId(value);
            if (normalized != null && !normalized.isEmpty()) {
                target.add(normalized);
            }
        }
    }

    private static final class ArchiveMetadata {
        private final String loaderType;
        private final String modId;
        private final boolean clientOnly;

        private ArchiveMetadata(String loaderType, String modId, boolean clientOnly) {
            this.loaderType = loaderType;
            this.modId = modId;
            this.clientOnly = clientOnly;
        }

        private static ArchiveMetadata unknown() {
            return new ArchiveMetadata("Unknown/Generic", null, false);
        }
    }

    private enum Disposition {
        CLIENT_ONLY(false, ANSI_YELLOW,     "[CLIENT ONLY]", "Skipping: ", ""),
        COMMUNITY_EXCLUDED(false, ANSI_RED, "[SERVER CRASH RISK]", "Skipping: ", " (mislabeled)"),
        LOADER_EXCLUDED(false, ANSI_RED,    "[SERVER CRASH RISK]", "Skipping: ", " (loader-specific)"),
        FORCE_INCLUDED(true, ANSI_GREEN,    "[SERVER/BOTH]", "Copying:  ", " (force-included)"),
        SERVER_COMPATIBLE(true, ANSI_GREEN, "[SERVER/BOTH]", "Copying:  ", "");

        private final boolean shouldCopy;
        private final String color;
        private final String label;
        private final String outputPrefix;
        private final String reason;

        Disposition(boolean shouldCopy, String color, String label, String outputPrefix, String reason) {
            this.shouldCopy = shouldCopy;
            this.color = color;
            this.label = label;
            this.outputPrefix = outputPrefix;
            this.reason = reason;
        }
    }
}
