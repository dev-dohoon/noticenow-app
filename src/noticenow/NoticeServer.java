package noticenow;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class NoticeServer {

    private static final Map<String, UserData> allUsersData = new ConcurrentHashMap<>();
    // SSE 연결을 관리하는 저장소 (Key: 학번, Value: 연결 객체)
    private static final Map<String, HttpExchange> sseClients = new ConcurrentHashMap<>();

    public static void main(String[] args) throws IOException {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        // 테스트를 위해 주기를 1분으로 단축합니다.
        scheduler.scheduleAtFixedRate(NoticeServer::checkAllSites, 0, 1, TimeUnit.MINUTES);

        HttpServer server = HttpServer.create(new InetSocketAddress(8080), 0);
        server.createContext("/", new RootHandler());
        server.createContext("/api/login", new LoginHandler());
        server.createContext("/api/add-site", new AddSiteHandler());
        server.createContext("/api/delete-site", new DeleteSiteHandler());
        // 실시간 알림을 위한 SSE 경로 추가
        server.createContext("/api/events", new EventsHandler());
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();

        System.out.println("✅ 서버가 http://localhost:8080 에서 시작되었습니다.");
        System.out.println("🕒 1분마다 모든 등록된 사이트를 백그라운드에서 확인합니다.");
    }

    private static void checkAllSites() {
        System.out.println("\n[" + new Date() + "] 🔍 전체 사이트 백그라운드 확인 시작...");
        allUsersData.forEach((studentId, userData) -> {
            userData.getMonitoredSites().forEach(site -> {
                try {
                    Document doc = Jsoup.connect(site.getSiteUrl()).get();
                    Set<String> currentTitles = new HashSet<>();
                    doc.select("tbody tr").forEach(tr -> {
                        Element titleTd = tr.select("td:nth-of-type(2)").first();
                        if (titleTd != null) currentTitles.add(titleTd.text());
                    });

                    Set<String> previousTitles = site.getLastKnownTitles();
                    if (!previousTitles.isEmpty()) {
                        Set<String> newTitles = new HashSet<>(currentTitles);
                        newTitles.removeAll(previousTitles);

                        if (!newTitles.isEmpty()) {
                            System.out.printf("🚨 새 공지 발견! 학번: %s, 사이트: %s\n", studentId, site.getSiteName());
                            newTitles.forEach(title -> {
                                System.out.println("   - " + title);
                                // ✨ 실시간 알림을 보내는 로직 호출 ✨
                                sendSseEvent(studentId, site.getSiteName(), title);
                            });
                        }
                    }
                    site.setLastKnownTitles(currentTitles);
                } catch (IOException e) {
                    System.err.printf("❌ 오류! 학번: %s, 사이트: %s, 원인: %s\n", studentId, site.getSiteName(), e.getMessage());
                }
            });
        });
        System.out.println("✅ 백그라운드 확인 완료.");
    }

    /**
     * SSE 클라이언트에게 실시간으로 데이터를 보내는 메소드
     */
    private static void sendSseEvent(String studentId, String siteName, String newTitle) {
        HttpExchange exchange = sseClients.get(studentId);
        if (exchange != null) {
            try {
                // SSE 데이터 형식: "data: {json}\n\n"
                String jsonData = String.format("{\"siteName\":\"%s\", \"title\":\"%s\"}",
                        siteName.replace("\"", "\\\""),
                        newTitle.replace("\"", "\\\""));
                String sseData = "data: " + jsonData + "\n\n";

                OutputStream os = exchange.getResponseBody();
                os.write(sseData.getBytes(StandardCharsets.UTF_8));
                os.flush(); // 데이터를 즉시 클라이언트로 보냄
                System.out.println("📨 SSE 이벤트 전송 완료 -> " + studentId);
            } catch (IOException e) {
                // 클라이언트 연결이 끊어진 경우
                System.out.println("🔌 SSE 클라이언트 연결 끊어짐: " + studentId);
                sseClients.remove(studentId);
            }
        }
    }

    // --- API 핸들러들 ---

    static class EventsHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String studentId = parseQuery(exchange.getRequestURI().getQuery()).get("studentId");
            if (studentId == null) {
                exchange.sendResponseHeaders(400, -1);
                return;
            }

            // SSE를 위한 헤더 설정
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.getResponseHeaders().set("Cache-Control", "no-cache");
            exchange.getResponseHeaders().set("Connection", "keep-alive");
            exchange.sendResponseHeaders(200, 0);

            // 클라이언트 연결을 맵에 저장 (연결을 닫지 않음!)
            sseClients.put(studentId, exchange);
            System.out.println("🔗 SSE 클라이언트 연결됨: " + studentId);
        }
    }

    static class LoginHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String studentId = parseQuery(exchange.getRequestURI().getQuery()).get("studentId");
            if (studentId == null || studentId.isBlank()) {
                sendResponse(exchange, 400, "{\"error\":\"학번을 입력해주세요.\"}");
                return;
            }
            UserData userData = allUsersData.computeIfAbsent(studentId, UserData::new);
            System.out.println("👤 사용자 로그인: " + studentId);
            sendResponse(exchange, 200, formatSitesToJson(userData.getMonitoredSites()));
        }
    }

    static class AddSiteHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            Map<String, String> params = parseQuery(exchange.getRequestURI().getQuery());
            String studentId = params.get("studentId");
            String siteName = params.get("siteName");
            String siteUrl = params.get("siteUrl");

            UserData userData = allUsersData.get(studentId);
            if (userData == null) {
                sendResponse(exchange, 403, "{\"error\":\"로그인이 필요합니다.\"}");
                return;
            }

            if (userData.addSite(siteName, siteUrl)) {
                System.out.printf("➕ 사이트 추가: %s, %s (%s)\n", studentId, siteName, siteUrl);
                userData.findSiteByUrl(siteUrl).ifPresent(site -> {
                    try {
                        Document doc = Jsoup.connect(site.getSiteUrl()).get();
                        Set<String> currentTitles = new HashSet<>();
                        doc.select("tbody tr").forEach(tr -> {
                            Element titleTd = tr.select("td:nth-of-type(2)").first();
                            if (titleTd != null) currentTitles.add(titleTd.text());
                        });
                        site.setLastKnownTitles(currentTitles);
                    } catch (IOException e) { /* 무시 */ }
                });
                sendResponse(exchange, 200, formatSitesToJson(userData.getMonitoredSites()));
            } else {
                sendResponse(exchange, 400, "{\"error\":\"사이트는 최대 3개까지만 등록할 수 있습니다.\"}");
            }
        }
    }

    static class DeleteSiteHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            Map<String, String> params = parseQuery(exchange.getRequestURI().getQuery());
            String studentId = params.get("studentId");
            String siteUrl = params.get("siteUrl");

            UserData userData = allUsersData.get(studentId);
            if (userData != null) {
                userData.removeSite(siteUrl);
                System.out.printf("➖ 사이트 삭제: %s, (%s)\n", studentId, siteUrl);
                sendResponse(exchange, 200, formatSitesToJson(userData.getMonitoredSites()));
            } else {
                sendResponse(exchange, 403, "{\"error\":\"로그인이 필요합니다.\"}");
            }
        }
    }

    static class RootHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String filePath = "index.html";
            try {
                byte[] fileBytes = Files.readAllBytes(Paths.get(filePath));
                exchange.sendResponseHeaders(200, fileBytes.length);
                OutputStream os = exchange.getResponseBody();
                os.write(fileBytes);
                os.close();
            } catch (IOException e) {
                String response = "404 Not Found: index.html 파일을 프로젝트 루트에 넣어주세요.";
                sendResponse(exchange, 404, "{\"error\":\"" + response + "\"}");
            }
        }
    }

    private static void sendResponse(HttpExchange exchange, int statusCode, String response) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        byte[] responseBytes = response.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(statusCode, responseBytes.length);
        OutputStream os = exchange.getResponseBody();
        os.write(responseBytes);
        os.close();
    }

    private static Map<String, String> parseQuery(String query) {
        Map<String, String> result = new HashMap<>();
        if (query != null) {
            for (String param : query.split("&")) {
                String[] entry = param.split("=");
                if (entry.length > 1) {
                    try {
                        String key = URLDecoder.decode(entry[0], StandardCharsets.UTF_8);
                        String value = URLDecoder.decode(entry[1], StandardCharsets.UTF_8);
                        result.put(key, value);
                    } catch (Exception e) {
                        System.err.println("URL 쿼리 파싱 오류: " + e.getMessage());
                    }
                }
            }
        }
        return result;
    }

    private static String formatSitesToJson(List<MonitoredSite> sites) {
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < sites.size(); i++) {
            MonitoredSite site = sites.get(i);
            String escapedName = site.getSiteName().replace("\\", "\\\\").replace("\"", "\\\"");
            String escapedUrl = site.getSiteUrl().replace("\\", "\\\\").replace("\"", "\\\"");
            json.append(String.format("{\"name\":\"%s\", \"url\":\"%s\"}", escapedName, escapedUrl));
            if (i < sites.size() - 1) {
                json.append(",");
            }
        }
        json.append("]");
        return json.toString();
    }
}
