package com.safefood.service;

import com.safefood.dao.FavoriteDao;
import com.safefood.dto.FavoriteDto;

import java.util.List;

public class FavoriteService {
    private final FavoriteDao favoriteDao = new FavoriteDao();

    // 찜 목록 꺼내오기
    public List<FavoriteDto> getFavorites(int userId) {
        return favoriteDao.findFavoritesByUserId(userId);
    }

    // 찜 취소하기
    public boolean removeFavorite(int favoriteId) {
        return favoriteDao.deleteFavorite(favoriteId);
    }

    // 찜 추가(저장)하기 — DAO 쪽에서 이미 찜한 메뉴를 걸러 줍니다
    public boolean addFavorite(int userId, Integer restaurantId, Integer menuId) {
        return favoriteDao.addFavorite(userId, restaurantId, menuId);
    }
}
