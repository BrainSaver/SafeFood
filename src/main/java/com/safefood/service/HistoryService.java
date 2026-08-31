package com.safefood.service;

import com.safefood.dao.HistoryDao;
import com.safefood.dto.HistoryDto;
import java.util.List;

public class HistoryService {
    private final HistoryDao historyDao = new HistoryDao();

    // 먹은 기록 저장
    public boolean saveEatenHistory(int userId, int menuId) {
        return historyDao.insertHistory(userId, menuId, null, "EATEN");
    }

    // 메뉴 차단하기 (히스토리 행을 BLOCKED로 변경하거나 새로 추가)
    public boolean blockHistory(int historyId, int userId, int menuId) {
        if (historyId > 0) {
            boolean updated = historyDao.updateHistoryType(historyId, "BLOCKED");
            if (updated) {
                return true;
            }
        }
        return historyDao.blockMenu(userId, menuId);
    }

    // 메뉴 차단 해제하기 (상태를 다시 'EATEN' 먹음으로 변경)
    public boolean unblockHistory(int historyId, int userId, int menuId) {
        if (historyId > 0) {
            boolean updated = historyDao.updateHistoryType(historyId, "EATEN");
            if (updated) {
                return true;
            }
        }
        return historyDao.unblockMenuToEaten(userId, menuId);
    }

    public boolean deleteHistory(int historyId) {
        return historyDao.deleteHistory(historyId);
    }

    // 목록 조회
    public List<HistoryDto> getHistories(int userId) {
        return historyDao.findHistoriesByUserId(userId);
    }

    public List<HistoryDto> getEatenHistory(int userId) {
        return historyDao.findHistoriesByUserId(userId);
    }
}