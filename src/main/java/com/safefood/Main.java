package com.safefood;

import com.safefood.dao.DatabaseUtil;
import com.safefood.setup.SetupWizardFx;
import com.safefood.view.AppNav;
import javafx.application.Application;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

public class Main extends Application {

    @Override
    public void start(Stage stage) {
        stage.setTitle("SafeFood");
        stage.setScene(new Scene(splash(), 460, 220));
        stage.centerOnScreen();
        stage.show();

        Task<Boolean> probe = new Task<>() {
            @Override
            protected Boolean call() {
                return DatabaseUtil.isReady();
            }
        };
        probe.setOnSucceeded(event -> route(stage, Boolean.TRUE.equals(probe.getValue())));
        probe.setOnFailed(event -> route(stage, false));

        Thread worker = new Thread(probe, "db-probe");
        worker.setDaemon(true);
        worker.start();
    }

    private void route(Stage stage, boolean ready) {
        if (ready) {
            showApp(stage);
            return;
        }
        SetupWizardFx wizard = new SetupWizardFx();
        wizard.setOnFinish(() -> {
            AppConfig.reload();
            // 마법사가 방금 심은 알레르기 마스터를 다시 읽습니다 (그 전에는 못 읽어 비어 있습니다)
            com.safefood.dao.AllergyCatalog.reload();
            showApp(stage);
        });
        wizard.start(stage);
    }

    private void showApp(Stage stage) {
        stage.setMaximized(false);
        AppNav.init(stage);
        AppNav.show("로그인", "login.fxml");
        stage.centerOnScreen();
    }

    private static VBox splash() {
        Label title = new Label("SafeFood");
        title.setStyle("-fx-font-size: 26px; -fx-font-weight: bold;");

        Label status = new Label("데이터베이스 연결을 확인하는 중입니다…");
        status.setStyle("-fx-text-fill: #6b6b6b;");

        ProgressBar bar = new ProgressBar();
        bar.setPrefWidth(240);

        VBox box = new VBox(14, title, status, bar);
        box.setAlignment(Pos.CENTER);
        box.setPadding(new Insets(32));
        box.setStyle("-fx-background-color: white;");
        return box;
    }

    public static void main(String[] args) {
        launch(args);
    }
}
