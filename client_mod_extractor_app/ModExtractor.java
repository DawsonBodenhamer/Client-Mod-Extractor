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

public class ModExtractor {
    private static final String CF_EXCLUDES_URL = "https://raw.githubusercontent.com/itzg/docker-minecraft-server/master/files/cf-exclude-include.json";
    private static final String MODRINTH_EXCLUDES_URL = "https://raw.githubusercontent.com/itzg/docker-minecraft-server/master/files/modrinth-exclude-include.json";
    private static final String CUSTOM_EXCLUDES_URL = "https://raw.githubusercontent.com/DawsonBodenhamer/Client-Mod-Extractor/main/custom-excludes.txt";

    public static void main(String[] args) {
        Path sourceFolder = Paths.get("").toAbsolutePath();
        Path targetFolder = sourceFolder.resolve("ServerMods");

        try {
            if (!Files.exists(targetFolder)) {
                Files.createDirectories(targetFolder);
            }

            Set<String> excludeSet = new HashSet<>();
            System.out.println("Fetching remote exclusion lists...");
            
            // Fetch itzg's CurseForge exclusions
            try {
                String cfJson = fetchUrl(CF_EXCLUDES_URL);
                extractJsonArray(cfJson, "globalExcludes", excludeSet);
            } catch (Exception e) {
                System.out.println("[Warning] Failed to fetch CurseForge excludes. Offline mode? " + e.getMessage());
            }

            // Fetch itzg's Modrinth exclusions
            try {
                String modrinthJson = fetchUrl(MODRINTH_EXCLUDES_URL);
                extractJsonArray(modrinthJson, "globalExcludes", excludeSet);
            } catch (Exception e) {
                System.out.println("[Warning] Failed to fetch Modrinth excludes. " + e.getMessage());
            }

            // Fetch Custom exclusions
            try {
                String customList = fetchUrl(CUSTOM_EXCLUDES_URL);
                Arrays.stream(customList.split("\n"))
                      .map(String::trim)
                      .filter(s -> !s.isEmpty())
                      .forEach(excludeSet::add);
            } catch (Exception e) {
                System.out.println("[Warning] Failed to fetch Custom excludes from GitHub. Falling back to local 'custom-excludes.txt' if it exists. " + e.getMessage());
                Path localCustom = sourceFolder.resolve("custom-excludes.txt");
                if (Files.exists(localCustom)) {
                    Files.readAllLines(localCustom).stream()
                         .map(String::trim)
                         .filter(s -> !s.isEmpty())
                         .forEach(excludeSet::add);
                }
            }

            // Normalize excludes to lower case for case-insensitive matching
            Set<String> normalizedExcludes = excludeSet.stream().map(String::toLowerCase).collect(Collectors.toSet());
            System.out.println("Loaded " + normalizedExcludes.size() + " exclusion rules from remote lists.");
            System.out.println();

            File[] jarFiles = sourceFolder.toFile().listFiles((d, name) -> name.toLowerCase().endsWith(".jar"));
            if (jarFiles == null) jarFiles = new File[0];

            int processedCount = 0;
            int copiedCount = 0;

            System.out.println("Location: " + sourceFolder);
            System.out.println("Scrubbing through " + jarFiles.length + " jar files...");
            System.out.println();

            for (File jar : jarFiles) {
                boolean isClientOnly = false;
                String loaderType = "Unknown/Generic";
                String extractedModId = null;

                try (ZipFile zip = new ZipFile(jar)) {
                    // --- 1. Check Fabric ---
                    ZipEntry fabricEntry = zip.getEntry("fabric.mod.json");
                    if (fabricEntry != null) {
                        loaderType = "Fabric";
                        String jsonContent = readZipEntry(zip, fabricEntry);
                        
                        // Check environment
                        Matcher envMatcher = Pattern.compile("\"environment\"\\s*:\\s*[\"']client[\"']", Pattern.CASE_INSENSITIVE).matcher(jsonContent);
                        if (envMatcher.find()) {
                            isClientOnly = true;
                        }

                        // Extract mod ID to check against exclude lists
                        Matcher idMatcher = Pattern.compile("\"id\"\\s*:\\s*[\"']([^\"']+)[\"']").matcher(jsonContent);
                        if (idMatcher.find()) {
                            extractedModId = idMatcher.group(1);
                        }
                    }

                    // --- 2. Check NeoForge and Forge ---
                    ZipEntry tomlEntry = zip.getEntry("META-INF/neoforge.mods.toml");
                    if (tomlEntry == null) tomlEntry = zip.getEntry("META-INF/mods.toml");
                    
                    if (tomlEntry != null) {
                        if ("Fabric".equals(loaderType)) {
                            loaderType = tomlEntry.getName().contains("neoforge") ? "Fabric/NeoForge" : "Fabric/Forge";
                        } else {
                            loaderType = tomlEntry.getName().contains("neoforge") ? "NeoForge" : "Forge";
                        }
                        
                        String tomlContent = readZipEntry(zip, tomlEntry);

                        // Extract mod ID if not already found
                        Matcher idMatcher = Pattern.compile("modId\\s*=\\s*[\"']([^\"']+)[\"']").matcher(tomlContent);
                        if (extractedModId == null && idMatcher.find()) {
                            extractedModId = idMatcher.group(1);
                        }

                        // Scan TOML up to first dependency block to avoid false positives
                        String coreToml = tomlContent.split("\\[\\[(dependencies|mixins)")[0];

                        if (Pattern.compile("clientSideOnly\\s*=\\s*true", Pattern.CASE_INSENSITIVE).matcher(coreToml).find() ||
                            Pattern.compile("side\\s*=\\s*[\"']CLIENT[\"']", Pattern.CASE_INSENSITIVE).matcher(coreToml).find() ||
                            Pattern.compile("displayTest\\s*=\\s*[\"']IGNORE_SERVER_VERSION[\"']", Pattern.CASE_INSENSITIVE).matcher(coreToml).find()) {
                            isClientOnly = true;
                        }
                    }

                } catch (Exception e) {
                    System.out.println("[Warning] Could not read contents of " + jar.getName() + ". Defaulting to keeping it.");
                }

                if (extractedModId != null && normalizedExcludes.contains(extractedModId.toLowerCase())) {
                    System.out.println("[" + loaderType + "] [PROBLEMATIC MOD] Skipping: " + jar.getName() + " (Mod ID: " + extractedModId + ")");
                } else if (isClientOnly) {
                    System.out.println("[" + loaderType + "] [CLIENT ONLY]   Skipping: " + jar.getName());
                } else {
                    System.out.println("[" + loaderType + "] [SERVER/BOTH]   Copying: " + jar.getName());
                    Files.copy(jar.toPath(), targetFolder.resolve(jar.getName()), StandardCopyOption.REPLACE_EXISTING);
                    copiedCount++;
                }
                processedCount++;
            }

            System.out.println();
            System.out.println("==========================================================");
            System.out.println("Done! Processed " + processedCount + " mods.");
            System.out.println("Copied " + copiedCount + " server-safe mods to: ./ServerMods");
            System.out.println("==========================================================");

        } catch (Exception e) {
            e.printStackTrace();
            System.exit(1);
        }
    }

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

    private static void extractJsonArray(String json, String arrayName, Set<String> targetSet) {
        // Regex to extract JSON array contents
        Matcher arrayMatcher = Pattern.compile("\"" + arrayName + "\"\\s*:\\s*\\[(.*?)\\]", Pattern.DOTALL).matcher(json);
        if (arrayMatcher.find()) {
            String arrayContent = arrayMatcher.group(1);
            Matcher stringMatcher = Pattern.compile("\"([^\"]+)\"").matcher(arrayContent);
            while (stringMatcher.find()) {
                targetSet.add(stringMatcher.group(1));
            }
        }
    }

    private static String readZipEntry(ZipFile zip, ZipEntry entry) throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(zip.getInputStream(entry)))) {
            return reader.lines().collect(Collectors.joining("\n"));
        }
    }
}
