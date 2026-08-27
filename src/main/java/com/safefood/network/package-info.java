/**
 * 소켓 통신 (그룹 실시간 기능) — 방을 만든 사람이 서버가 됩니다.
 *
 * <ul>
 *   <li>{@code GroupServer} / {@code ClientHandler} / {@code Room} — 방장 쪽 서버</li>
 *   <li>{@code GroupClient} / {@code GroupSession} — 참여 쪽 소켓과 화면용 상태</li>
 *   <li>{@code Message} — {@code 타입|본문} 프로토콜</li>
 *   <li>{@code InviteCode} / {@code RoomBeacon} / {@code RoomFinder} — 코드 발급과 방 찾기</li>
 * </ul>
 *
 * <p>참여자는 코드에 담긴 주소로 먼저 붙어 보고, 실패할 때만 같은 네트워크에 방을 찾는
 * 질의를 뿌립니다({@code RoomFinder} → {@code RoomBeacon}). 평소에는 UDP를 쓰지 않습니다.
 *
 * <p>지킬 것
 * <ul>
 *   <li>자동 탐색(UDP)은 실패해도 되는 보조 수단입니다 — 탐색 실패로 방 열기를 중단하지 마세요.</li>
 *   <li>탐색 질의에는 코드의 주소부만 싣습니다. 입장 자격은 TCP {@code JOIN}에서 검사합니다.</li>
 *   <li>DB에는 방장만 접근합니다 — 참여자는 MySQL이 없어도 들어올 수 있어야 합니다.</li>
 * </ul>
 */
package com.safefood.network;
