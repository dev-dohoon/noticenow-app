package noticenow;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

// 서버의 핵심 로직을 담당하는 메인 클래스
public class NoticeServer {

    // 모든 사용자 데이터를 메모리에 저장하는 저장소 (Key: 학번)
    private static final Map<String, UserData> userDataStore = new ConcurrentHashMap<>();
    // 실시간 알림(SSE)을 위한 클라이언트 연결 저장소 (Key: 학번)
    private static final Map<String, PrintWriter> sseClients = new ConcurrentHashMap<>();

    public static void main(String[] args) throws IOException {
        // 서버가 시작되면 1분마다 모든 사이트를 확인하는 백그라운드 작업 시작
        startBackgroundChecker();

        // 8080 포트로 HTTP 서버 생성
        HttpServer server = HttpServer.create(new InetSocketAddress(8080), 0);

        // 각 API 경로에 맞는 핸들러(담당자) 지정
        server.createContext("/", new StaticFileHandler()); // 기본 HTML 페이지 제공
        server.createContext("/api/login", new LoginHandler()); // 로그인 처리
        server.createContext("/api/add-site", new AddSiteHandler()); // 사이트 추가 처리
        server.createContext("/api/delete-site", new DeleteSiteHandler()); // 사이트 삭제 처리 (오타 수정!)
        server.createContext("/api/events", new SseHandler()); // 실시간 알림 연결 처리

        server.setExecutor(Executors.newCachedThreadPool()); // 동시 요청 처리를 위함
        server.start();

        System.out.println("✅ 서버가 http://localhost:8080 에서 시작되었습니다.");
        System.out.println("Cloudtype 배포 주소로 접속해주세요.");
    }

    // 백그라운드에서 주기적으로 모든 사용자의 모든 사이트를 확인하는 메소드
    private static void startBackgroundChecker() {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        scheduler.scheduleAtFixedRate(NoticeServer::checkAllSites, 1, 1, TimeUnit.MINUTES);
    }

    // 모든 사이트를 확인하는 실제 로직
    private static void checkAllSites() {
        System.out.println("🔄 백그라운드 확인 작업 시작...");
        userDataStore.forEach((studentId, userData) -> {
            userData.getSites().forEach(site -> {
                try {
                    Document doc = Jsoup.connect(site.getUrl()).get();
                    Elements currentTitles = doc.select("tr > td:nth-child(2)");
                    List<String> newTitlesList = new ArrayList<>();
                    for (Element titleElement : currentTitles) {
                        newTitlesList.add(titleElement.text());
                    }

                    List<String> oldTitles = site.getLastTitles();

                    // 최초 확인 시에는 현재 상태만 저장하고 알림은 보내지 않음
                    if (oldTitles == null) {
                        site.setLastTitles(newTitlesList);
                        return; // 다음 사이트로 넘어감
                    }

                    // 새로운 제목만 필터링
                    List<String> addedTitles = new ArrayList<>(newTitlesList);
                    addedTitles.removeAll(oldTitles);

                    if (!addedTitles.isEmpty()) {
                        System.out.printf("🚨 새 공지 발견! [%s] %s%n", studentId, site.getName());
                        addedTitles.forEach(newTitle -> {
                            String notificationJson = String.format(
                                    "{\"siteName\": \"%s\", \"title\": \"%s\"}",
                                    site.getName().replace("\"", "\\\""),
                                    newTitle.replace("\"", "\\\"")
                            );
                            // 웹페이지에 실시간 알림 전송
                            sendSseEvent(studentId, notificationJson);
                        });
                    }
                    // 확인이 끝나면 현재 제목 목록을 마지막 상태로 저장
                    site.setLastTitles(newTitlesList);

                } catch (IOException e) {
                    System.err.printf("❌ 사이트 확인 중 오류 [%s]: %s%n", site.getName(), e.getMessage());
                }
            });
        });
    }

    // 연결된 웹페이지(클라이언트)에 실시간 알림(SSE)을 보내는 메소드
    private static void sendSseEvent(String studentId, String data) {
        PrintWriter writer = sseClients.get(studentId);
        if (writer != null) {
            writer.write("data: " + data + "\n\n");
            writer.flush();
            System.out.printf("✅ SSE 이벤트 전송 완료 [%s]%n", studentId);
        }
    }

    // --- 각 API 경로를 처리하는 핸들러 클래스들 ---

    // index.html 파일을 서비스하는 핸들러
    static class StaticFileHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            // 서버 내부 리소스에서 index.html 파일을 읽어옴 (가장 중요한 변경점!)
            try (InputStream inputStream = NoticeServer.class.getResourceAsStream("/index.html")) {
                if (inputStream == null) {
                    sendResponse(exchange, 404, "{\"error\":\"index.html 파일을 찾을 수 없습니다.\"}");
                    return;
                }
                exchange.sendResponseHeaders(200, 0);
                try (OutputStream os = exchange.getResponseBody()) {
                    inputStream.transferTo(os);
                }
            }
        }
    }

    // /api/login 요청을 처리하는 핸들러
    static class LoginHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            Map<String, String> params = queryToMap(exchange.getRequestURI());
            String studentId = params.get("studentId");

            if (studentId == null || studentId.trim().isEmpty()) {
                sendResponse(exchange, 400, "{\"error\": \"학번을 입력해주세요.\"}");
                return;
            }
            // 해당 학번의 사용자가 없으면 새로 만들고, 있으면 기존 정보를 가져옴
            UserData userData = userDataStore.computeIfAbsent(studentId, k -> new UserData());
            sendResponse(exchange, 200, convertSitesToJson(userData.getSites()));
        }
    }

    // /api/add-site 요청을 처리하는 핸들러
    static class AddSiteHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            Map<String, String> params = queryToMap(exchange.getRequestURI());
            String studentId = params.get("studentId");
            String siteName = params.get("siteName");
            String siteUrl = params.get("siteUrl");

            UserData userData = userDataStore.get(studentId);
            if (userData == null) {
                sendResponse(exchange, 403, "{\"error\": \"로그인이 필요합니다.\"}");
                return;
            }
            if (userData.getSites().size() >= 3) {
                sendResponse(exchange, 400, "{\"error\": \"사이트는 최대 3개까지 등록할 수 있습니다.\"}");
                return;
            }
            userData.addSite(new MonitoredSite(siteName, siteUrl));
            sendResponse(exchange, 200, convertSitesToJson(userData.getSites()));
        }
    }

    // /api/delete-site 요청을 처리하는 핸들러
    static class DeleteSiteHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            Map<String, String> params = queryToMap(exchange.getRequestURI());
            String studentId = params.get("studentId");
            String siteUrl = params.get("siteUrl");

            UserData userData = userDataStore.get(studentId);
            if (userData == null) {
                sendResponse(exchange, 403, "{\"error\": \"로그인이 필요합니다.\"}");
                return;
            }
            userData.removeSite(siteUrl);
            sendResponse(exchange, 200, convertSitesToJson(userData.getSites()));
        }
    }

    // /api/events 실시간 연결을 처리하는 핸들러
    static class SseHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=UTF-8");
            exchange.getResponseHeaders().set("Cache-Control", "no-cache");
            exchange.getResponseHeaders().set("Connection", "keep-alive");
            exchange.sendResponseHeaders(200, 0);

            Map<String, String> params = queryToMap(exchange.getRequestURI());
            String studentId = params.get("studentId");

            OutputStream os = exchange.getResponseBody();
            PrintWriter writer = new PrintWriter(new OutputStreamWriter(os, StandardCharsets.UTF_8), true);
            sseClients.put(studentId, writer);
            System.out.printf("✅ SSE 클라이언트 연결: %s%n", studentId);

            try {
                // 클라이언트가 연결을 끊을 때까지 대기
                exchange.getRequestBody().readAllBytes();
            } catch (IOException e) {
                // 연결이 비정상적으로 끊어졌을 때
            } finally {
                sseClients.remove(studentId);
                System.out.printf("❌ SSE 클라이언트 연결 종료: %s%n", studentId);
            }
        }
    }

    // --- 유틸리티 메소드 (코드 재사용을 위함) ---
    private static Map<String, String> queryToMap(URI uri) {
        Map<String, String> result = new HashMap<>();
        String query = uri.getQuery();
        if (query != null) {
            for (String param : query.split("&")) {
                String[] pair = param.split("=");
                if (pair.length > 1) {
                    result.put(URLDecoder.decode(pair[0], StandardCharsets.UTF_8), URLDecoder.decode(pair[1], StandardCharsets.UTF_8));
                }
            }
        }
        return result;
    }

    private static void sendResponse(HttpExchange exchange, int statusCode, String json) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        byte[] jsonBytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(statusCode, jsonBytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(jsonBytes);
        }
    }

    private static String convertSitesToJson(List<MonitoredSite> sites) {
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < sites.size(); i++) {
            MonitoredSite site = sites.get(i);
            json.append(String.format("{\"name\":\"%s\",\"url\":\"%s\"}",
                    site.getName().replace("\"", "\\\""),
                    site.getUrl().replace("\"", "\\\"")));
            if (i < sites.size() - 1) {
                json.append(",");
            }
        }
        json.append("]");
        return json.toString();
    }
}