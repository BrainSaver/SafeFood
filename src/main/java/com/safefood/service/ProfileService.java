package com.safefood.service;

import com.safefood.dao.ProfileDao;
import com.safefood.dto.PreferenceDto;

import java.util.List;
import java.util.Map;

public class ProfileService {
    private final ProfileDao profileDao;

    public ProfileService() {
        this.profileDao = new ProfileDao();
    }


    // Dao 메서드를 대신 호출
    public PreferenceDto getMyPreference(int userId) {
        return profileDao.getPreference(userId);
    }

    public List<String> getMyCategories(int userId) {
        return profileDao.getCategories(userId);
    }

    public Map<String, Integer> getMyAllergies(int userId) {
        return profileDao.getAllergies(userId);
    }

    /**
     * 맵기·예산·거리와 선호 카테고리를 저장합니다.
     *
     * @return 저장 성공 여부 — 화면이 실패를 알려 줄 수 있도록 돌려줍니다
     */
    public boolean updatePreferencesOnly(int userId, int spicyLevel, int priceMax, int maxDistance,
                                         List<String> categories){
        profileDao.updatePreference(userId, spicyLevel, priceMax, maxDistance);
        return profileDao.replaceCategories(userId, categories);
    }

    /**
     * 알레르기 목록을 저장합니다.
     *
     * <p>번호 변환과 저장을 {@link ProfileDao#replaceAllergies} 가 한 트랜잭션으로 처리하므로,
     * 실패하면 이전 목록이 그대로 남습니다 — 알레르기가 빈 프로필로 추천이 돌아가는 일은 없습니다.
     *
     * @return 저장 성공 여부
     */
    public boolean updateAllergiesOnly(int userId, Map<String, Integer> allergies){
        return profileDao.replaceAllergies(userId, allergies);
    }
}
