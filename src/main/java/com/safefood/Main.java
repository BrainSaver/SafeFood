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
                boolean ready = DatabaseUtil.isReady();
                if (ready) {
                    checkAllergyMaster();
                }
                return ready;
            }
        };
        probe.setOnSucceeded(event -> route(stage, Boolean.TRUE.equals(probe.getValue())));
        probe.setOnFailed(event -> route(stage, false));

        Thread worker = new Thread(probe, "db-probe");
        worker.setDaemon(true);
        worker.start();
    }

    /**
     * 알레르기 마스터가 어긋났는지 켤 때 한 번 봅니다. 앱은 그대로 뜨고, 콘솔로만 알립니다 —
     * 무엇을 고칠지는 데이터를 보고 사람이 정할 일입니다.
     *
     * <p>여기서 부르는 김에 마스터를 <b>화면 스레드 밖에서</b> 미리 읽어 둡니다. 커넥션 풀이
     * 없어(DatabaseUtil 참고) 화면이 처음 그려질 때 읽으면 그 왕복만큼 창이 멎습니다.
     */
    private static void checkAllergyMaster() {
        for (String problem : com.safefood.dao.AllergyCatalog.verify()) {
            System.err.println("[allergy] " + problem);
        }
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
