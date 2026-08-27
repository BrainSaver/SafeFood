package com.safefood.view;

import javafx.application.Platform;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * 화면 스레드 밖에서 조회하고, 끝나면 화면 스레드로 돌아옵니다.
 *
 * <p>이 규칙이 화면마다 복사되어 있었습니다 — {@code newSingleThreadExecutor} 를 만들고,
 * {@code latestRequest} 토큰으로 늦게 온 응답을 버리고, {@code Platform.runLater} 로 넘기는
 * 세 가지를 매번 손으로 맞췄습니다. 하나만 빠뜨리면 창이 조용히 멎거나(FX 스레드 조회),
 * 필터를 연타할 때 옛 결과가 새 화면을 덮습니다.
 *
 * <h3>쓰는 법</h3>
 * <pre>
 * private final AsyncLoader loader = new AsyncLoader();
 *
 * loader.load(() -&gt; dao.findByUser(userId),   // 백그라운드
 *             list -&gt; render(list),            // FX 스레드, 최신 요청일 때만
 *             error -&gt; showError(error));      // FX 스레드
 * </pre>
 *
 * <p><b>스레드를 하나만 씁니다.</b> 커넥션 풀이 없어 조회마다 새로 연결하므로, 필터를 연타해도
 * 커넥션이 한꺼번에 몰리지 않게 줄을 세웁니다. 화면을 드나들 때마다 스레드가 쌓이지 않도록
 * 컨트롤러마다 만들지 않고 전부 같은 것을 씁니다.
 *
 * <p><b>{@link AsyncLoader} 자체는 컨트롤러마다 하나씩</b> 두세요. '늦게 온 응답 버리기'는
 * 그 화면 안에서만 따져야 하는 값이라, 인스턴스가 그 경계를 만듭니다.
 */
public final class AsyncLoader {

    private static final ExecutorService POOL = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "ui-load");
        thread.setDaemon(true);
        return thread;
    });

    /** 이 화면이 띄운 마지막 요청 번호. FX 스레드에서만 만집니다. */
    private int latest;

    /**
     * 백그라운드에서 {@code work} 를 돌리고 결과를 FX 스레드의 {@code onSuccess} 로 넘깁니다.
     *
     * <p>이 호출보다 나중에 띄운 요청이 있으면 결과를 <b>버립니다</b>. 그래서 필터를 빠르게
     * 여러 번 바꿔도 마지막에 고른 조건의 결과만 화면에 남습니다.
     *
     * @param onFailure {@code work} 가 던진 예외를 FX 스레드에서 받습니다. null 이면 무시합니다
     */
    public <T> void load(Supplier<T> work, Consumer<T> onSuccess, Consumer<Throwable> onFailure) {
        int request = ++latest;
        POOL.execute(() -> {
            T value;
            try {
                value = work.get();
            } catch (RuntimeException | Error e) {
                Platform.runLater(() -> {
                    if (request == latest && onFailure != null) {
                        onFailure.accept(e);
                    }
                });
                return;
            }
            Platform.runLater(() -> {
                if (request == latest) {
                    onSuccess.accept(value);
                }
            });
        });
    }

    /** 실패를 따로 다루지 않아도 되는 조회용 — 예외는 콘솔에만 남습니다. */
    public <T> void load(Supplier<T> work, Consumer<T> onSuccess) {
        load(work, onSuccess, error ->
                System.err.println("[ui] 조회에 실패했습니다: " + error.getMessage()));
    }

    /**
     * 결과를 <b>반드시</b> 전달합니다 — 늦었다는 이유로 버리지 않습니다.
     *
     * <p>{@link #load} 와 갈라 두는 이유: 찜하기·기록 저장 같은 <b>동작</b>은 나중 요청이
     * 앞선 것을 대신하는 관계가 아닙니다. 이런 것까지 {@code load} 로 보내면, 사용자가 찜을
     * 누르는 순간 진행 중이던 목록 조회가 '낡은 요청'이 되어 화면이 갱신되지 않습니다.
     *
     * <p>어느 쪽인지 가르는 기준: <b>같은 자리를 다시 채우는 조회면 {@code load}</b>,
     * 그 밖의 일은 {@code submit}.
     */
    public <T> void submit(Supplier<T> work, Consumer<T> onDone, Consumer<Throwable> onFailure) {
        POOL.execute(() -> {
            T value;
            try {
                value = work.get();
            } catch (RuntimeException | Error e) {
                Platform.runLater(() -> {
                    if (onFailure != null) {
                        onFailure.accept(e);
                    }
                });
                return;
            }
            Platform.runLater(() -> onDone.accept(value));
        });
    }

    /**
     * 지금까지 띄운 요청의 결과를 전부 버립니다.
     *
     * <p>화면을 떠나거나 대상이 바뀔 때(다른 아이 선택 등) 부르면, 이미 날아간 조회가
     * 돌아와도 새 화면을 건드리지 않습니다.
     */
    public void discardPending() {
        latest++;
    }
}
