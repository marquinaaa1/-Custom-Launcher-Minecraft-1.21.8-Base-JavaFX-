package com.launcher;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.io.FileReader;
import java.io.FileNotFoundException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import netscape.javascript.JSObject;
import javafx.application.Platform;

public class MinecraftLauncher {
    private String MINECRAFT_VERSION;
    private List<String> ALLOWED_MODS;
    private List<String> JVM_ARGS;
    private List<String> NEEDED_NATIVES;
    
    private final File gameDir;
    private final File versionsDir;
    private final File librariesDir;
    private final File modsDir;

    public MinecraftLauncher() throws Exception {
        System.out.println("Java: MinecraftLauncher constructor called.");
        LauncherConfig.loadConfig();

        MINECRAFT_VERSION = LauncherConfig.getString("minecraft_version");
        ALLOWED_MODS = LauncherConfig.getStringList("allowed_mods");
        JVM_ARGS = LauncherConfig.getStringList("jvm_args");
        JVM_ARGS.add("-Doshi.util.platform.windows.perfCounter.enabled=false");
        NEEDED_NATIVES = LauncherConfig.getStringList(LauncherConfig.getNativeClassifierConfigKey());
        
        String gameDirPath = LauncherConfig.getString("game_dir");
        if (gameDirPath.startsWith("C:\\Users\\AppData")) {
            String userHome = System.getProperty("user.home");
            gameDirPath = userHome + "\\AppData\\Roaming\\.countermine";
        }
        this.gameDir = new File(gameDirPath);
        this.versionsDir = new File(gameDir, "versions");
        this.librariesDir = new File(gameDir, "libraries");
        this.modsDir = new File(gameDir, "mods");
        
        System.out.println("Java: Game directory: " + gameDir.getAbsolutePath());
        
        gameDir.mkdirs();
        versionsDir.mkdirs();
        librariesDir.mkdirs();
        modsDir.mkdirs();
    }

    public void launch(String username, JSBridge jsBridge) throws Exception {
        System.out.println("Java: MinecraftLauncher.launch() called.");
        System.out.println("=== Запуск Minecraft ===");
        System.out.println("Пользователь: " + username);
        System.out.println("Версия: " + MINECRAFT_VERSION);
        System.out.println("Путь к игре: " + gameDir.getAbsolutePath());

        File versionDir = new File(versionsDir, MINECRAFT_VERSION);
        File versionJson = new File(versionDir, MINECRAFT_VERSION + ".json");

        System.out.println("Java: Начинаем полную проверку и докачку файлов...");
        VersionDownloader downloader = new VersionDownloader(gameDir);
        downloader.setCallback(new VersionDownloader.DownloadCallback() {
            @Override
            public void onProgress(String message, int progress) {
                System.out.println("Java: [" + progress + "%] " + message);
                jsBridge.updateLoadingProgress(progress, message);
            }

            @Override
            public void onComplete() {
                System.out.println("Java: Полная проверка и докачка завершена!");
                jsBridge.updateLoadingProgress(60, "Проверка и загрузка завершены!");
            }

            @Override
            public void onError(String error) {
                System.err.println("Java: Ошибка при проверке/докачке: " + error);
                jsBridge.hideLoadingScreen();
                jsBridge.showError("Ошибка проверки/докачки: " + error);
            }
        });

        String effectiveVersionName;

        effectiveVersionName = MINECRAFT_VERSION;
        File vanillaVersionDir = new File(versionsDir, effectiveVersionName);
        File vanillaVersionJson = new File(vanillaVersionDir, effectiveVersionName + ".json");

        System.out.println("Java: Начинаем полную проверку и докачку файлов для базовой версии: " + MINECRAFT_VERSION);
        downloader.verifyAndDownloadAllFiles(MINECRAFT_VERSION);
        
        jsBridge.updateLoadingProgress(65, "Проверка и загрузка завершены! Проверка модов...");
        verifyMods(jsBridge);

        System.out.println("Java: Чтение version.json...");
        JsonObject versionData = JsonParser.parseReader(new FileReader(vanillaVersionJson)).getAsJsonObject();

        jsBridge.updateLoadingProgress(82, "Подготовка natives...");
        System.out.println("Java: Подготовка natives для версии: " + effectiveVersionName);
        
        downloader.prepareNativesForVersion(MINECRAFT_VERSION);
        
        File nativesDirToCheck = new File(vanillaVersionDir, "natives");
        String[] neededNatives = NEEDED_NATIVES.toArray(new String[0]);
        
        System.out.println("Java: Проверка и докачка отсутствующих natives...");
        boolean allNativesPresent = true;
        for (String lib : neededNatives) {
            if (downloader.findFileRecursively(nativesDirToCheck, lib) == null) {
                System.out.println("Java: Отсутствует native: " + lib + ". Попытка докачать...");
                jsBridge.updateLoadingProgress(86, "Докачка " + lib + "...");
                try {
                    downloader.downloadDllFromServer(lib, nativesDirToCheck);
                    if (downloader.findFileRecursively(nativesDirToCheck, lib) == null) {
                        System.err.println("Java: Не удалось докачать native: " + lib);
                        allNativesPresent = false;
                    }
                } catch (Exception e) {
                    System.err.println("Java: Ошибка при докачке native " + lib + ": " + e.getMessage());
                    e.printStackTrace();
                    allNativesPresent = false;
                }
            }
        }

        if (!allNativesPresent || !downloader.validateNativesExist(nativesDirToCheck, neededNatives)) {
            throw new Exception("Не удалось обеспечить наличие всех необходимых natives (DLL). Проверьте лог на ошибки.");
        }

        jsBridge.updateLoadingProgress(90, "Подготовка команды запуска...");
        System.out.println("Java: Подготовка команды запуска...");
        List<String> command = buildLaunchCommand(username, versionData, vanillaVersionDir);

        System.out.println("\n=== Команда запуска ===");
        for (String arg : command) {
            System.out.println(arg);
        }

        System.out.println("\n=== Запуск процесса ===");
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(gameDir);

        Process process = pb.start();
        new Thread(() -> {
            try (java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    System.out.println("Minecraft Output: " + line);
                }
            } catch (java.io.IOException e) {
                System.err.println("Error reading Minecraft output: " + e.getMessage());
            }
        }).start();

        new Thread(() -> {
            try (java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(process.getErrorStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    System.err.println("Minecraft Error: " + line);
                }
            } catch (java.io.IOException e) {
                System.err.println("Error reading Minecraft error output: " + e.getMessage());
            }
        }).start();

        System.out.println("Java: Minecraft запущен! PID: " + process.pid());
        jsBridge.updateLoadingProgress(100, "Игра запущена!");
        jsBridge.hideLoadingScreen();

        new Thread(() -> {
            try {
                int exitCode = process.waitFor();
                System.out.println("Java: Процесс Minecraft завершился с кодом: " + exitCode);
            } catch (InterruptedException e) {
                System.err.println("Java: Ошибка ожидания завершения процесса Minecraft: " + e.getMessage());
                Thread.currentThread().interrupt();
            }
        }).start();
    }

    private List<String> buildLaunchCommand(String username, JsonObject versionData, File versionDir) throws Exception {
        List<String> command = new ArrayList<>();
        
        String javaPath = System.getProperty("java.home") + File.separator + "bin" + File.separator + "java.exe";
        command.add(javaPath);
        
        for (String arg : JVM_ARGS) {
            command.add(arg);
        }
        command.add("-Dorg.lwjgl.util.Debug=true");
        command.add("-Dorg.lwjgl.util.DebugLoader=true");
        
        File nativesDir = new File(versionDir, "natives");
        if (nativesDir.exists()) {
            List<String> nativePaths = getNativeLibraryPathsRecursively(nativesDir);
            if (!nativePaths.isEmpty()) {
                command.add("-Dorg.lwjgl.librarypath=" + String.join(File.pathSeparator, nativePaths));
            }
        }
        
        command.add("-cp");
        command.add(buildClasspath(versionData, versionDir));
        
        String mainClass = versionData.get("mainClass").getAsString();
        command.add(mainClass);
        
        JsonObject arguments = versionData.getAsJsonObject("arguments");
        if (arguments != null && arguments.has("game")) {
            JsonArray gameArgs = arguments.getAsJsonArray("game");
            for (JsonElement arg : gameArgs) {
                if (arg.isJsonPrimitive()) {
                    String argStr = arg.getAsString()
                        .replace("${auth_player_name}", username)
                        .replace("${version_name}", MINECRAFT_VERSION)
                        .replace("${game_directory}", gameDir.getAbsolutePath())
                        .replace("${assets_root}", new File(gameDir, "assets").getAbsolutePath())
                        .replace("${assets_index_name}", getAssetsIndex(versionData))
                        .replace("${auth_uuid}", "00000000-0000-0000-0000-000000000000")
                        .replace("${auth_access_token}", "0")
                        .replace("${clientid}", "0")
                        .replace("${auth_xuid}", "0")
                        .replace("${user_type}", "legacy")
                        .replace("${version_type}", "release");
                    command.add(argStr);
                }
            }
        } else {
            
            command.add("--username");
            command.add(username);
            command.add("--version");
            command.add(MINECRAFT_VERSION);
            command.add("--gameDir");
            command.add(gameDir.getAbsolutePath());
            command.add("--assetsDir");
            command.add(new File(gameDir, "assets").getAbsolutePath());
            command.add("--assetIndex");
            command.add(getAssetsIndex(versionData));
            command.add("--uuid");
            command.add("00000000-0000-0000-0000-000000000000");
            command.add("--accessToken");
            command.add("0");
            command.add("--userType");
            command.add("legacy");
        }
        
        return command;
    }

    private String buildClasspath(JsonObject versionData, File versionDir) throws Exception {
        List<String> classpathEntries = new ArrayList<>();
        java.util.Map<String, String> artifactToJar = new java.util.HashMap<>();
        java.util.Map<String, String> artifactToVersion = new java.util.HashMap<>();
        java.util.function.Function<String[], String> getArtifactVers = parts -> (parts.length >= 3 ? parts[1] + ":" + parts[2] : null);
        if (versionData.has("libraries")) {
            JsonArray libraries = versionData.getAsJsonArray("libraries");
            for (JsonElement libElement : libraries) {
                JsonObject lib = libElement.getAsJsonObject();
                if (lib.has("rules") && !checkRules(lib.getAsJsonArray("rules"))) continue;
                String libPath = null;
                if (lib.has("downloads")) {
                    JsonObject downloads = lib.getAsJsonObject("downloads");
                    if (downloads.has("artifact")) {
                        JsonObject artifact = downloads.getAsJsonObject("artifact");
                        libPath = artifact.get("path").getAsString();
                    }
                } else if (lib.has("name")) {
                    String name = lib.get("name").getAsString();
                    String[] parts = name.split(":");
                    if (parts.length == 3) {
                        String group = parts[0].replace('.', File.separatorChar);
                        String artifact = parts[1];
                        String version = parts[2];
                        libPath = group + File.separator + artifact + File.separator + version + File.separator + artifact + "-" + version + ".jar";
                    }
                }
                if (libPath != null) {
                    File libFile = new File(librariesDir, libPath);
                    if (libFile.exists()) {
                        String[] parts = libPath.replace("\\", "/").split("/");
                        if (parts.length >= 4) {
                            String artifactId = parts[parts.length - 3];
                            String version = parts[parts.length - 2];
                            if (!artifactToJar.containsKey(artifactId) || versionCompare(version, artifactToVersion.get(artifactId)) > 0) {
                                artifactToJar.put(artifactId, libFile.getAbsolutePath());
                                artifactToVersion.put(artifactId, version);
                            }
                        }
                    }
                }
            }
        }
        File fabricLoaderJar = new File(versionDir, versionData.get("id").getAsString() + ".jar");
        if (fabricLoaderJar.exists()) {
            String artifactId = fabricLoaderJar.getName();
            artifactToJar.put("fabric-loader-jar", fabricLoaderJar.getAbsolutePath());
        }
        File baseVersionDir = new File(versionsDir, MINECRAFT_VERSION);
        File clientJar = new File(baseVersionDir, MINECRAFT_VERSION + ".jar");
        if (clientJar.exists()) {
            artifactToJar.put("minecraft-client-jar", clientJar.getAbsolutePath());
        }
        File vanillaDir = new File(versionsDir, MINECRAFT_VERSION);
        File vanillaJson = new File(vanillaDir, MINECRAFT_VERSION + ".json");
        if (vanillaJson.exists()) {
            JsonObject vanillaVer = JsonParser.parseReader(new FileReader(vanillaJson)).getAsJsonObject();
            if (vanillaVer.has("libraries")) {
                JsonArray vlibs = vanillaVer.getAsJsonArray("libraries");
                for (JsonElement libElement : vlibs) {
                    JsonObject lib = libElement.getAsJsonObject();
                    String libPath = null;
                    if (lib.has("downloads")) {
                        JsonObject downloads = lib.getAsJsonObject("downloads");
                        if (downloads.has("artifact")) {
                            JsonObject artifact = downloads.getAsJsonObject("artifact");
                            libPath = artifact.get("path").getAsString();
                        }
                    } else if (lib.has("name")) {
                        String name = lib.get("name").getAsString();
                        String[] parts = name.split(":");
                        if (parts.length == 3) {
                            String group = parts[0].replace('.', File.separatorChar);
                            String artifact = parts[1];
                            String version = parts[2];
                            libPath = group + File.separator + artifact + File.separator + version + File.separator + artifact + "-" + version + ".jar";
                        }
                    }
                    if (libPath != null) {
                        File libFile = new File(librariesDir, libPath);
                        if (libFile.exists()) {
                            String[] parts = libPath.replace("\\", "/").split("/");
                            if (parts.length >= 4) {
                                String artifactId = parts[parts.length - 3];
                                String version = parts[parts.length - 2];
                                if (!artifactToJar.containsKey(artifactId) || versionCompare(version, artifactToVersion.get(artifactId)) > 0) {
                                    artifactToJar.put(artifactId, libFile.getAbsolutePath());
                                    artifactToVersion.put(artifactId, version);
                                    System.out.println("[FIX] Добавлена в classpath ванильная библиотека: " + libFile.getAbsolutePath());
                                }
                            }
                        }
                    }
                }
            }
        } else {
            System.out.println("[WARN] Не найден vanilla version.json для полной проверки зависимостей.");
        }
        classpathEntries.addAll(artifactToJar.values());
        return String.join(File.pathSeparator, classpathEntries);
    }

    private boolean checkRules(JsonArray rules) {
        String os = getOSName();
        for (JsonElement ruleElement : rules) {
            JsonObject rule = ruleElement.getAsJsonObject();
            String action = rule.get("action").getAsString();
            
            if (rule.has("os")) {
                JsonObject osRule = rule.getAsJsonObject("os");
                if (osRule.has("name")) {
                    String osName = osRule.get("name").getAsString();
                    if (osName.equals(os)) {
                        return action.equals("allow");
                    }
                }
            } else {
                return action.equals("allow");
            }
        }
        return true;
    }

    private String getOSName() {
        String os = System.getProperty("os.name").toLowerCase();
        if (os.contains("win")) return "windows";
        if (os.contains("mac")) return "osx";
        return "linux";
    }

    private String getAssetsIndex(JsonObject versionData) {
        if (versionData.has("assetIndex")) {
            return versionData.getAsJsonObject("assetIndex").get("id").getAsString();
        }
        return MINECRAFT_VERSION;
    }
    
    private void downloadVersion(JSBridge jsBridge) throws Exception {
        System.out.println("Java: downloadVersion() called.");
        VersionDownloader downloader = new VersionDownloader(gameDir);

        downloader.setCallback(new VersionDownloader.DownloadCallback() {
            @Override
            public void onProgress(String message, int progress) {
                System.out.println("Java: [" + progress + "%] " + message);
                jsBridge.updateLoadingProgress(progress, message);
            }

            @Override
            public void onComplete() {
                System.out.println("Java: Загрузка завершена!");
                jsBridge.updateLoadingProgress(60, "Загрузка завершена!");
            }

            @Override
            public void onError(String error) {
                System.err.println("Java: Ошибка загрузки: " + error);
                jsBridge.hideLoadingScreen();
                jsBridge.showError("Ошибка загрузки: " + error);
            }
        });

        downloader.downloadVersion(MINECRAFT_VERSION);
    }

    private String listAvailableVersions() {
        if (!versionsDir.exists()) {
            return "(папка versions не существует)";
        }
        
        File[] versions = versionsDir.listFiles(File::isDirectory);
        if (versions == null || versions.length == 0) {
            return "(нет установленных версий)";
        }
        
        StringBuilder sb = new StringBuilder();
        for (File v : versions) {
            sb.append("  - ").append(v.getName()).append("\n");
        }
        return sb.toString();
    }

    private String getFabricVersionName(String minecraftVersion) throws Exception {
        File versionsFolder = new File(gameDir, "versions");
        if (!versionsFolder.exists() || !versionsFolder.isDirectory()) {
            System.out.println("Java: Папка версий не найдена: " + versionsFolder.getAbsolutePath());
            return null;
        }

        File[] versionDirs = versionsFolder.listFiles(File::isDirectory);
        if (versionDirs != null) {
            for (File versionDir : versionDirs) {
                String versionName = versionDir.getName();
                if (versionName.contains("fabric") && versionName.contains(minecraftVersion)) {
                    File fabricJson = new File(versionDir, versionName + ".json");
                    if (fabricJson.exists()) {
                        System.out.println("Java: Найден Fabric профиль: " + versionName);
                        return versionName;
                    }
                }
            }
        }
        System.out.println("Java: Fabric профиль для версии " + minecraftVersion + " не найден в " + versionsFolder.getAbsolutePath() + ".");
        return null;
    }

    private void verifyMods(JSBridge jsBridge) {
        jsBridge.updateLoadingProgress(70, "Проверка папки модов...");
        System.out.println("Java: Проверка папки модов: " + modsDir.getAbsolutePath());

        File[] modFiles = modsDir.listFiles();
        if (modFiles != null) {
            for (File modFile : modFiles) {
                if (modFile.isFile()) {
                    if (!ALLOWED_MODS.contains(modFile.getName())) {
                        System.out.println("Java: Удаление неразрешенного мода: " + modFile.getName());
                        jsBridge.updateLoadingProgress(75, "Удаление неразрешенного мода: " + modFile.getName());
                        modFile.delete();
                    } else {
                        System.out.println("Java: Обнаружен разрешенный мод: " + modFile.getName());
                    }
                }
            }
        }
        jsBridge.updateLoadingProgress(80, "Проверка модов завершена.");
        System.out.println("Java: Проверка модов завершена.");
    }

    private int versionCompare(String v1, String v2) {
        if (v2 == null) return 1;
        String[] a = v1.split("[.\\-]");
        String[] b = v2.split("[.\\-]");
        int len = Math.max(a.length, b.length);
        for (int i = 0; i < len; i++) {
            int ai = (i < a.length) ? toIntSafe(a[i]) : 0;
            int bi = (i < b.length) ? toIntSafe(b[i]) : 0;
            if (ai != bi) return Integer.compare(ai, bi);
        }
        return 0;
    }
    private int toIntSafe(String s) { try { return Integer.parseInt(s.replaceAll("\\D", "")); } catch(Exception e) { return 0; }}
    
    private void copyDirectory(File source, File destination) throws Exception {
        if (!source.exists() || !source.isDirectory()) {
            return;
        }
        destination.mkdirs();
        File[] files = source.listFiles();
        if (files != null) {
            for (File file : files) {
                File destFile = new File(destination, file.getName());
                if (file.isDirectory()) {
                    copyDirectory(file, destFile);
                } else {
                    Files.copy(file.toPath(), destFile.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    public List<String> getNativeLibraryPathsRecursively(File baseDir) {
        List<String> nativePaths = new ArrayList<>();
        if (baseDir.exists() && baseDir.isDirectory()) {
            nativePaths.add(baseDir.getAbsolutePath());
            File[] files = baseDir.listFiles();
            if (files != null) {
                for (File file : files) {
                    if (file.isDirectory()) {
                        nativePaths.addAll(getNativeLibraryPathsRecursively(file));
                    }
                }
            }
        }
        return nativePaths;
    }
}
