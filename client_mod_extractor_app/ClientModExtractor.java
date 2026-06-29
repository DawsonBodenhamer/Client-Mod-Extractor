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
import java.util.Scanner;
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

    private static final String CF_EXCLUDES_URL = "https://raw.githubusercontent.com/itzg/docker-minecraft-server/master/files/cf-exclude-include.json";
    private static final String MODRINTH_EXCLUDES_URL = "https://raw.githubusercontent.com/itzg/docker-minecraft-server/master/files/modrinth-exclude-include.json";
    private static final String CUSTOM_EXCLUDES_URL = "https://raw.githubusercontent.com/DawsonBodenhamer/Client-Mod-Extractor/main/custom-excludes.txt";

    /* ──────────────────────────────────────────────────────────────────────────────
     *        Public Execution
     * ────────────────────────────────────────────────────────────────────────────*/

    /**
     * Main entry point for application execution.
     *
     * @param args Command line arguments
     */
    public static void main(String[] args) {

        // --- 1. Parse Arguments ---
        // Check for interactive prompt argument
        boolean promptAffirmation = Arrays.asList(args).contains("--prompt-affirmation");
        if (promptAffirmation) {
            Scanner scanner = new Scanner(System.in);
            System.out.println();

            while (true) {
                System.out.println("Are you a unique and valuable human? [Type Y or N and press Enter]");
                System.out.print("> ");
                String answer = scanner.nextLine().trim().toLowerCase();

                if (answer.equals("y") || answer.equals("yes")) {
                    System.out.println();
                    System.out.println("That's a great start. Hold CTRL and click https://www.bible.com/bible/8/JHN.3.16.AMPC if you need a friend.");
                    System.out.println();

                    // Pause 2 seconds to allow user to read message
                    try {
                        Thread.sleep(2000);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    break;
                } else {
                    System.out.println();
                    System.out.println("Wrong answer. Every human is unique and valuable in their own way. Try again.");
                    System.out.println();
                }
            }
        }

        // --- 2. Build Target Directory ---
        Path sourceFolder = Paths.get("").toAbsolutePath();
        Path targetFolder = sourceFolder.resolve("Save_For_Server_Mods");

        try {
            if (!Files.exists(targetFolder)) {
                Files.createDirectories(targetFolder);
            }

            // --- 3. Fetch Remote Exclusions ---
            Set<String> excludeSet = new HashSet<>();
            System.out.println("Fetching remote exclusion lists...");

            // Fetch CurseForge exclusions
            try {
                String cfJson = fetchUrl(CF_EXCLUDES_URL);
                extractJsonArray(cfJson, "globalExcludes", excludeSet);
            } catch (Exception e) {
                System.out.println("[Warning] Failed to fetch CurseForge excludes. Offline mode? " + e.getMessage());
            }

            // Fetch Modrinth exclusions
            try {
                String modrinthJson = fetchUrl(MODRINTH_EXCLUDES_URL);
                extractJsonArray(modrinthJson, "globalExcludes", excludeSet);
            } catch (Exception e) {
                System.out.println("[Warning] Failed to fetch Modrinth excludes. " + e.getMessage());
            }

            // Fetch my exclusions
            try {
                String customList = fetchUrl(CUSTOM_EXCLUDES_URL);
                Arrays.stream(customList.split("\n"))
                        .map(String::trim)
                        .filter(s -> !s.isEmpty())
                        .forEach(excludeSet::add);
            } catch (Exception e) {
                System.out.println("[Warning] Failed to fetch custom excludes. Falling back to local file. " + e.getMessage());
                Path localCustom = sourceFolder.resolve("custom-excludes.txt");
                if (Files.exists(localCustom)) {
                    Files.readAllLines(localCustom).stream()
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

            System.out.println("Loaded " + normalizedExcludes.size() + " exclusion rules from remote lists.\n");

            // --- 4. Process Local Archives ---
            File[] jarFiles = sourceFolder.toFile().listFiles((d, name) -> name.toLowerCase().endsWith(".jar"));
            if (jarFiles == null) {
                jarFiles = new File[0];
            }

            int processedCount = 0;
            int copiedCount = 0;

            System.out.println("Location: " + sourceFolder);
            System.out.println("Scrubbing through " + jarFiles.length + " jar files...\n");

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
                                Pattern.compile("displayTest\\s*=\\s*[\"']IGNORE_SERVER_VERSION[\"']", Pattern.CASE_INSENSITIVE).matcher(coreToml).find() ||
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
                    System.out.println("[Warning] Could not read contents of " + jar.getName() + ". Defaulting to keeping it.");
                }

                // --- Process Output and Log ---
                String loaderTag = String.format("[%s]", loaderType);

                if (extractedModId != null && normalizedExcludes.contains(normalizeModId(extractedModId))) {
                    System.out.printf("%-18s %-18s Skipping: %s (Mod ID: %s)%n", loaderTag, "[PROBLEMATIC MOD]", jar.getName(), extractedModId);
                } else if (isClientOnly) {
                    System.out.printf("%-18s %-18s Skipping: %s%n", loaderTag, "[CLIENT ONLY]", jar.getName());
                } else {
                    System.out.printf("%-18s %-18s Copying:  %s%n", loaderTag, "[SERVER/BOTH]", jar.getName());
                    Files.copy(jar.toPath(), targetFolder.resolve(jar.getName()), StandardCopyOption.REPLACE_EXISTING);
                    copiedCount++;
                }

                processedCount++;
            }

            System.out.println("\n==========================================================");
            System.out.println("Done! Processed " + processedCount + " mods.");
            System.out.println("Copied " + copiedCount + " server-safe mods to: ./Save_For_Server_Mods");
            System.out.println("==========================================================");

        } catch (Exception e) {
            e.printStackTrace();
            System.exit(1);
        }
    }

    /* ──────────────────────────────────────────────────────────────────────────────
     *        Private Helpers
     * ────────────────────────────────────────────────────────────────────────────*/

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