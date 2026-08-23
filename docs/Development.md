# SafeFood 개발 문서

> 개발 환경 설정 · 그룹 추천 로직 · 배포까지 한곳에

기능·화면·데이터 구조의 **설계 기준**은 별도 설계 문서(기능/데이터베이스/UI 설계서)이고,
이 문서는 **실제로 어떻게 돌리고 만드는지**를 다룹니다.

| 장 | 내용 |
|----|------|
| [1. 개발 환경 설정](#-개발-환경-설정) | Setup Wizard, `data/` 파일 모드, 스키마 변경 절차 |
| [2. 그룹 추천](#-그룹-추천) | 조건 병합, 알레르기 판정, 점수·후보 선정 |
| [3. 배포](#-배포--실행-파일과-설치-파일) | 실행 파일(`SafeFood.exe`)과 설치 파일 만들기 |

<br>
<br>

---

# 🚀 개발 환경 설정

> **개발 환경 설정 마법사** — 저장소를 클론한 팀원이 한 번 실행하면 끝

MySQL 접속 확인부터 DB·테이블 생성, 기본 데이터 삽입, `config.properties` 작성까지
[README의 시작하기 3~4단계](../README.md#-시작하기)를 자동으로 처리합니다.

**MySQL을 쓰지 않는 경우**에는 DB 단계를 건너뛰고, 프로젝트 루트의 `data/` 폴더에
정보를 생성·저장하는 방식으로 진행할 수 있습니다. (아래 [데이터베이스 미사용](#데이터베이스-미사용--data-폴더) 참고)

**여러 번 실행해도 안전합니다.** 이미 있는 DB·테이블·데이터·`data/` 파일은 건드리지 않고 건너뜁니다.

**화면은 두 가지입니다.** 창(JavaFX)과 콘솔 중 편한 쪽을 쓰면 되고, **하는 일과 결과는 같습니다.**

<br>

## 실행 방법

### 준비물

- JDK 21 이상 (`pom.xml`의 `maven.compiler.release` 기준)
- Maven 3.6 이상 — IntelliJ에 내장돼 있어 따로 설치하지 않아도 됩니다
- **(선택)** 실행 중인 MySQL 8.0 — DB를 쓸 때만 필요

> MySQL에 연결하지 못하면 DB 단계를 건너뜁니다.
> 이때는 **설정 파일**을 만들고, 앱 데이터는 **`data/` 폴더**에 생성·저장합니다.

> 📦 **JDBC 드라이버는 준비할 필요가 없습니다.** `pom.xml`에 의존성으로 들어 있어
> Maven이 자동으로 내려받고 클래스패스에 올려 줍니다.

### 창으로 실행 (권장)

```bash
mvn javafx:run -Pwizard-fx
```

단계 목록이 왼쪽에 계속 보이고, 입력을 **되돌아가서 고칠 수 있습니다.**
비밀번호도 가려집니다. 자세한 차이는 아래 [어느 화면을 쓸까](#어느-화면을-쓸까)를 보세요.

### 콘솔로 실행

```bash
mvn compile exec:java -Pwizard
```

> **한글이 깨진다면** 실행 전에 `chcp 65001`을 한 번 실행하세요.

### IntelliJ에서

`main` 메서드 옆 ▶ 버튼을 누르면 됩니다.
(`pom.xml`을 `Open as Project`로 열어 Maven 프로젝트로 인식된 상태여야 합니다.)

| 화면 | 열 파일 |
|------|---------|
| 창 | `src/main/java/com/safefood/setup/SetupWizardFxLauncher.java` |
| 콘솔 | `src/main/java/com/safefood/setup/SetupWizard.java` |

> ⚠️ **창 화면은 `SetupWizardFx`가 아니라 `SetupWizardFxLauncher`를 실행해야 합니다.**
> `SetupWizardFx`를 직접 ▶로 실행하면 이렇게 거절당합니다.
>
> ```text
> 오류: 이 애플리케이션을 실행하는 데 필요한 JavaFX 런타임 구성요소가 누락되었습니다.
> ```
>
> JavaFX 11부터 JavaFX가 JDK에서 빠져서, JVM은 **메인 클래스가 `Application`을 상속하면**
> JavaFX가 *모듈 경로*에 있는지 검사합니다. 그런데 IntelliJ ▶ 버튼은 의존성을
> *클래스패스*에 올리기 때문에, jar가 분명히 있는데도 위 오류가 납니다.
> `SetupWizardFxLauncher`는 `Application`을 상속하지 않아 이 검사를 지나갑니다.
> (`mvn javafx:run -Pwizard-fx`는 플러그인이 모듈 경로를 붙여 주므로 어느 쪽이든 됩니다.)

<br>

## 어느 화면을 쓸까

| | 창 (JavaFX) | 콘솔 |
|--|-------------|------|
| 실행 | `mvn javafx:run -Pwizard-fx` | `mvn compile exec:java -Pwizard` |
| 입력 수정 | 언제든 [뒤로]로 돌아가 고칠 수 있음 | 지나가면 다시 실행해야 함 |
| 잘못된 입력 | 그 자리에서 빨간 글씨로 안내 | 같은 질문을 다시 물어봄 |
| 비밀번호 | 항상 가려짐 | IntelliJ 실행창에서는 그대로 보임 |
| 연결 테스트 | 버튼으로 여러 번, 창이 멈추지 않음 | 다음 단계로 넘어가며 1회 |
| 진행 상황 | 진행 막대 + 로그 | 텍스트 로그 |
| 원격 접속·CI | 화면이 없으면 못 씀 | 어디서나 됨 |

> 두 화면 모두 값을 모아 `SetupService`에 넘기는 구조라서 **결과가 달라질 일이 없습니다.**

<br>

## 마법사가 하는 일

| 단계 | 내용 | 실패하면 |
|------|------|----------|
| 1 | JDBC 드라이버 확인 (Maven이 이미 준비) | `data/` 모드로만 진행할지 선택 |
| 2 | 접속 정보 입력 → 연결 테스트 | 원인을 한글로 안내하고 재입력 / DB 건너뛰기 |
| 3 | `CREATE DATABASE safefood` | 이후 DB 단계 건너뛰고 `data/` 모드로 진행 |
| 4 | 테이블 22개 생성 | 이후 DB 단계 건너뜀 |
| 5 | 알레르기·기분 태그 기본 데이터 삽입 | 건너뜀 (기본 목록은 6단계에서 `data/public/`에도 생성) |
| 6 | `data/public`·`data/private` 폴더와 기본 파일 준비 | 알려 주고 7단계는 계속 진행 |
| 7 | `config.properties` 작성 + `.gitignore` 확인 | — |

1·2단계는 사용자에게 묻는 부분이라 화면(`setup/`)이 맡고,
3~7단계는 두 화면이 공유하는 처리부(`setup/core/`)가 담당합니다.
자세한 구성은 아래 [파일 구성](#파일-구성)을 보세요.

중간에 실패해도 **할 수 있는 데까지 진행하고**, 마지막에 무엇이 남았는지 알려 줍니다.
MySQL을 켠 뒤 다시 실행하면 남은 DB 준비를 이어서 합니다.

> 6단계는 **DB를 쓰든 안 쓰든 항상** 실행합니다.
> 나중에 MySQL을 끄고 파일 모드로 바꿔도 바로 쓸 수 있게 하기 위해서입니다.

### 만들어지는 파일

| 파일 | 커밋 | 설명 |
|------|------|------|
| `config.properties` | ❌ **금지** | 내 DB 계정·API 키가 들어 있음 (프로젝트 루트) |
| `config.properties.example` | ⭕ | 값이 빈 공유용 사본 |
| `config.properties.bak` | ❌ | 덮어쓰기 전 자동 백업 |
| `.gitignore` | ⭕ | `/config.properties`·`/config.properties.bak`·`data/private/` 항목이 없으면 자동 추가 |
| `data/public/` | ⭕ | 앱 동작에 필요한 공통 데이터 |
| `data/private/` | ❌ | 개인 데이터 · 팀 서버 데이터 |

> `config.properties.example`은 **항상 프로젝트 기본값**(`safefood`, `5000`)으로 씁니다.
> 팀이 공유하는 커밋 대상이라, 각자 고른 DB 이름·포트가 들어가면 실행할 때마다 충돌하기 때문입니다.
> 내용이 이미 같으면 아예 손대지 않습니다.

<br>

## 데이터베이스 미사용 — `data/` 폴더

MySQL을 설치하지 않았거나, 드라이버·연결이 없어 DB 단계를 건너뛴 경우
앱이 쓰는 정보는 프로젝트 루트의 **`data/`** 폴더에 생성·저장합니다.

### 언제 쓰이나

- JDBC 드라이버가 없을 때
- MySQL 서버에 연결하지 못했을 때
- 팀원이 DB 없이 UI·소켓·추천 로직만 먼저 돌리고 싶을 때

> 폴더와 기본 파일은 **DB를 쓰더라도 미리 만들어 둡니다.**
> 나중에 MySQL을 끄고 파일 모드로 바꿔도 그대로 쓸 수 있게 하기 위해서입니다.

### 폴더 역할

| 폴더 | 들어가는 것 | 커밋 |
|------|-------------|------|
| `data/public/` | 앱이 **동작하는 데 필요한** 공통 데이터 (알레르기·기분 태그·메뉴 마스터 등) | ⭕ |
| `data/private/` | **개인 데이터**(회원·취향·즐겨찾기·내 히스토리) + **팀 서버 데이터**(방장 쪽 그룹·투표·접속 기록 등) | ❌ |

### 폴더·파일 구조

```
data/
├── public/                        # 앱에 필요한 공통 데이터 (커밋 가능)
│   ├── allergy.txt                # 알레르기 마스터 19종
│   ├── mood.txt                   # 기분 태그 마스터 10종
│   ├── food.txt                   # 음식 사전 (공공데이터, 선택)
│   ├── ingredient.txt             # 재료 마스터 (선택)
│   ├── food_ingredient.txt        # 음식별 재료 구성 (선택)
│   ├── ingredient_allergy.txt     # 재료 → 알레르기 매핑 (선택)
│   ├── restaurant.txt             # 가게 마스터 (선택)
│   ├── menu.txt                   # 메뉴 마스터 (선택)
│   ├── menu_allergy.txt           # 메뉴별 알레르기 (선택)
│   └── icon/                      # 앱 아이콘 (logo.png, safefood.ico)
└── private/                       # 커밋 금지
    ├── users.txt                  # [개인] 회원·게스트
    ├── preferences.txt            # [개인] 취향·예산·거리
    ├── user_allergies.txt         # [개인] 보유 알레르기 + 심각도
    ├── categories.txt             # [개인] 선호 음식 종류
    ├── favorites.txt              # [개인] 즐겨찾기
    ├── history.txt                # [개인] 내 추천 히스토리
    ├── groups.txt                 # [팀 서버] 그룹·초대 코드
    ├── group_members.txt          # [팀 서버] 참여자·조건
    ├── group_member_allergies.txt # [팀 서버] 참여 시점 알레르기 스냅샷
    └── votes.txt                  # [팀 서버] 투표·결과
```

> 파일이 없으면 마법사(또는 앱 최초 실행)가 **기본 내용으로 새로 만듭니다.**
> 이미 있는 파일은 **덮어쓰지 않습니다.**

### 저장 규칙

| 항목 | 내용 |
|------|------|
| 위치 | 프로젝트 루트 `data/public/`, `data/private/` |
| 인코딩 | UTF-8 |
| 형식 | 텍스트 한 줄 = 한 레코드, 필드는 `\|`로 구분 |
| 커밋 | `public/`만 공유 · `private/`(개인·팀 서버)는 `.gitignore` |

> ⚠️ **주석 줄을 넣지 마세요.** 한 줄이 곧 한 레코드라서 `#`으로 시작하는 줄도
> 레코드로 읽힙니다. 그래서 마법사가 만드는 기본 파일에도 주석이 없습니다.

**필드 순서** — `core/Schema.java`의 테이블 컬럼 순서와 같습니다.
(데이터베이스 설계서의 테이블 정의서와도 같아야 합니다)

| 파일 | 대응 테이블 | 필드 |
|------|------------|------|
| `public/allergy.txt` | `allergy` | `name` |
| `public/mood.txt` | `mood` | `name` |
| `public/food.txt` | `food` | `id\|food_cd\|name\|category` |
| `public/ingredient.txt` | `ingredient` | `id\|name` |
| `public/food_ingredient.txt` | `food_ingredient` | `food_id\|ingredient_id\|quantity` |
| `public/ingredient_allergy.txt` | `ingredient_allergy` | `ingredient_id\|allergy_id` |
| `public/restaurant.txt` | `restaurant` | `id\|name\|category\|address\|phone\|open_time\|close_time\|latitude\|longitude\|rating\|review_count` |
| `public/menu.txt` | `menu` | `id\|restaurant_id\|name\|price\|category\|spicy_level\|description\|food_id` |
| `public/menu_allergy.txt` | `menu_allergy` | `menu_id\|allergy_id\|risk_level\|ingredient` |
| `private/users.txt` | `user` | `id\|login_id\|password\|nickname\|created_at` |
| `private/preferences.txt` | `user_preference` | `user_id\|spicy_level\|price_min\|price_max\|max_distance` |
| `private/user_allergies.txt` | `user_allergy` | `user_id\|allergy_id\|severity` |
| `private/categories.txt` | `user_category` | `user_id\|category` |
| `private/favorites.txt` | `favorite` | `id\|user_id\|restaurant_id\|menu_id\|created_at` |
| `private/history.txt` | `history` | `id\|user_id\|menu_id\|group_id\|type\|created_at` |
| `private/groups.txt` | `dining_group` | `id\|name\|owner_id\|invite_code\|status\|created_at` |
| `private/group_members.txt` | `group_member` | `id\|group_id\|user_id\|guest_name\|latitude\|longitude\|joined_at` |
| `private/group_member_allergies.txt` | `group_member_allergy` | `member_id\|allergy_id` |
| `private/votes.txt` | `group_vote` | `candidate_id\|member_id\|voted_at` |

ENUM 값도 DB와 같게 씁니다.

| 필드 | 허용 값 |
|------|---------|
| `history.type` | `RECOMMENDED` · `EATEN` · `VIEWED` · `BLOCKED` |
| `dining_group.status` | `OPEN` · `VOTING` · `CLOSED` |
| `user_allergy.severity` | `1`(거의 없음) ~ `5`(극도로 높음), 기본 `3` |
| `menu_allergy.risk_level` | `CONTAINS`(함유) · `POSSIBLE`(혼입 가능) · `UNKNOWN`(불명) |

예시 (`data/public/allergy.txt` — 마법사가 만드는 기본 내용):

```text
우유
계란
메밀
땅콩
대두
밀
호두
잣
새우
게
오징어
조개류
고등어
복숭아
토마토
돼지고기
쇠고기
닭고기
아황산류
```

`allergy.txt`·`mood.txt`를 뺀 나머지 `public/` 파일(음식 사전·재료·가게·메뉴 등)과
`private/`의 파일들은 **빈 파일로** 만들어집니다.

### DB 모드와의 관계

| | MySQL 사용 | `data/` 사용 |
|--|------------|--------------|
| 준비 | MySQL + Connector/J | 폴더·파일만 있으면 됨 |
| 앱 공통 데이터 | DB에 INSERT | `data/public/`에 마스터 파일 생성 |
| 개인·팀 서버 데이터 | 테이블에 저장 | `data/private/`에 개인·그룹·투표 파일 저장 |
| 여러 기기 공유 | 공용 DB면 가능 | `public/`은 저장소로 공유, `private/`는 해당 PC만 |

나중에 MySQL을 켜고 마법사를 다시 실행하면 DB·테이블을 이어서 만들 수 있습니다.
`data/`에 쌓인 내용을 DB로 옮기는 마이그레이션은 **별도 작업**입니다.

> 📌 현재 **앱 본체는 `data/`를 런타임에 읽지 않습니다.** DB에서 직접 조회합니다.
> `data/`를 쓰는 것은 Setup Wizard와 `MenuGenImporter`(공공데이터 가져오기 도구)입니다.

<br>

## 파일 구성

**폴더가 곧 역할**입니다. 바깥은 화면, `core/`는 처리부입니다.

```
src/main/java/com/safefood/setup/
├── SetupWizard.java            # 콘솔 화면 (진입점)
├── SetupWizardFx.java          # 창(JavaFX) 화면
├── SetupWizardFxLauncher.java  # 창 화면의 IntelliJ ▶ 진입점
├── Ui.java                     # 콘솔 출력·입력 (기본값, y/n, 비밀번호 가리기)
└── core/                       # 화면 없이 도는 처리부
    ├── SetupService.java       # 3~7단계 실행. 두 화면이 함께 씁니다
    ├── SetupRequest.java       # 실행에 필요한 값 묶음 (화면 → 처리)
    ├── SetupResult.java        # 실행 결과 (처리 → 화면)
    ├── SetupReporter.java      # 진행 상황을 화면에 알리는 통로
    ├── DbConfig.java           # 접속 정보 + JDBC URL 조립
    ├── Schema.java             # 테이블 DDL과 기본 데이터
    ├── DatabaseInitializer.java   # DB·테이블 생성, 데이터 삽입, 오류 해석
    ├── DataStoreInitializer.java  # data/ 폴더·기본 파일 생성
    ├── ConfigFileWriter.java      # 설정 파일·.gitignore 처리
    ├── JdbcDriverSetup.java       # Maven을 안 쓸 때의 드라이버 확보
    └── package-info.java

src/main/resources/com/safefood/setup/
└── setup-wizard.css            # 창 화면 스타일
```

### 왜 나눴나

**의존 방향이 한쪽으로만 흐르게** 하기 위해서입니다.

```
com.safefood.setup (화면)  ──→  com.safefood.setup.core (처리)
```

`core/`는 화면을 알지 못합니다. `System.out`도 JavaFX도 쓰지 않고, 진행 상황은
`SetupReporter`로만 넘깁니다. 그래서 **콘솔판과 창판이 같은 코드를 그대로 씁니다.**
전에는 이 규칙이 "지키기로 한 약속"이었지만, 이제는 **패키지가 갈려 있어 컴파일러가 막아 줍니다.**

`core/`에서 화면 쪽을 import 하고 싶어졌다면 설계가 잘못된 신호입니다.
필요한 값은 `SetupRequest`에 담아 넘기고, 알릴 것은 `SetupReporter`로 보내세요.

### core/에서 열어 둔 것

화면이 직접 부르는 것만 `public`입니다.

| 클래스 | 화면에서 쓰는 곳 |
|--------|-----------------|
| `SetupService` | 3~7단계 실행 |
| `SetupRequest` · `SetupResult` | 값 전달 · 결과 받기 |
| `SetupReporter` | 화면이 구현해서 넘김 |
| `DbConfig` | 접속 정보 담기 |
| `DatabaseInitializer` | 연결 테스트(2단계), 오류 해석 |
| `ConfigFileWriter` | `findProjectRoot()`, `configPath()` |
| `JdbcDriverSetup` | 드라이버 확인(1단계) |

`Schema`와 `DataStoreInitializer`는 `core/` 안에서만 쓰므로 열어 두지 않았습니다.
**새 클래스를 추가할 때도 화면이 직접 부르는 것만 `public`으로 두세요.**

<br>

## 스키마를 고칠 때

같은 스키마가 **세 곳**에 있습니다. 하나만 고치면 다음 사람이 틀린 쪽을 믿게 됩니다.

| 위치 | 성격 |
|------|------|
| `setup/core/Schema.java` | 실제로 실행되는 코드 |
| README의 **데이터베이스 스키마** 절 | 사람이 읽는 문서 |
| 데이터베이스 설계서 (테이블 정의서 · 부록 A) | 설계 기준 |

마스터 목록(알레르기·기분 태그)은 `data/public/`의 기본 파일도 `Schema.java`에서 가져다 쓰므로
**따로 맞출 필요가 없습니다.** 다만 이미 만들어진 `allergy.txt`·`mood.txt`는 덮어쓰지 않으니,
목록을 바꿨다면 해당 파일을 지우고 마법사를 다시 실행하세요.

테이블 컬럼을 바꿨다면 `core/DataStoreInitializer.java`의 필드 순서 주석과
위 [저장 규칙](#저장-규칙)의 표도 같이 고쳐 주세요.

모든 DDL이 `CREATE TABLE IF NOT EXISTS`라서 **이미 만들어진 테이블의 구조는 바뀌지 않습니다.**
컬럼을 추가·변경했다면 직접 반영해야 합니다.

```sql
-- 방법 1) 해당 테이블만 지우고 마법사 재실행 (다른 테이블 데이터는 유지)
DROP TABLE menu_allergy;

-- 방법 2) 처음부터 다시 (⚠️ 데이터가 전부 사라집니다)
DROP DATABASE safefood;
```

> 마법사는 **어떤 것도 지우지 않습니다.** 삭제는 위처럼 직접 실행하세요.
> `data/private/`를 초기화하려면 해당 파일을 지운 뒤 마법사(또는 앱)를 다시 실행하면 됩니다.

<br>

## 이미 DB를 만든 팀원이라면 (v2.0 스키마)

데이터베이스 설계서 v2.0에서 컬럼 3개와 ENUM 값 1개가 늘었습니다.
**이전에 마법사를 돌려 테이블이 이미 있는 팀원**은 마법사를 다시 실행해도
`CREATE TABLE IF NOT EXISTS`가 건너뛰므로 새 컬럼이 생기지 않습니다.
아래를 한 번 실행해 주세요. (처음 설치하는 팀원은 그냥 마법사만 실행하면 됩니다)

```sql
USE safefood;

ALTER TABLE user_allergy
    ADD COLUMN severity TINYINT NOT NULL DEFAULT 3;

ALTER TABLE restaurant
    ADD COLUMN rating       DECIMAL(2,1) NULL,
    ADD COLUMN review_count INT NULL DEFAULT 0;

ALTER TABLE history
    MODIFY COLUMN type ENUM('RECOMMENDED', 'EATEN', 'VIEWED', 'BLOCKED') NOT NULL;
```

| 변경 | 왜 필요한가 |
|------|-------------|
| `user_allergy.severity` | 프로필 화면(SC-07)의 심각도 Class 1~5를 저장할 곳이 없었음 |
| `restaurant.rating` · `review_count` | 즐겨찾기 화면(SC-06-b)의 ★ 평점·리뷰 수를 저장할 곳이 없었음 |
| `history.type`에 `BLOCKED` | 기록 화면(SC-06)의 '차단됨' 상태에 대응하는 값이 없었음 |

내가 어느 쪽인지 확인하려면:

```sql
SHOW COLUMNS FROM user_allergy LIKE 'severity';
```

결과가 비어 있으면 위 ALTER를 실행해야 합니다.

> `data/` 파일 모드를 쓰고 있었다면 ALTER는 필요 없습니다.
> 다만 새로 생긴 `user_allergies.txt`·`categories.txt`·`group_member_allergies.txt`가
> 없으므로, 마법사를 한 번 더 실행하면 빈 파일로 만들어 줍니다.
> 기존 `restaurant.txt`에 데이터를 넣어 뒀다면 뒤에 `rating`·`review_count` 두 칸을 더해 주세요.

<br>

## 자주 나오는 문제

**`MySQL Connector/J를 클래스패스에서 찾지 못했습니다`**
드라이버 jar가 등록되지 않았습니다. DB를 쓸 거라면 위 [준비물](#준비물) 안내대로 추가하세요.
지금은 DB 없이 가도 된다면 설정 파일만 만들고, 데이터는 `data/`에 두세요.

**`계정 또는 비밀번호가 맞지 않습니다` (1045)**
MySQL 계정·비밀번호를 확인하세요. 설치할 때 정한 root 비밀번호입니다.
연결을 포기하면 DB 단계를 건너뛰고 `data/` 모드로 갈 수 있습니다.

**`MySQL 서버에 연결하지 못했습니다`**
서버가 꺼져 있거나 포트가 다릅니다. Windows는 `services.msc`에서 MySQL 서비스 상태를 확인하세요.
당장 MySQL이 필요 없다면 연결을 건너뛰고 `data/`에 정보를 저장하면 됩니다.

**`이 계정에는 해당 데이터베이스를 다룰 권한이 없습니다` (1044)**
DB를 만들 권한이 있는 계정(보통 root)으로 다시 실행하세요.

**`data/` 폴더가 없어요 / 파일이 비어 있어요**
마법사 또는 앱을 한 번 실행해 기본 파일을 만들게 하세요.
`private/`의 파일들은 원래 빈 파일로 만들어집니다. (아직 쌓인 데이터가 없어서 정상입니다)
프로젝트 루트(README가 있는 폴더)에서 실행했는지 확인하세요.

**비밀번호가 화면에 그대로 보여요**
IntelliJ 실행창은 `System.console()`을 지원하지 않아 가릴 수 없습니다.
터미널에서 실행하거나, **창 화면(`mvn javafx:run -Pwizard-fx`)을 쓰면** 항상 가려집니다.

**`오류: 이 애플리케이션을 실행하는 데 필요한 JavaFX 런타임 구성요소가 누락되었습니다`**
IntelliJ ▶ 버튼으로 `SetupWizardFx`를 직접 실행했을 때 납니다.
`SetupWizardFxLauncher`를 대신 실행하세요. (`mvn javafx:run -Pwizard-fx`도 됩니다)

JavaFX jar가 없어서가 아닙니다. JVM은 **메인 클래스가 `Application`을 상속하면**
JavaFX가 *모듈 경로*(`--module-path`)에 있는지 검사하는데, IntelliJ ▶ 버튼은
의존성을 *클래스패스*(`-classpath`)에 올리기 때문입니다.
`SetupWizardFxLauncher`는 `Application`을 상속하지 않아 이 검사를 지나갑니다.
앱 GUI도 같은 이유로 `MainLauncher`라는 시작 클래스를 따로 두고 있습니다.

**`Unsupported JavaFX configuration: classes were loaded from 'unnamed module'` 경고가 떠요**
클래스패스로 JavaFX를 쓸 때 나오는 정상적인 경고입니다. 그냥 두면 됩니다.
`mvn javafx:run -Pwizard-fx`로 실행하면 나오지 않습니다.

**창 화면에서 [설치 시작]을 눌렀는데 아무 반응이 없어요**
DB 작업은 백그라운드에서 돌아가 창이 멈추지 않습니다. 진행 막대와 로그를 확인하세요.
연결이 안 되는 주소를 넣으면 MySQL이 시간 초과될 때까지(수십 초) 기다릴 수 있습니다.
미리 [연결 테스트] 버튼으로 확인하는 편이 빠릅니다.

<br>

## 주의

- `config.properties`는 **절대 커밋하지 마세요.** 팀원과는 `.example` 파일로 공유합니다.
- `data/private/`는 **커밋하지 마세요.** 개인 데이터와 팀 서버 데이터가 들어갑니다.
- `data/public/`은 앱에 필요한 공통 데이터라 팀과 공유해도 됩니다.
- 실수로 커밋했다면 커밋만 지우는 것으로는 부족합니다. **비밀번호·API 키를 새로 발급**하세요.
- 생성되는 JDBC URL의 `useSSL=false`는 로컬 개발 전용입니다. 실제 서버에 올린다면 제거하세요.

<br>
<br>

---

# 👥 그룹 추천

> 그룹 대기실에서 **투표 후보 3개**가 어떻게 정해지는지 — 조건 병합부터 알레르기 판정, 후보 부족 처리까지

기능 ID `G-03`(조건 병합) · `G-05`(후보 추천). 병합 규칙의 기준은
[README — 그룹 추천 조건 병합 규칙](../README.md#그룹-추천-조건-병합-규칙)입니다.

<br>

## 구성 파일

| 파일 | 역할 |
|------|------|
| [`Room.java`](../src/main/java/com/safefood/network/Room.java) | 전원 READY 감지 → 조건 병합 → 후보 발송 (방장 앱 안의 방 상태) |
| [`GroupRecommendService.java`](../src/main/java/com/safefood/service/GroupRecommendService.java) | 병합 조건으로 필터·점수·후보 선정 |
| [`GroupMenuDao.java`](../src/main/java/com/safefood/dao/GroupMenuDao.java) | 메뉴·가게·알레르기 조회 (SQL 1회) |

개인 추천(`RecommendService`)과 파일을 나눈 이유는 **입력이 다르기** 때문입니다.
개인 추천은 회원 PK로 DB에서 취향과 알레르기 심각도를 꺼내 쓰지만, 그룹은 소켓으로 모인
병합 조건만 가지고 판단합니다 — **게스트 참여자는 회원 PK도 심각도 기록도 없습니다.**

<br>

## 전체 흐름

```
참여자: 조건 확인 화면에서 준비 완료
   → INFO|알레르기,목록|맵기|예산   (GroupConditionController)
   → READY
방장 앱(Room): 전원 READY 확인
   → 조건 병합 → MERGED 브로드캐스트          (즉시)
   → 별도 스레드에서 후보 조회                 (DB 왕복)
   → CANDIDATES + VOTE_STATUS 브로드캐스트
```

### 조건 병합

| 조건 | 병합 방식 | 비고 |
|------|-----------|------|
| 알레르기 | **합집합** | 이름 기준. 한 명이라도 못 먹으면 후보에서 제외 |
| 매운맛 | **최솟값** | 가장 약한 사람 기준 |
| 예산 | **최솟값** | `0`은 "제한없음"이라 병합에서 무시 |

알레르기를 ID가 아니라 **이름**으로 다루는 이유는 `INFO` 메시지가 이름만 싣기 때문입니다.
게스트는 `user_allergy` 행이 없어 ID를 붙일 수 없습니다.

### 조회를 락 밖으로 뺀 이유

`startRecommendation()`은 `synchronized markReady()` 안에서 불립니다. 여기서 원격 DB를
기다리면 그동안 **다른 참여자의 채팅·투표·퇴장이 전부 막힙니다.**
그래서 병합 결과(`MERGED`)만 락 안에서 즉시 보내고, 조회는 데몬 스레드로 넘긴 뒤
결과가 오면 `applyCandidates()`가 다시 락을 잡습니다.

`recommending` 플래그는 조회가 도는 동안 들어온 늦은 `READY`가 조회를 겹쳐 돌리지 않게 막습니다.

<br>

## 알레르기 판정 — 3단 조회

메뉴 하나의 알레르기 정보를 아래 순서로 찾습니다. `GroupMenuDao`의 SQL 한 방에 들어 있습니다.

| 단계 | 조건 | 출처 | 판정 |
|------|------|------|------|
| 1 | `menu_allergy`에 행이 있음 | 수기 등록 | `CURATED` — 기록된 `CONTAINS` / `POSSIBLE` 그대로 |
| 2 | 행이 없고 `menu.food_id`가 있음 | 공공데이터 사전 | `DERIVED` — 유도된 알레르기를 **`CONTAINS`로 취급** |
| 3 | 둘 다 없음 | — | `NONE` — 정보 없음 |

2단의 유도 경로:

```
menu.food_id → food_ingredient → ingredient_allergy → allergy
```

### 2단을 CONTAINS로 기록하는 이유

`CONTAINS`와 `POSSIBLE`은 **둘 다 제외**라서 필터 결과는 같지만, 유도된 값은
음식의 실제 재료 구성에서 나온 것이라 `POSSIBLE`(제조 과정 혼입)보다 `CONTAINS`(원재료 포함)에
가깝습니다. 나중에 두 등급을 다르게 다루게 되더라도 의미가 어긋나지 않게 기록해 둡니다.
후보 카드 사유에는 `재료 기반 추정`을 붙여 수기 등록과 구분합니다.

### 3단이 필요한 이유

정보가 없는 메뉴를 **안전한 것으로 통과시키면 안 됩니다.** `NONE`인 메뉴는
그룹에 알레르기가 하나라도 등록돼 있으면 `-40점` + 정렬상 후순위로 밀고,
사유에 `알레르기 정보 없음 — 주문 전 직접 확인 필요`를 붙입니다.
제외까지 하지는 않습니다 — "모른다"가 "위험하다"는 아니고, 제외하면 후보 고갈이 심해집니다.

> ⚠️ **유도 0건 ≠ 안전.** 재료 1,388개 중 알레르기가 매핑된 건 424개(31%)뿐입니다.
> 매핑이 없는 재료만 든 음식은 유도 결과가 비어 나오는데, 이건 `NONE`과 같이 다룹니다.

> ⚠️ **"확인했는데 알레르기 없음"은 현재 스키마로 표현할 수 없습니다.**
> 기록할 행이 없어 `NONE`과 구분되지 않습니다. 필요해지면 `menu_allergy`에
> `risk_level = 'NONE'` 행을 넣는 규칙을 정해야 합니다.

<br>

## 점수와 후보 선정

### 필터

| 대상 | 처리 |
|------|------|
| `CONTAINS` ∩ 알레르기 합집합 | **제외.** 어떤 경우에도 완화하지 않습니다 |
| `POSSIBLE` ∩ 알레르기 합집합 | **제외.** 혼입 가능성도 감점이 아니라 탈락으로 다룹니다 |
| 매운맛 초과 · 예산 초과 | 탈락이 아니라 **후순위로 밀기** |

`POSSIBLE`을 감점이 아니라 제외로 둔 이유는, 그룹에는 참여자별 심각도가 없어서
"이 사람에게 얼마나 위험한가"를 점수로 환산할 근거가 없기 때문입니다.
감점으로 두면 후보가 부족할 때 혼입 가능 메뉴가 그대로 투표에 올라옵니다.

실측상 비용도 작습니다 — 혼입 가능 알레르기는 대개 이미 `CONTAINS`로 걸린 메뉴에 붙어 있어서,
제외로 바꿔도 후보 수는 거의 그대로입니다.

| 제외 조건 | 감점이었을 때 | 제외로 바꾼 뒤 |
|-----------|---------------|----------------|
| 대두 | 10개 | 9개 |
| 대두 · 밀 | 2개 | 2개 |
| 대두 · 밀 · 돼지고기 · 계란 · 새우 | 1개 | 1개 |

매운맛·예산을 탈락시키지 않는 이유는 후보 부족 대비입니다. 조건을 지킨 메뉴가 3개 이상이면
초과 메뉴는 절대 뽑히지 않고, 모자랄 때만 사유(`조건에 맞는 후보가 모자라 포함했어요`)를
붙여 채웁니다. README의 **조건 완화 안내**에 해당합니다.

### 점수

| 항목 | 값 |
|------|-----|
| 기본 점수 | `100` |
| 가게 평점 가산 | `rating × 4` (최대 `+20`) |
| 예산 여유 가산 | 예산의 80% 이하면 `+5` |
| 알레르기 정보 없음 감점 | `-40` |

알레르기는 점수에 반영되지 않습니다. 걸리면 제외, 아니면 만점 — 중간이 없습니다.
개인 추천(`RecommendService`)이 `severity × 10`으로 감점하는 것과 다른 부분이고,
그룹에는 참여자별 심각도가 없기 때문입니다.

### 정렬과 다양성

```
조건 준수 여부 → 점수 → 평점 → 가격(싼 것) → 메뉴 id
```

마지막 `메뉴 id`는 **같은 입력이면 항상 같은 순서**를 보장하기 위한 것입니다.

정렬 뒤 **가게당 1개씩** 먼저 채우고, 그래도 3개가 안 되면 남은 것으로 채웁니다.
한 가게가 후보 3개를 독점하면 투표의 의미가 없어지기 때문입니다.

<br>

## 후보가 안 나올 때

`recommend()`는 후보와 함께 **훑은 메뉴 수**를 돌려주고, `Room`이 이걸로 세 경우를 가릅니다.

| 상황 | 판단 | 처리 |
|------|------|------|
| 훑은 메뉴 `0`개 | DB 미연결 또는 메뉴 데이터 없음 | 대체 목록(하드코딩 3개)으로 대기실 흐름 유지 |
| 훑은 메뉴 있음, 후보 `0`개 | 알레르기로 전부 제외 | **대체 목록 금지.** 빈 후보 + 안내 메시지 |
| 그 외 | 정상 | 후보 3개 발송 |

> 🔴 두 번째 경우에 대체 목록을 쓰면 **알레르기가 가장 빡빡한 그룹에게 하필
> 검증 안 된 하드코딩 메뉴가 나갑니다.** 방향이 정확히 거꾸로라서 반드시 갈라야 합니다.

매운맛·예산은 이미 "후순위로 밀기"라서 후보를 0개로 만들 수 없습니다.
즉 **후보 0개의 원인은 항상 알레르기**이고, 안내 문구도 그에 맞춰 나갑니다 —
"매운맛·예산을 완화해 보세요"는 이 상황에서 도움이 되지 않는 잘못된 안내입니다.

<br>

## 프로토콜 주의

`CANDIDATES` 메시지는 한 줄짜리 CSV입니다.

```
CANDIDATES|1.김치찌개(할머니손맛),2.제육볶음(백반집),3.비빔밥(한그릇)
```

메뉴·가게 이름에 `|` `,` `(` `)`가 들어 있으면 받는 쪽 파싱이 어긋납니다.
하드코딩 목록일 때는 없던 문제지만 DB 이름은 `짜장면(곱빼기)`처럼 들어올 수 있어,
`Room.protocolSafe()`가 보내기 전에 걷어냅니다.

<br>

## 재추천 시점

**새 참여자가 준비를 마치면 추천이 다시 돕니다.** 들어오는 순간이 아니라 `READY` 시점입니다.

새 참여자는 `ready = false`로 들어오므로 전원 READY 조건이 한 번 깨졌다가,
그 사람이 준비를 마치면 다시 성립해 `startRecommendation()`이 재실행됩니다.
막는 건 최종 확정(`resultMessage`)뿐입니다.

재실행되면 **그때까지의 표가 전부 초기화됩니다.** 늦게 온 사람의 알레르기를 반영해야 하니
안전 측면에서는 맞는 동작이지만, 화면에는 후보가 새로 왔다는 것만 뜨고 표가 왜 사라졌는지는
안내되지 않습니다.

> ⚠️ 반대 방향(**나가기**)은 재추천되지 않습니다. `leave()`는 표만 정리하고 조건 병합은
> 건드리지 않아서, 맵찔이가 나가도 매운맛 기준은 그대로 낮게 남습니다.

<br>

## 현재 데이터 기준 실측

2026-08 기준 팀 DB 상태입니다.

### menu_allergy 커버리지 — 빈틈 없음

| 항목 | 값 |
|------|-----|
| 메뉴 / 가게 | 29개 / 9곳 |
| `menu_allergy` 행 수 | 108 (메뉴당 평균 3.7건) |
| 알레르기 표시가 있는 메뉴 | **29 / 29 (100%)** |
| ↳ `CONTAINS` 보유 | 29 / 29 |
| ↳ `POSSIBLE` 보유 | 12 / 29 |
| 표시가 아예 없는 메뉴 | **0** |

### food 사전 연결 — 아직 안 됨

| 항목 | 값 |
|------|-----|
| `food_id`가 채워진 메뉴 | **0 / 29** |
| 사전 음식 수 | 3,250 |
| 알레르기가 유도되는 음식 | 2,459 (76%) |
| 알레르기 매핑된 재료 | 424 / 1,388 (31%) |

**2단(DERIVED)은 현재 한 번도 실행되지 않습니다.** 커버리지가 100%라 1단에서 다 끝나고,
`food_id`도 비어 있기 때문입니다. 공공데이터에서 메뉴를 대량으로 가져올 때를 위한 대비입니다.

유도 결과 표본:

```
짜장면        재료 11개 → 돼지고기, 밀
돈가스        재료  8개 → 계란, 대두, 돼지고기, 밀
비빔밥(고추장)  재료 11개 → 계란, 대두, 밀
해물김치찌개    재료 14개 → 대두, 조개류
```

### 후보 고갈 — 실제 위험

알레르기 합집합이 커지면 후보가 빠르게 말라붙습니다. 대두·밀이 각각 29개 중 19개(66%)에 걸립니다.

| 제외 조건 | 남은 후보 |
|-----------|-----------|
| 대두 | 9개 |
| 대두 · 밀 | 2개 |
| 대두 · 밀 · 돼지고기 · 계란 | 2개 |
| 대두 · 밀 · 돼지고기 · 계란 · 새우 | 1개 |
| 대두 · 밀 · 돼지고기 · 계란 · 새우 · 토마토 | **0개** |

참여자 3~4명이면 이 구간에 쉽게 들어갑니다. 메뉴 29개로는 근본 해결이 안 되고,
**메뉴 데이터 확충이 가장 효과적인 대응**입니다.

<br>

## 단독 실행

앱을 띄우지 않고 추천 로직만 확인할 수 있습니다.

```bash
mvn -q compile
```

컴파일 후 `GroupRecommendService`의 `main`을 실행하면 조건별 후보와
`훑은 메뉴 / 알레르기로 제외 / 후보` 개수가 출력됩니다.
`config.properties`를 읽으므로 **프로젝트 루트에서** 실행하세요.

<br>

## 앞으로

- [ ] 재추천 시 표 초기화 안내 — 지금은 아무 설명 없이 표가 사라집니다
- [ ] 참여자 퇴장 시 조건 재병합 — 나간 사람의 제약이 계속 남습니다
- [ ] `menu.food_id` 매핑 — 2단(DERIVED)이 실제로 동작하려면 필요합니다
- [ ] 확정 결과를 `group_candidate` · `group_vote`에 저장 — 현재 방 상태는 방장 앱 메모리에만 있습니다
- [ ] 선호 카테고리 병합 — README에는 규칙이 있지만 `INFO`가 아직 싣지 않습니다

<br>
<br>

---

# 📦 배포 — 실행 파일과 설치 파일

> 자바가 없는 PC에서도 돌아가는 **SafeFood.exe**와, 일반 앱처럼 설치되는 **설치 마법사** 만들기

결과물은 두 가지입니다. 팀 내부에서 빠르게 돌려볼 때는 폴더, 다른 사람에게 전달할 때는 설치 파일을 쓰세요.

| | 명령 | 결과물 | 크기 |
|---|------|--------|------|
| **폴더** | `mvn clean package -Pexe` | `target/dist/SafeFood/` | 약 165MB |
| **설치 파일** | `mvn clean package -Pexe,installer` | `target/installer/SafeFood-1.0.exe` | 약 67MB |

<br>

## 빌드 준비물

- **JDK 21** — `jpackage`는 JDK에만 들어 있고 JRE에는 없습니다
- **WiX Toolset v3** — 설치 파일을 만들 때만 필요합니다

```bash
winget install WiXToolset.WiXToolset
```

> WiX 설치 관리자는 의존성으로 **Windows 기능 `NetFx3`(.NET Framework 3.5)를 함께 켭니다.**
> WiX v4 이상은 `jpackage`(JDK 21)가 지원하지 않으니 **v3**를 설치하세요.

WiX는 `C:\Program Files (x86)\WiX Toolset v3.14`에 설치되고 `WIX` 환경 변수만 남기는데,
`jpackage`는 `candle.exe`·`light.exe`를 **PATH에서** 찾습니다.
그래서 `pom.xml`의 `installer` 프로파일이 실행 시점에만 PATH를 붙여 줍니다.

```xml
<environmentVariables>
    <PATH>${env.PATH};${env.WIX}bin</PATH>
</environmentVariables>
```

시스템 PATH를 건드리지 않으므로, WiX를 같은 방법으로 설치한 팀원이면 추가 설정 없이 그대로 됩니다.

<br>

## 폴더 배포 (`-Pexe`)

```
target/dist/SafeFood/
├── SafeFood.exe          ← 실행 파일
├── app/                  ← safefood.jar + 의존성 jar + 실행 설정
├── runtime/              ← 전용 자바 런타임 (jlink로 추린 것)
└── config.properties     ← 직접 넣어야 합니다
```

압축해서 전달하면 받는 사람은 자바 설치 없이 `SafeFood.exe`를 더블클릭하면 됩니다.
대신 바로가기도, 제거 기능도 없습니다. `target/`은 `.gitignore` 대상이라 저장소에는 안 들어갑니다.

<br>

## 설치 파일 (`-Pexe,installer`)

`SafeFood-1.0.exe` **한 개 파일**만 전달하면 됩니다. 받는 사람이 더블클릭하면
일반 앱과 똑같은 설치 마법사가 뜹니다.

`exe` 프로파일이 만든 폴더를 `jpackage --app-image`로 그대로 감싸므로,
앱을 두 번 빌드하지 않습니다.

### 설치 마법사가 하는 일

| 항목 | 내용 |
|------|------|
| 설치 위치 | `%LOCALAPPDATA%\SafeFood` — 마법사에서 변경 가능 (`--win-dir-chooser`) |
| 관리자 권한 | **필요 없습니다** (`--win-per-user-install`) |
| 시작 메뉴 | `SafeFood` 그룹에 바로가기 생성 |
| 바탕화면 | 설치 중 체크박스로 선택 (`--win-shortcut-prompt`) |
| 설정 > 앱 | `SafeFood 1.0 / SafeFood Team`으로 등록 — 여기서 제거 |
| 업그레이드 | `app.upgrade.uuid`가 같으면 기존 버전을 덮어씁니다 |

바로가기의 **시작 위치**는 설치 폴더로 잡힙니다. 앱이 `config.properties`를
현재 작업 디렉터리에서 찾기 때문에 이 설정이 필요합니다.

### 제거

**설정 > 앱**에서 SafeFood를 찾아 제거합니다.
설치 폴더 · 시작 메뉴 · 바탕화면 바로가기 · 등록 정보가 모두 지워집니다.

<br>

## ⚠️ config.properties는 직접 넣어야 합니다

**빌드도 설치 마법사도 자동으로 넣지 않습니다.** DB 계정과 비밀번호가 들어 있어서,
결과물에 자동으로 포함하면 그대로 외부로 나갈 수 있기 때문입니다.

설치본을 쓸 때는 설치 폴더에 넣습니다.

```
C:\Users\<사용자>\AppData\Local\SafeFood\config.properties
```

폴더 배포를 쓸 때는 `SafeFood.exe`와 같은 자리에 둡니다.

```bash
copy config.properties target\dist\SafeFood\
```

> 결과물을 외부에 공유하기 전에 `config.properties`가 들어 있는지 **반드시 확인하세요.**

<br>

## 🛡️ SmartScreen 경고

설치 파일에 **코드 서명이 없어서** 처음 실행할 때 Windows가 경고를 띄웁니다.

> Windows의 PC 보호 — 인식할 수 없는 앱의 실행을 차단했습니다

**추가 정보 → 실행**을 누르면 진행됩니다. 이 경고를 없애려면 코드 서명 인증서가 필요한데
유료라서(연 10만원대~) 수업 프로젝트 범위에서는 경고를 안내하는 쪽이 현실적입니다.

받는 사람에게 미리 알려 주세요.

<br>

## 🔥 처음 실행할 때

그룹 방을 만들면 소켓 서버가 열립니다(`config.properties`의 `socket.port`, 기본 5000).
**Windows 방화벽 허용 창**이 뜨는데, 같은 네트워크의 팀원이 접속하려면 허용해야 합니다.

<br>

## 빌드 구조

### `exe` 프로파일 — 폴더 만들기

| 순서 | 플러그인 | 하는 일 |
|------|----------|---------|
| 1 | `maven-clean-plugin` | 이전 `target/dist`·`target/app` 삭제 (jpackage는 대상 폴더가 있으면 실패) |
| 2 | `maven-jar-plugin` | `safefood.jar`를 `target/app`에 생성 |
| 3 | `maven-dependency-plugin` | 런타임 의존성 jar를 `target/app`에 복사 |
| 4 | `exec-maven-plugin` | `jpackage --type app-image` 실행 → `target/dist` |

`target/app`의 jar가 전부 클래스패스에 올라갑니다. fat jar(shade)를 만들지 않는 이유는
JavaFX jar의 `module-info.class`가 병합 과정에서 문제를 일으키기 때문입니다.

### `installer` 프로파일 — 설치 파일 만들기

`jpackage --type exe --app-image`로 `exe` 프로파일의 결과물을 감쌉니다.
WiX가 MSI를 만들고, jpackage가 그걸 실행 파일로 포장합니다.

### 설정 값

앱 이름·버전·시작 클래스는 `pom.xml` 위쪽 `properties`에서 바꿉니다.

```xml
<app.name>SafeFood</app.name>
<app.version>1.0</app.version>
<app.main.class>com.safefood.MainLauncher</app.main.class>
<app.upgrade.uuid>4BB53F4D-B659-49F4-A2B0-A4B545CA2E9B</app.upgrade.uuid>
```

`app.main.class`가 `Main`이 아니라 `MainLauncher`인 이유는, `Application`을 상속한 클래스를
직접 시작 클래스로 지정하면 JavaFX가 모듈 경로를 요구하기 때문입니다.
`MainLauncher`는 `Application.launch(Main.class, args)`만 호출하는 우회용 클래스입니다.

> ⚠️ **`app.upgrade.uuid`는 절대 바꾸지 마세요.** 이 값이 같아야 새 버전이 기존 설치를
> 덮어씁니다. 바꾸면 1.0과 1.1이 따로 깔립니다.

> 📌 **MSI는 코드 페이지 1252라 한글을 못 넣습니다.** `--description`에 한글을 쓰면
> `light.exe`가 `311`로 실패합니다. 그래서 설명만 영문으로 두었습니다.

<br>

## 오류 로그 보기

기본 설정은 **콘솔 창이 뜨지 않습니다.** GUI 앱이라 검은 창이 같이 뜨면 보기 안 좋기 때문인데,
대신 `System.err` 출력(예: `[group] 추천 후보 조회 실패`)이 어디에도 남지 않습니다.

디버깅용으로 콘솔이 필요하면 `exe` 프로파일의 jpackage 인자에 한 줄 추가하세요.

```xml
<argument>--win-console</argument>
```

> 실행 중 아래 경고가 뜨는 것은 정상입니다. 클래스패스 방식으로 JavaFX를 올려서 나오는 안내입니다.
>
> ```
> Unsupported JavaFX configuration: classes were loaded from 'unnamed module ...'
> ```

<br>

## 아이콘

`data/public/icon/safefood.ico`를 씁니다. 같은 폴더의 `logo.png`(339×343)를
256·128·64·48·32·16 여섯 크기로 담아 만든 파일이고, 저장소에 커밋돼 있습니다.

아이콘을 바꾸려면 이 `.ico` 파일을 교체하면 됩니다.
`jpackage`는 Windows에서 `.ico`만 받습니다 — PNG를 그대로 넘기면 빌드가 실패합니다.

<br>

## 한계

- **Windows 전용입니다.** `jpackage`는 실행한 OS용 결과물만 만듭니다.
  macOS·Linux용은 해당 OS에서 따로 빌드해야 합니다.
- **Setup Wizard는 포함되지 않습니다.** 본 앱(`MainLauncher`)만 들어갑니다.
  DB와 `config.properties` 준비는 개발 환경에서 미리 끝내야 합니다.
- **코드 서명이 없어 SmartScreen 경고가 뜹니다.**
- 앱은 `data/` 폴더를 런타임에 읽지 않아서 결과물에 포함하지 않습니다.

<br>

## 검증 기록

2026-08-24 기준, 아래를 실제로 확인했습니다.

- `mvn clean package -Pexe,installer` → `SafeFood-1.0.exe` 67.1MB 생성
- 설치 → `%LOCALAPPDATA%\SafeFood`에 배치, 시작 메뉴 `SafeFood` 그룹과 바탕화면 바로가기 생성
  (시작 위치가 설치 폴더로 지정됨), 설정 > 앱에 `SafeFood 1.0 / SafeFood Team`으로 등록
- 설치된 `SafeFood.exe` 실행 → 로그인 창 정상 표시
- 제거 → 설치 폴더 · 시작 메뉴 · 바탕화면 바로가기 · 등록 정보 모두 삭제 확인
