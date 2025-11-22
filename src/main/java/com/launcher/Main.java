package com.launcher;

import javafx.application.Application;
import javafx.scene.Scene;
import javafx.scene.web.WebView;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintStream;

public class Main extends Application {

    @Override
    public void start(Stage stage) throws Exception { 
        WebView webView = new WebView();
        

        webView.getEngine().load(getClass().getResource("/ui/index.html").toExternalForm());
        

        JSBridge bridge = new JSBridge(stage, webView);
        webView.getEngine().getLoadWorker().stateProperty().addListener((obs, oldState, newState) -> {
            if (newState == javafx.concurrent.Worker.State.SUCCEEDED) {
                try {
                    netscape.javascript.JSObject window = (netscape.javascript.JSObject) webView.getEngine().executeScript("window");
                    window.setMember("javaApp", bridge);
                } catch (Exception e) {
                    System.err.println("Error connecting Java bridge: " + e.getMessage());
                    e.printStackTrace();
                }
            }
        });

        Scene scene = new Scene(webView, 1024, 600);
        
        stage.setTitle("Counter-Mine 2 Launcher");
        stage.setScene(scene);
        stage.initStyle(StageStyle.UNDECORATED);
        stage.setResizable(false);
        stage.show();
        System.out.println("DEBUG: JavaFX Application started successfully.");
    }

    public static void main(String[] args) {
        setupLogging();
        launch(args);
    }

    private static void setupLogging() {
        try {
            File jarFile = new File(Main.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            File launcherDir = jarFile.getParentFile();

            System.out.println("DEBUG: JAR Path: " + jarFile.getAbsolutePath());
            System.out.println("DEBUG: Launcher Directory: " + launcherDir.getAbsolutePath());
            File countermineDir = new File(launcherDir, ".countermine");
            System.out.println("DEBUG: .countermine Directory: " + countermineDir.getAbsolutePath());
            if (!countermineDir.exists()) {
                System.out.println("DEBUG: Creating .countermine directory...");
                countermineDir.mkdirs();
            }

            File logFile = new File(countermineDir, "launcher.log");
            System.out.println("DEBUG: Log File Path: " + logFile.getAbsolutePath());

            PrintStream ps = new PrintStream(new FileOutputStream(logFile, true)); // true для дозаписи
            System.setOut(ps);
            System.setErr(ps);

            System.out.println("--- Launcher Log Started ---");
            System.out.println("Log file: " + logFile.getAbsolutePath());

            Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
                System.err.println("FATAL ERROR in thread " + t.getName() + ":");
                e.printStackTrace(System.err);
                System.err.flush();
            });

        } catch (Exception e) {
            System.err.println("Error setting up logging: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
