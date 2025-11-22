package com.launcher;

import javafx.application.Platform;
import javafx.stage.Stage;
import javafx.scene.web.WebView;
import netscape.javascript.JSObject; 

public class JSBridge {
    private final Stage stage;
    private final WebView webView; 
    private final MinecraftLauncher launcher;
    private boolean isGameRunning = false; 

    public JSBridge(Stage stage, WebView webView) throws Exception { 
        this.stage = stage;
        this.webView = webView; 
        this.launcher = new MinecraftLauncher();
    }


    public void minimize() {
        Platform.runLater(() -> stage.setIconified(true));
    }

    public void close() {
        System.out.println("Java: close() method called in JSBridge.");
        Platform.runLater(() -> {
            System.out.println("Java: Platform.runLater() executing stage.close() and Platform.exit().");
            stage.close();
            Platform.exit();
            System.out.println("Java: stage.close() and Platform.exit() executed.");
        });
    }

    public void moveWindow(double deltaX, double deltaY) {
        Platform.runLater(() -> {
            stage.setX(stage.getX() + deltaX);
            stage.setY(stage.getY() + deltaY);
        });
    }

    public void openURL(String url) {
        try {
            java.awt.Desktop.getDesktop().browse(new java.net.URI(url));
            System.out.println("Java: Opened URL: " + url);
        } catch (Exception e) {
            System.err.println("Java: Error opening URL: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public void updateLoadingProgress(int progress, String message) {
        System.out.println(String.format("Java: Calling JS updateLoadingProgress(%d, '%s');", progress, escapeJavaScriptString(message)));
        Platform.runLater(() -> {
            try {
                webView.getEngine().executeScript(String.format("updateLoadingProgress(%d, '%s');", progress, escapeJavaScriptString(message)));
            } catch (Exception e) {
                System.err.println("Java: Error calling updateLoadingProgress: " + e.getMessage());
                e.printStackTrace();
            }
        });
    }

    public void hideLoadingScreen() {
        System.out.println("Java: Calling JS hideLoadingScreen();");
        Platform.runLater(() -> {
            try {
                webView.getEngine().executeScript("hideLoadingScreen();");
            } catch (Exception e) {
                System.err.println("Java: Error calling hideLoadingScreen: " + e.getMessage());
                e.printStackTrace();
            }
        });
    }


    public void showError(String message) {
        System.out.println("Java: Calling JS showStatus('%s', 'error');" + escapeJavaScriptString(message));
        Platform.runLater(() -> {
            try {
                webView.getEngine().executeScript(String.format("showStatus('%s', 'error');", escapeJavaScriptString(message)));
            } catch (Exception e) {
                System.err.println("Java: Error calling showError: " + e.getMessage());
                e.printStackTrace();
            }
        });
    }

    // Обновленный метод launch (больше не принимает callbacks)
    public void launch(String username) { 
        System.out.println("Java: JSBridge.launch() called with username: " + username); 
        if (isGameRunning) { 
            System.out.println("Java: Игра уже запущена. Отменяем запуск.");
            showError("Игра уже запущена!");
            return;
        }

        isGameRunning = true;
        new Thread(() -> {
            try {
                updateLoadingProgress(50, "Тестовое сообщение из Java"); 
                
                System.out.println("Java: Calling launcher.launch()...");
                launcher.launch(username, this);
            } catch (Exception e) {
                String errorMsg = e.getMessage();
                System.err.println("ОШИБКА: " + errorMsg);
                e.printStackTrace();
                System.err.flush(); 
                
                hideLoadingScreen(); 
                showError(errorMsg); 
            } finally {
                System.out.println("Java: JSBridge.launch() finally block executed.");
                isGameRunning = false;
            }
        }).start();
    }

    private String escapeJavaScriptString(String str) {
        return str.replace("\'", "\\\'").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
    }
}
