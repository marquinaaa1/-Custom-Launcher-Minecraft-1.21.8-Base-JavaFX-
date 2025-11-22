package com.launcher;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.Gson;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.security.cert.X509Certificate;
import java.security.SecureRandom;

public class VersionDownloader {
    private static String VERSION_MANIFEST_URL;
    private final File gameDir;
    private DownloadCallback callback;

    public interface DownloadCallback {
        void onProgress(String message, int progress);
        void onComplete();
        void onError(String error);
    }

    public VersionDownloader(File gameDir) {
        this.gameDir = gameDir;
        VERSION_MANIFEST_URL = LauncherConfig.getString("version_manifest_url");
        try {
            TrustManager[] trustAllCerts = new TrustManager[]{
                new X509TrustManager() {
                    public X509Certificate[] getAcceptedIssuers() { return null; }
                    public void checkClientTrusted(X509Certificate[] certs, String authType) { }
                    public void checkServerTrusted(X509Certificate[] certs, String authType) { }
                }
            };
            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, trustAllCerts, new SecureRandom());
            HttpsURLConnection.setDefaultSSLSocketFactory(sslContext.getSocketFactory());
            HttpsURLConnection.setDefaultHostnameVerifier(new HostnameVerifier() {
                public boolean verify(String hostname, SSLSession session) {
                    return true;
                }
            });
        } catch (Exception e) {
            System.err.println("Ошибка при инициализации SSLContext для обхода проверки: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public void setCallback(DownloadCallback callback) {
        this.callback = callback;
    }

    private void updateProgress(String message, int progress) {
        System.out.println("[" + progress + "%] " + message);
        if (callback != null) {
            callback.onProgress(message, progress);
        }
    }

    public void verifyAndDownloadAllFiles(String version) throws Exception {
        updateProgress("Начало проверки и загрузки файлов...", 0);

        updateProgress("Получение манифеста версий...", 5);
        String manifestJson = downloadString(VERSION_MANIFEST_URL);
        JsonObject manifest = new Gson().fromJson(manifestJson, JsonObject.class);
        JsonArray versions = manifest.getAsJsonArray("versions");

        String versionUrl = null;
        for (JsonElement versionElement : versions) {
            JsonObject versionObj = versionElement.getAsJsonObject();
            if (versionObj.get("id").getAsString().equals(version)) {
                versionUrl = versionObj.get("url").getAsString();
                break;
            }
        }

        if (versionUrl == null) {
            throw new Exception("Версия " + version + " не найдена!");
        }

        updateProgress("Загрузка информации о версии...", 10);
        String versionJson = downloadString(versionUrl);
        JsonObject versionData = new Gson().fromJson(versionJson, JsonObject.class);

        File versionDir = new File(gameDir, "versions" + File.separator + version);
        File librariesDir = new File(gameDir, "libraries");
        File assetsDir = new File(gameDir, "assets");
        File nativesDir = new File(versionDir, "natives");

        versionDir.mkdirs();
        librariesDir.mkdirs();
        assetsDir.mkdirs();
        nativesDir.mkdirs();

        File versionJsonFile = new File(versionDir, version + ".json");
        if (!versionJsonFile.exists() || !Files.readString(versionJsonFile.toPath()).equals(versionJson)) {
            updateProgress("Сохранение version.json...", 15);
            versionJsonFile.getParentFile().mkdirs();
            System.out.println("[DEBUG] Создана директория для версии: " + versionJsonFile.getParentFile().getAbsolutePath() + ", существует: " + versionJsonFile.getParentFile().exists());
            try {
                Files.writeString(versionJsonFile.toPath(), versionJson);
                System.out.println("[DEBUG] version.json успешно сохранен: " + versionJsonFile.getAbsolutePath());
            } catch (IOException e) {
                System.err.println("[ОШИБКА ЗАПИСИ ФАЙЛА] Не удалось записать файл: " + versionJsonFile.getAbsolutePath());
                e.printStackTrace();
                throw e;
            }
        }

        updateProgress("Проверка и загрузка клиента...", 20);
        JsonObject downloads = versionData.getAsJsonObject("downloads");
        JsonObject client = downloads.getAsJsonObject("client");
        String clientUrl = client.get("url").getAsString();
        File clientJar = new File(versionDir, version + ".jar");
        if (!clientJar.exists()) {
            downloadFile(clientUrl, clientJar);
        }

        updateProgress("Проверка и загрузка библиотек...", 30);
        JsonArray libraries = versionData.getAsJsonArray("libraries");
        int totalLibs = libraries.size();
        int currentLib = 0;

        for (JsonElement libElement : libraries) {
            JsonObject lib = libElement.getAsJsonObject();
            currentLib++;
            int progress = 30 + (currentLib * 30 / totalLibs);

            if (lib.has("rules") && !checkRules(lib.getAsJsonArray("rules"))) {
                continue;
            }

            if (lib.has("downloads")) {
                JsonObject libDownloads = lib.getAsJsonObject("downloads");

                if (libDownloads.has("artifact")) {
                    JsonObject artifact = libDownloads.getAsJsonObject("artifact");
                    String path = artifact.get("path").getAsString();
                    String url = artifact.get("url").getAsString();
                    File libFile = new File(librariesDir, path);

                    if (!libFile.exists()) {
                        libFile.getParentFile().mkdirs();
                        updateProgress("Библиотека: " + libFile.getName(), progress);
                        downloadFile(url, libFile);
                    }
                }

                if (libDownloads.has("classifiers")) {
                    JsonObject classifiers = libDownloads.getAsJsonObject("classifiers");
                    String nativeKey = getNativeClassifier();

                    if (classifiers.has(nativeKey)) {
                        JsonObject nativeArtifact = classifiers.getAsJsonObject(nativeKey);
                        String path = nativeArtifact.get("path").getAsString();
                        String url = nativeArtifact.get("url").getAsString();
                        
                        File nativeJarFile = new File(librariesDir, path);
                        if (!nativeJarFile.exists()) {
                            nativeJarFile.getParentFile().mkdirs();
                            updateProgress("Native JAR: " + nativeJarFile.getName(), progress);
                            downloadFile(url, nativeJarFile);
                        }
                        extractNatives(nativeJarFile, nativesDir);
                    }
                }
            }
        }

        updateProgress("Проверка и загрузка ассетов...", 60);
        System.out.println("[ASSETS-DEBUG] Начинаем проверку и загрузку ассетов.");
        if (versionData.has("assetIndex")) {
            JsonObject assetIndex = versionData.getAsJsonObject("assetIndex");
            String assetIndexUrl = assetIndex.get("url").getAsString();
            String assetIndexId = assetIndex.get("id").getAsString();
            System.out.println("[ASSETS-DEBUG] Загрузка assetIndex: " + assetIndexId + " из " + assetIndexUrl);

            File assetIndexDir = new File(assetsDir, "indexes");
            assetIndexDir.mkdirs();
            File assetIndexFile = new File(assetIndexDir, assetIndexId + ".json");

            String assetIndexJson = downloadString(assetIndexUrl);
            Files.writeString(assetIndexFile.toPath(), assetIndexJson);
            System.out.println("[ASSETS-DEBUG] assetIndex.json сохранен в: " + assetIndexFile.getAbsolutePath());

            JsonObject assetIndexData = new Gson().fromJson(assetIndexJson, JsonObject.class);
            JsonObject objects = assetIndexData.getAsJsonObject("objects");

            File objectsDir = new File(assetsDir, "objects");
            int totalAssets = objects.size();
            int currentAsset = 0;
            System.out.println("[ASSETS-DEBUG] Обнаружено " + totalAssets + " ассетов. Начинаем проверку/загрузку файлов.");

            for (String key : objects.keySet()) {
                currentAsset++;
                if (currentAsset % 50 == 0 || currentAsset == totalAssets) {
                    int progress = 60 + (currentAsset * 35 / totalAssets);
                    updateProgress("Ассеты: " + currentAsset + "/" + totalAssets, progress);
                }

                JsonObject asset = objects.getAsJsonObject(key);
                String hash = asset.get("hash").getAsString();
                String hashPrefix = hash.substring(0, 2);

                File assetFile = new File(objectsDir, hashPrefix + File.separator + hash);
                boolean needsDownload = false;
                if (!assetFile.exists()) {
                    System.out.println("[ASSETS-DEBUG] Файл ассета не найден: " + assetFile.getName() + ". Требуется загрузка.");
                    needsDownload = true;
                } else {
                    try {
                        String existingHash = calculateSHA1(assetFile);
                        if (!existingHash.equals(hash)) {
                            System.out.println("[ASSETS-DEBUG] Хеш не совпадает для: " + assetFile.getName() + ", ожидается: " + hash + ", фактически: " + existingHash + ". Требуется загрузка.");
                            needsDownload = true;
                        } else {
                            // System.out.println("[ASSETS-DEBUG] Файл ассета существует и хеш совпадает: " + assetFile.getName());
                        }
                    } catch (Exception e) {
                        System.err.println("[ASSETS-DEBUG] Ошибка при проверке хеша файла: " + assetFile.getName() + ", перекачиваем. Ошибка: " + e.getMessage());
                        needsDownload = true;
                    }
                }

                if (needsDownload) {
                    assetFile.getParentFile().mkdirs();
                    String assetUrl = LauncherConfig.getString("minecraft_assets_base_url") + hashPrefix + "/" + hash;
                    System.out.println("[ASSETS-DEBUG] Загрузка ассета: " + assetFile.getName() + " из " + assetUrl);
                    downloadFile(assetUrl, assetFile);
                }
            }
            System.out.println("[ASSETS-DEBUG] Проверка и загрузка всех файлов ассетов завершена.");
        } else {
            System.out.println("[ASSETS-DEBUG] assetIndex не найден в versionData.");
        }

        updateProgress("Все файлы проверены и загружены!", 95);
        if (callback != null) {
            callback.onComplete();
        }

        try {
            File vanillaVersionDir = new File(gameDir, "versions" + File.separator + version);
            File vanillaNativesDir = new File(vanillaVersionDir, "natives");
            File vanillaJson = new File(vanillaVersionDir, version + ".json");
            if (vanillaJson.exists()) {
                JsonObject vanillaVer = new Gson().fromJson(new java.io.FileReader(vanillaJson), JsonObject.class);
                if (vanillaVer.has("libraries")) {
                    JsonArray vanillaLibraries = vanillaVer.getAsJsonArray("libraries");
                    for (JsonElement libElement : vanillaLibraries) {
                        JsonObject lib = libElement.getAsJsonObject();
                        if (lib.has("downloads") && lib.getAsJsonObject("downloads").has("classifiers")) {
                            JsonObject classifiers = lib.getAsJsonObject("downloads").getAsJsonObject("classifiers");
                            String nativeKey = getNativeClassifier();
                            if (classifiers.has(nativeKey)) {
                                JsonObject nativeArtifact = classifiers.getAsJsonObject(nativeKey);
                                String path = nativeArtifact.get("path").getAsString();
                                File nativeFile = new File(gameDir, "libraries/" + path);
                                if (nativeFile.exists()) {
                                    extractNatives(nativeFile, vanillaNativesDir);
                                }
                            }
                        }
                    }
                }
            }
            File versionsDir = new File(gameDir, "versions");
            File[] possibleDirs = versionsDir.listFiles(File::isDirectory);
            if (possibleDirs != null) {
                for (File dir : possibleDirs) {
                    if (dir.getName().contains("fabric-loader") && dir.getName().contains(version)) {
                        File fabricNativesDir = new File(dir, "natives");
                        File fabricJson = new File(dir, dir.getName() + ".json");
                        if (fabricJson.exists()) {
                            JsonObject fabricVer = new Gson().fromJson(new java.io.FileReader(fabricJson), JsonObject.class);
                            if (fabricVer.has("libraries")) {
                                JsonArray fabricLibraries = fabricVer.getAsJsonArray("libraries");
                                for (JsonElement libElement : fabricLibraries) {
                                    JsonObject lib = libElement.getAsJsonObject();
                                    if (lib.has("downloads") && lib.getAsJsonObject("downloads").has("classifiers")) {
                                        JsonObject classifiers = lib.getAsJsonObject("downloads").getAsJsonObject("classifiers");
                                        String nativeKey = getNativeClassifier();
                                        if (classifiers.has(nativeKey)) {
                                            JsonObject nativeArtifact = classifiers.getAsJsonObject(nativeKey);
                                            String path = nativeArtifact.get("path").getAsString();
                                            File nativeFile = new File(gameDir, "libraries/" + path);
                                            if (nativeFile.exists()) {
                                                extractNatives(nativeFile, fabricNativesDir);
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } catch(Exception ex) {
            System.err.println("[NATIVES] Ошибка автораспаковки natives: " + ex.getMessage());
            ex.printStackTrace();
        }
    }

    public void downloadVersion(String version) throws Exception {
        updateProgress("Выполняется полная загрузка версии " + version + "...", 0);
        verifyAndDownloadAllFiles(version);
    }

    private String downloadString(String urlString) throws Exception {
        URL url = new URL(urlString);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/100.0.4896.88 Safari/537.36"); // Добавляем User-Agent

        BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
        StringBuilder response = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            response.append(line);
        }
        reader.close();

        return response.toString();
    }

    private void downloadFile(String urlString, File destination) throws Exception {
        if (destination.exists()) {
            return;
        }

        URL url = new URL(urlString);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");

        try (InputStream in = conn.getInputStream()) {
            Files.copy(in, destination.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public void downloadDllFromServer(String dllFileName, File destinationDir) throws Exception {
        destinationDir.mkdirs();
        File destinationFile = new File(destinationDir, dllFileName);
        String downloadUrl = LauncherConfig.getString("natives_server_url") + dllFileName;

        System.out.println("[NATIVES] Попытка скачать DLL с сервера: " + downloadUrl + " в " + destinationFile.getAbsolutePath());

        try {
            downloadFile(downloadUrl, destinationFile);
            if (destinationFile.exists()) {
                System.out.println("[NATIVES] Успешно скачан DLL: " + dllFileName);
            } else {
                throw new IOException("Файл не был скачан или не существует после загрузки.");
            }
        } catch (Exception e) {
            System.err.println("[NATIVES] Ошибка при скачивании DLL " + dllFileName + " с сервера: " + e.getMessage());
            throw e;
        }
    }

    private void extractNatives(File zipFile, File destDir) throws Exception {
        destDir.mkdirs();
        try (ZipInputStream zis = new ZipInputStream(new FileInputStream(zipFile))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                String entryName = entry.getName();
                
                if (entry.isDirectory() || entryName.contains("META-INF")) {
                    continue;
                }
                
                if (!entryName.endsWith(".dll") && !entryName.endsWith(".so") && !entryName.endsWith(".dylib")) {
                    continue;
                }
                
                File outputFile = new File(destDir, entryName);
                
                outputFile.getParentFile().mkdirs();
                
                System.out.println("[NATIVES] Извлечение: " + entryName + " -> " + outputFile.getAbsolutePath());

                try (FileOutputStream fos = new FileOutputStream(outputFile)) {
                    byte[] buffer = new byte[8192];
                    int len;
                    while ((len = zis.read(buffer)) > 0) {
                        fos.write(buffer, 0, len);
                    }
                }

                if (outputFile.getName().equals("OpenAL.dll")) {
                    File opengl32File = new File(destDir, "opengl32.dll");
                    if (!opengl32File.exists()) {
                        System.out.println("[NATIVES] Копирование OpenAL.dll в opengl32.dll...");
                        try {
                            Files.copy(outputFile.toPath(), opengl32File.toPath(), StandardCopyOption.REPLACE_EXISTING);
                            System.out.println("[NATIVES] Успешно скопировано: OpenAL.dll -> opengl32.dll");
                        } catch (IOException e) {
                            System.err.println("[NATIVES] Ошибка при копировании OpenAL.dll в opengl32.dll: " + e.getMessage());
                            e.printStackTrace();
                        }
                    }
                }
            }
        }
    }

    public void prepareNativesForVersion(String versionName) throws Exception {
        System.out.println("[NATIVES] Подготовка natives для профиля: " + versionName);
        File versionDir = new File(gameDir, "versions" + File.separator + versionName);
        File nativesDir = new File(versionDir, "natives");
        File versionJson = new File(versionDir, versionName + ".json");
        
        if (!versionJson.exists()) {
            System.err.println("[NATIVES] Не найден JSON для версии: " + versionName);
            return;
        }

        if (nativesDir.exists()) {
            System.out.println("[NATIVES] Очистка старых natives...");
            deleteDirectory(nativesDir);
        }
        nativesDir.mkdirs();
        
        JsonObject versionData = new Gson().fromJson(new FileReader(versionJson), JsonObject.class);
        if (!versionData.has("libraries")) {
            System.out.println("[NATIVES] В JSON нет секции libraries");
            return;
        }
        
        JsonArray libraries = versionData.getAsJsonArray("libraries");
        int nativesExtracted = 0;
        
        for (JsonElement libElement : libraries) {
            JsonObject lib = libElement.getAsJsonObject();
            

            if (lib.has("rules") && !checkRules(lib.getAsJsonArray("rules"))) {
                continue;
            }
            
            if (!lib.has("downloads")) continue;
            
            JsonObject downloads = lib.getAsJsonObject("downloads");
            if (!downloads.has("classifiers")) continue;
            
            JsonObject classifiers = downloads.getAsJsonObject("classifiers");
            String nativeKey = getNativeClassifier();
            System.out.println("[NATIVES-DEBUG] System os.arch: " + System.getProperty("os.arch"));
            System.out.println("[NATIVES-DEBUG] Generated nativeKey: " + nativeKey);
            System.out.println("[NATIVES-DEBUG] Available classifiers: " + classifiers.keySet());
            
            if (classifiers.has(nativeKey)) {
                JsonObject nativeArtifact = classifiers.getAsJsonObject(nativeKey);
                String path = nativeArtifact.get("path").getAsString();
                String url = nativeArtifact.get("url").getAsString();
                
                File nativeJarFile = new File(gameDir, "libraries" + File.separator + path);
                if (!nativeJarFile.exists()) {
                    nativeJarFile.getParentFile().mkdirs();
                    System.out.println("[NATIVES] Скачивание native JAR: " + nativeJarFile.getName());
                    downloadFile(url, nativeJarFile);
                }
                
                if (nativeJarFile.exists()) {
                    System.out.println("[NATIVES] Распаковка JAR: " + nativeJarFile.getName());
                    extractNatives(nativeJarFile, nativesDir);
                    nativesExtracted++;
                }
            }
        }
        
        System.out.println("[NATIVES] Распаковано JAR-файлов с natives: " + nativesExtracted);
    }

    public boolean validateNativesExist(File nativesDir, String[] neededLibs) {
        if (!nativesDir.exists() || !nativesDir.isDirectory()) {
            System.err.println("[NATIVES] Директория не существует: " + nativesDir.getAbsolutePath());
            return false;
        }
        System.out.println("=== Содержимое папки natives: " + nativesDir.getAbsolutePath() + " ===");
        StringBuilder missing = new StringBuilder();
        for (String lib : neededLibs) {
            File f = findFileRecursively(nativesDir, lib);
            if (f == null || !f.exists()) {
                System.err.println("[NATIVES] Не найден: " + lib);
                missing.append(lib).append(" ");
            } else {
                System.out.println("[NATIVES] OK: " + lib + " (найден по пути: " + f.getAbsolutePath() + ")");
            }
        }
        File[] files = nativesDir.listFiles();
        if (files != null) {
            for (File file : files) {
                System.out.println("    - " + file.getName() + (file.isFile() ? " (file)" : " (dir)"));
            }
        }
        if (missing.length() > 0) {
            System.err.println("[NATIVES] Отсутствуют необходимые natives: " + missing);
            return false;
        }
        return true;
    }

    private boolean checkRules(JsonArray rules) {
        String os = getOSName();
        String arch = System.getProperty("os.arch").contains("64") ? "64" : "32";

        for (JsonElement ruleElement : rules) {
            JsonObject rule = ruleElement.getAsJsonObject();
            String action = rule.get("action").getAsString();

            if (rule.has("os")) {
                JsonObject osRule = rule.getAsJsonObject("os");
                if (osRule.has("name")) {
                    String osName = osRule.get("name").getAsString();
                    if (osRule.has("arch") && !osRule.get("arch").getAsString().contains(arch)) {
                        continue;
                    }
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
        if (os.startsWith("windows")) return "windows";
        if (os.contains("mac")) return "osx";
        return "linux";
    }

    private String getNativeClassifier() {
        String os = getOSName();
        String arch = System.getProperty("os.arch").toLowerCase();

        if (os.equals("windows")) {
            if (arch.contains("64")) {
                return "natives-windows-x64";
            } else {
                return "natives-windows-x86"; 
            }
        } else if (os.equals("osx")) {
            return "natives-macos"; 
        } else {
            return "natives-linux"; 
        }
    }

    public void downloadAndInstallFabric(String minecraftVersion, JSBridge jsBridge) throws Exception {
        updateProgress("Загрузка установщика Fabric...", 5);
        File tempDir = Files.createTempDirectory("fabric_installer").toFile();
        File fabricInstallerFile = new File(tempDir, "fabric-installer.jar");
        String fabricInstallerUrl = LauncherConfig.getString("fabric_installer_url");
        downloadFile(fabricInstallerUrl, fabricInstallerFile);

        try {
            updateProgress("Установка Fabric Loader...", 15);
            ProcessBuilder pb = new ProcessBuilder(
                "java",
                "-jar",
                fabricInstallerFile.getAbsolutePath(),
                "client",
                "-mcversion", minecraftVersion,
                "-dir", gameDir.getAbsolutePath(),
                "-noprofile"
            );
            pb.redirectErrorStream(true);
            pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
            Process process = pb.start();
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                throw new Exception("Ошибка установки Fabric Loader. Код выхода: " + exitCode + ". Проверьте, что Java установлена и доступна.");
            }
            updateProgress("Fabric Loader установлен!", 25);
        } finally {
            deleteDirectory(tempDir);
        }

        File versionsDir = new File(gameDir, "versions");
        File[] possibleDirs = versionsDir.listFiles(File::isDirectory);
        File fabricVersionFolder = null;
        String fabricVersionName = null;
        if (possibleDirs != null) {
            for (File dir : possibleDirs) {
                if (dir.getName().contains("fabric-loader") && dir.getName().contains(minecraftVersion)) {
                    fabricVersionFolder = dir;
                    fabricVersionName = dir.getName();
                    break;
                }
            }
        }
        if (fabricVersionFolder != null && fabricVersionName != null) {
            File fabricJson = new File(fabricVersionFolder, fabricVersionName + ".json");
            File fabricJar = new File(fabricVersionFolder, fabricVersionName + ".jar");
            if (fabricJson.exists() && !fabricJar.exists()) {
                try {
                    com.google.gson.JsonObject obj = new Gson().fromJson(new java.io.FileReader(fabricJson), com.google.gson.JsonObject.class);
                    if (obj.has("downloads")) {
                        com.google.gson.JsonObject dls = obj.getAsJsonObject("downloads");
                        if (dls.has("artifact")) {
                            com.google.gson.JsonObject artifact = dls.getAsJsonObject("artifact");
                            if (artifact.has("url")) {
                                String jarUrl = artifact.get("url").getAsString();
                                updateProgress("Докачка fabric-loader .jar...", 27);
                                downloadFile(jarUrl, fabricJar);
                                updateProgress("Докачан fabric-loader .jar!", 28);
                            }
                        }
                    }
                } catch (Exception e) {
                    System.err.println("[Fabric] Не удалось докачать fabric-loader jar: " + e.getMessage());
                    e.printStackTrace();
                }
            }
        }
    }

    public void downloadAllowedMods(List<String> allowedMods, File modsDir) throws Exception {
        updateProgress("Загрузка разрешенных модов...", 85);
        modsDir.mkdirs();

        int totalMods = allowedMods.size();
        for (int i = 0; i < totalMods; i++) {
            String modFileName = allowedMods.get(i);
            String modUrl = LauncherConfig.getString("mod_download_url");
            File modFile = new File(modsDir, modFileName);

            if (!modFile.exists()) {
                updateProgress("Загрузка мода: " + modFileName, 85 + (i * 5 / totalMods));
                downloadFile(modUrl, modFile);
                System.out.println("Java: Загружен мод: " + modFileName);
            } else {
                System.out.println("Java: Мод уже существует: " + modFileName);
            }
        }
        updateProgress("Загрузка модов завершена.", 90);
    }

    private boolean extractDllFromLibraries(String dllName, File destinationDir) {
        System.out.println("[NATIVES] Поиск " + dllName + " в JAR-файлах libraries...");
        File librariesDir = new File(gameDir, "libraries");
        if (!librariesDir.exists()) {
            return false;
        }
        
        try {
            java.util.List<File> jarFiles = new java.util.ArrayList<>();
            findJarFiles(librariesDir, jarFiles);
            
            for (File jarFile : jarFiles) {
                try (ZipInputStream zis = new ZipInputStream(new FileInputStream(jarFile))) {
                    ZipEntry entry;
                    while ((entry = zis.getNextEntry()) != null) {
                        String entryName = entry.getName();
                        if (entryName.endsWith(dllName) || entryName.endsWith("/" + dllName)) {
                            File outputFile = new File(destinationDir, dllName);
                            outputFile.getParentFile().mkdirs();
                            
                            try (FileOutputStream fos = new FileOutputStream(outputFile)) {
                                byte[] buffer = new byte[8192];
                                int len;
                                while ((len = zis.read(buffer)) > 0) {
                                    fos.write(buffer, 0, len);
                                }
                            }
                            System.out.println("[NATIVES] Извлечен " + dllName + " из " + jarFile.getName());
                            return true;
                        }
                    }
                } catch (Exception e) {
                }
            }
        } catch (Exception e) {
            System.err.println("[NATIVES] Ошибка при поиске DLL в JAR: " + e.getMessage());
        }
        return false;
    }
    
    private void findJarFiles(File directory, java.util.List<File> jarFiles) {
        File[] files = directory.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isDirectory()) {
                    findJarFiles(file, jarFiles);
                } else if (file.getName().endsWith(".jar")) {
                    jarFiles.add(file);
                }
            }
        }
    }

    private void deleteDirectory(File directory) {
        try {
            Files.walk(directory.toPath())
                .sorted(Comparator.reverseOrder())
                .map(Path::toFile)
                .forEach(File::delete);
        } catch (IOException e) {
            System.err.println("Ошибка при удалении временной директории " + directory.getAbsolutePath() + ": " + e.getMessage());
        }
    }

    public File findFileRecursively(File baseDir, String fileName) {
        if (!baseDir.exists() || !baseDir.isDirectory()) {
            return null;
        }
        File[] files = baseDir.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isDirectory()) {
                    File found = findFileRecursively(file, fileName);
                    if (found != null) {
                        return found;
                    }
                } else if (file.getName().equals(fileName)) {
                    return file;
                }
            }
        }
        return null;
    }

    private String calculateSHA1(File file) throws Exception {
        java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-1");
        try (java.io.InputStream fis = new java.io.FileInputStream(file)) {
            byte[] buffer = new byte[8192];
            int numOfBytesRead;
            while( (numOfBytesRead = fis.read(buffer)) > 0){
                md.update(buffer, 0, numOfBytesRead);
            }
        }
        byte[] hash = md.digest();
        StringBuilder sb = new StringBuilder();
        for (byte b : hash) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
