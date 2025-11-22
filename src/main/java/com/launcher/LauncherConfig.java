package com.launcher;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.FileNotFoundException; 
import java.lang.reflect.Type;
import java.util.List;
import java.util.Map;

public class LauncherConfig {
    private static Map<String, Object> configMap;

    public static void loadConfig() throws Exception {
        try (InputStream is = LauncherConfig.class.getClassLoader().getResourceAsStream("config.json")) {
            if (is == null) {
                throw new FileNotFoundException("config.json not found in resources.");
            }
            Gson gson = new GsonBuilder().create();
            Type type = new TypeToken<Map<String, Object>>() {}.getType();
            configMap = gson.fromJson(new InputStreamReader(is), type);
        } catch (Exception e) {
            System.err.println("Error loading config.json: " + e.getMessage());
            e.printStackTrace();
            throw e;
        }
    }

    public static String getString(String key) {
        return (String) configMap.get(key);
    }

    @SuppressWarnings("unchecked")
    public static List<String> getStringList(String key) {
        return (List<String>) configMap.get(key);
    }

    public static String getNativeClassifierConfigKey() {
        String os = System.getProperty("os.name").toLowerCase();
        String arch = System.getProperty("os.arch").contains("64") ? "64" : "32";

        if (os.contains("win")) {
            return "needed_natives_windows_" + arch;
        } else if (os.contains("mac")) {
            return "needed_natives_macos"; 
        } else {
            return "needed_natives_linux"; 
        }
    }
}
