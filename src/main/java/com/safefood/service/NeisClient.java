package com.safefood.service;

import com.safefood.AppConfig;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
import java.io.StringReader;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 나이스(NEIS) 교육정보 개방 포털 호출 — 학교 검색과 급식 식단.
 *
 * <p>응답 형식은 <b>XML</b>을 씁니다. JSON 을 다루려면 라이브러리를 새로 들여야 하는데,
 * 이 프로젝트는 이미 {@code MenuGenImporter} 에서 공공데이터 XML 을 표준 DOM 으로 읽고 있어
 * 의존성 없이 같은 방식을 쓸 수 있습니다. 캐시에 <b>원문을 그대로</b> 저장하는 이점은 형식과 무관합니다.
 *
 * <p><b>인증키</b>는 {@code config.properties} 의 {@code neis.api.key} 입니다.
 *
 * <h3>세 가지 응답을 구분합니다</h3>
 * <ul>
 *   <li>{@code INFO-000} — 정상. 행이 들어 있습니다</li>
 *   <li>{@code INFO-200} — <b>해당 데이터 없음. 오류가 아닙니다.</b> 주말·방학이거나 학교가
 *       아직 안 올린 것입니다 (학교 절반이 5일치만 올려 둡니다)</li>
 *   <li>그 밖 ({@code ERROR-*}, 네트워크 오류) — {@link NeisException} 으로 올립니다.
 *       못 불러온 것을 '알레르기 없음'으로 보이게 하면 안 되기 때문입니다</li>
 * </ul>
 */
public class NeisClient {

    private static final String SCHOOL_ENDPOINT = "https://open.neis.go.kr/hub/schoolInfo";
    private static final String MEAL_ENDPOINT = "https://open.neis.go.kr/hub/mealServiceDietInfo";

    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final DateTimeFormatter YMD = DateTimeFormatter.ofPattern("yyyyMMdd");

    /** 학교 검색 한 건. */
    public record School(String atptCode, String atptName, String schulCode, String schulName, String kind) {
    }

    /** 급식 조회 결과 원문. {@code hasData} 가 false 면 그 기간에 등록된 급식이 없다는 뜻입니다. */
    public record MealResponse(String body, boolean hasData) {
    }

    /** 급식 하루치 원문 한 줄. */
    public record MealRow(LocalDate date, String mealType, String rawDish) {
    }

    /** 나이스를 부르지 못했거나 오류를 돌려받았을 때. '데이터 없음'과는 다릅니다. */
    public static class NeisException extends RuntimeException {
        public NeisException(String message) {
            super(message);
        }

        public NeisException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();

    /** 키가 채워져 있는지 — 화면이 급식 메뉴를 열어 줄지 정할 때 씁니다. */
    public static boolean hasApiKey() {
        return !apiKey().isEmpty();
    }

    private static String apiKey() {
        return AppConfig.trimmed("neis.api.key", "");
    }

    // ---- 학교 검색 ----

    /**
     * 학교 이름으로 검색합니다.
     *
     * <p>표준학교코드가 비어 있는 행(아직 개교하지 않은 {@code (가칭)…} 학교)은 걸러 냅니다 —
     * 그 코드로는 급식을 조회할 수 없습니다.
     */
    public List<School> searchSchools(String name) {
        if (name == null || name.isBlank()) {
            return List.of();
        }
        String url = SCHOOL_ENDPOINT + "?KEY=" + encode(requireKey())
                + "&Type=xml&pIndex=1&pSize=50&SCHUL_NM=" + encode(name.trim());

        Document doc = parseXml(get(url));
        if (isNoData(doc)) {
            return List.of();
        }
        requireOk(doc);

        List<School> schools = new ArrayList<>();
        for (Element row : elements(doc.getDocumentElement(), "row")) {
            String schulCode = text(row, "SD_SCHUL_CODE");
            if (schulCode == null || schulCode.isBlank()) {
                continue;
            }
            schools.add(new School(
                    text(row, "ATPT_OFCDC_SC_CODE"),
                    text(row, "ATPT_OFCDC_SC_NM"),
                    schulCode,
                    text(row, "SCHUL_NM"),
                    text(row, "SCHUL_KND_SC_NM")));
        }
        return List.copyOf(schools);
    }

    // ---- 급식 ----

    /**
     * 한 달치 급식을 받아 원문 그대로 돌려줍니다.
     *
     * <p>호출 단위를 한 달로 두는 이유: 일주일치나 한 달치나 호출은 1회고 응답도 23KB 남짓이라,
     * 한 번 받아 두면 부모가 여러 주를 오가도 다시 부르지 않습니다.
     */
    public MealResponse fetchMonth(String atptCode, String schulCode, YearMonth month) {
        LocalDate from = month.atDay(1);
        LocalDate to = month.atEndOfMonth();
        String url = MEAL_ENDPOINT + "?KEY=" + encode(requireKey())
                + "&Type=xml&pIndex=1&pSize=100"
                + "&ATPT_OFCDC_SC_CODE=" + encode(atptCode)
                + "&SD_SCHUL_CODE=" + encode(schulCode)
                + "&MLSV_FROM_YMD=" + from.format(YMD)
                + "&MLSV_TO_YMD=" + to.format(YMD);

        String body = get(url);
        Document doc = parseXml(body);
        if (isNoData(doc)) {
            return new MealResponse(body, false);
        }
        requireOk(doc);
        return new MealResponse(body, true);
    }

    /**
     * 저장해 둔 원문이든 방금 받은 원문이든, 급식 행으로 읽어 냅니다.
     * 형식이 깨졌으면 빈 목록입니다 — 캐시가 상했을 뿐 부를 곳은 남아 있습니다.
     */
    public static List<MealRow> readRows(String xmlBody) {
        if (xmlBody == null || xmlBody.isBlank()) {
            return List.of();
        }
        Document doc;
        try {
            doc = parseXml(xmlBody);
        } catch (NeisException e) {
            return List.of();
        }
        if (isNoData(doc) || !doc.getDocumentElement().getTagName().equals("mealServiceDietInfo")) {
            return List.of();
        }

        List<MealRow> rows = new ArrayList<>();
        for (Element row : elements(doc.getDocumentElement(), "row")) {
            String ymd = text(row, "MLSV_YMD");
            if (ymd == null || ymd.length() != 8) {
                continue;
            }
            try {
                rows.add(new MealRow(
                        LocalDate.parse(ymd, YMD),
                        text(row, "MMEAL_SC_NM"),
                        text(row, "DDISH_NM")));
            } catch (RuntimeException ignored) {
                // 날짜가 이상한 행은 건너뜁니다 — 나머지는 그대로 보여 줍니다
            }
        }
        rows.sort((a, b) -> a.date().compareTo(b.date()));
        return List.copyOf(rows);
    }

    // ---- 내부 ----

    private static String requireKey() {
        String key = apiKey();
        if (key.isEmpty()) {
            throw new NeisException("config.properties 의 neis.api.key 가 비어 있습니다.");
        }
        return key;
    }

    private String get(String url) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(TIMEOUT).GET().build();
        try {
            HttpResponse<String> response =
                    http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200) {
                throw new NeisException("나이스 응답 오류 (HTTP " + response.statusCode() + ")");
            }
            return response.body();
        } catch (IOException e) {
            throw new NeisException("나이스에 연결하지 못했습니다: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new NeisException("조회가 중단되었습니다.", e);
        }
    }

    private static Document parseXml(String body) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setExpandEntityReferences(false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            return builder.parse(new InputSource(new StringReader(body)));
        } catch (Exception e) {
            throw new NeisException("나이스 응답을 읽지 못했습니다.", e);
        }
    }

    /** 루트가 {@code RESULT} 이고 코드가 INFO-200 이면 '데이터 없음'입니다. */
    private static boolean isNoData(Document doc) {
        Element root = doc.getDocumentElement();
        return "RESULT".equals(root.getTagName()) && "INFO-200".equals(text(root, "CODE"));
    }

    /** 정상 응답이 아니면 예외로 올립니다. */
    private static void requireOk(Document doc) {
        Element root = doc.getDocumentElement();
        if ("RESULT".equals(root.getTagName())) {
            throw new NeisException(text(root, "MESSAGE") + " (" + text(root, "CODE") + ")");
        }
        for (Element head : elements(root, "head")) {
            for (Element result : elements(head, "RESULT")) {
                String code = text(result, "CODE");
                if (code != null && !code.startsWith("INFO-0")) {
                    throw new NeisException(text(result, "MESSAGE") + " (" + code + ")");
                }
            }
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    private static List<Element> elements(Element parent, String tag) {
        List<Element> result = new ArrayList<>();
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node instanceof Element element && tag.equals(element.getTagName())) {
                result.add(element);
            }
        }
        return result;
    }

    /** 바로 아래 자식 태그의 글. CDATA 도 그대로 읽힙니다. */
    private static String text(Element parent, String tag) {
        for (Element child : elements(parent, tag)) {
            String value = child.getTextContent();
            return value == null ? null : value.trim();
        }
        return null;
    }
}
