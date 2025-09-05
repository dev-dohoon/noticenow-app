package noticenow;

import com.google.gson.Gson;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class NoticeServer {

    private static final Gson gson = new Gson();
    private static JedisPool jedisPool;
    private static final Map<String, PrintWriter> sseClients = new ConcurrentHashMap<>();

    public static void main(String[] args) throws IOException {
        initializeRedis();
        startBackgroundChecker();

        HttpServer server = HttpServer.create(new InetSocketAddress(8080), 0);
        server.createContext("/", new StaticFileHandler());
        server.createContext("/api/login", new LoginHandler());
        server.createContext("/api/add-site", new AddSiteHandler());
        server.createContext("/api/delete-site", new DeleteSiteHandler());
        server.createContext("/api/events", new SseHandler());
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();

        System.out.println("✅ 서버가 http://localhost:8080 에서 시작되었습니다.");
        System.out.println("Cloudtype 배포 주소로 접속해주세요.");
    }

    private static void initializeRedis() {
        String redisHost = System.getenv("REDIS_HOST");
        String redisPortStr = System.getenv("REDIS_PORT");
        String redisPassword = System.getenv("REDIS_PASSWORD");

        if (redisHost == null || redisPortStr == null) {
            System.err.println("❌ Redis 환경 변수가 설정되지 않았습니다. 로컬 테스트 모드로 전환합니다.");
            redisHost = "localhost";
            redisPortStr = "6379";
            redisPassword = null;
        }

        int redisPort = Integer.parseInt(redisPortStr);
        jedisPool = new JedisPool(redisHost, redisPort, null, redisPassword);
        System.out.println("✅ Redis DB가 성공적으로 연결되었습니다.");
    }

    private static void startBackgroundChecker() {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        scheduler.scheduleAtFixedRate(NoticeServer::checkAllSites, 0, 1, TimeUnit.MINUTES);
    }

    private static void checkAllSites() {
        System.out.println("🔄 백그라운드 확인 작업 시작...");
        try (Jedis jedis = jedisPool.getResource()) {
            for (String userKey : jedis.keys("user:*")) {
                String studentId = userKey.substring(5);
                String userDataJson = jedis.get(userKey);
                UserData userData = gson.fromJson(userDataJson, UserData.class);

                boolean needsDbUpdate = false;
                for (MonitoredSite site : userData.getSites()) {
                    try {
                        Document doc = Jsoup.connect(site.getUrl()).get();
                        Elements currentTitlesElements = doc.select("tr > td:nth-child(2)");
                        List<String> newTitlesList = new ArrayList<>();
                        currentTitlesElements.forEach(el -> newTitlesList.add(el.text()));

                        List<String> oldTitles = site.getLastTitles();

                        if (oldTitles == null) {
                            site.setLastTitles(newTitlesList);
                            needsDbUpdate = true;
                            continue;
                        }

                        if (!newTitlesList.equals(oldTitles)) {
                            List<String> addedTitles = new ArrayList<>(newTitlesList);
                            addedTitles.removeAll(oldTitles);

                            if (!addedTitles.isEmpty()) {
                                System.out.printf("🚨 새 공지 발견! [%s] %s%n", studentId, site.getName());
                                for (String newTitle : addedTitles) {
                                    String time = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm"));
                                    Map<String, String> newLog = Map.of(
                                            "siteName", site.getName(),
                                            "title", newTitle,
                                            "time", time
                                    );
                                    String notificationJson = gson.toJson(newLog);
                                    sendSseEvent(studentId, notificationJson);
                                }
                            }
                            site.setLastTitles(newTitlesList);
                            needsDbUpdate = true;
                        }
                    } catch (IOException e) {
                        System.err.printf("❌ 사이트 확인 중 오류 [%s]: %s%n", site.getName(), e.getMessage());
                    }
                }
                if (needsDbUpdate) {
                    jedis.set(userKey, gson.toJson(userData));
                }
            }
        } catch (Exception e) {
            System.err.println("❌ Redis 작업 중 오류 발생: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static void sendSseEvent(String studentId, String data) {
        PrintWriter writer = sseClients.get(studentId);
        if (writer != null) {
            writer.write("data: " + data + "\n\n");
            // PrintWriter는 에러 발생 시 Exception을 던지지 않고 내부 상태만 변경함
            // checkError()로 에러 상태를 확인하여 연결 종료를 감지
            if (writer.checkError()) {
                System.out.println("❌ SSE 전송 실패, 클라이언트 연결 종료됨: " + studentId);
                sseClients.remove(studentId);
            } else {
                System.out.println("📨 SSE 이벤트 전송 완료 -> " + studentId);
            }
        }
    }

    // --- 핸들러들 ---

    static class StaticFileHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getPath();
            if (path.equals("/")) {
                path = "/index.html";
            }
            try (InputStream inputStream = NoticeServer.class.getResourceAsStream(path)) {
                if (inputStream == null) {
                    String response = "404 (Not Found)\n";
                    exchange.sendResponseHeaders(404, response.length());
                    try (OutputStream os = exchange.getResponseBody()) {
                        os.write(response.getBytes());
                    }
                } else {
                    exchange.sendResponseHeaders(200, 0);
                    try (OutputStream os = exchange.getResponseBody()) {
                        inputStream.transferTo(os);
                    }
                }
            }
        }
    }

    static class LoginHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            Map<String, String> params = queryToMap(exchange.getRequestURI().getRawQuery());
            String studentId = params.get("studentId");
            String userKey = "user:" + studentId;
            UserData userData;

            try (Jedis jedis = jedisPool.getResource()) {
                String userDataJson = jedis.get(userKey);
                if (userDataJson == null) {
                    userData = new UserData();
                    jedis.set(userKey, gson.toJson(userData));
                } else {
                    userData = gson.fromJson(userDataJson, UserData.class);
                }
            }
            sendJsonResponse(exchange, 200, gson.toJson(userData.getSites()));
        }
    }

    static class AddSiteHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            Map<String, String> params = queryToMap(exchange.getRequestURI().getRawQuery());
            String studentId = params.get("studentId");
            String siteName = params.get("siteName");
            String siteUrlB64 = params.get("siteUrlB64");
            String userKey = "user:" + studentId;

            try (Jedis jedis = jedisPool.getResource()) {
                String userDataJson = jedis.get(userKey);
                if (userDataJson == null) {
                    sendJsonResponse(exchange, 403, "{\"error\":\"로그인 정보가 없습니다.\"}");
                    return;
                }
                UserData userData = gson.fromJson(userDataJson, UserData.class);
                if (userData.getSites().size() >= 3) {
                    sendJsonResponse(exchange, 400, "{\"error\":\"사이트는 최대 3개까지 등록 가능합니다.\"}");
                    return;
                }
                String siteUrl = new String(Base64.getDecoder().decode(siteUrlB64), StandardCharsets.UTF_8);
                userData.addSite(new MonitoredSite(siteName, siteUrl));
                jedis.set(userKey, gson.toJson(userData));
                sendJsonResponse(exchange, 200, gson.toJson(userData.getSites()));
            }
        }
    }

    static class DeleteSiteHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            Map<String, String> params = queryToMap(exchange.getRequestURI().getRawQuery());
            String studentId = params.get("studentId");
            String siteUrlB64 = params.get("siteUrlB64");
            String userKey = "user:" + studentId;

            try (Jedis jedis = jedisPool.getResource()) {
                String userDataJson = jedis.get(userKey);
                if (userDataJson == null) {
                    sendJsonResponse(exchange, 403, "{\"error\":\"로그인 정보가 없습니다.\"}");
                    return;
                }
                UserData userData = gson.fromJson(userDataJson, UserData.class);
                String siteUrl = new String(Base64.getDecoder().decode(siteUrlB64), StandardCharsets.UTF_8);
                userData.removeSite(siteUrl);
                jedis.set(userKey, gson.toJson(userData));
                sendJsonResponse(exchange, 200, gson.toJson(userData.getSites()));
            }
        }
    }

    // ✨ 최종 수정된 SseHandler ✨
    static class SseHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            Map<String, String> params = queryToMap(exchange.getRequestURI().getRawQuery());
            String studentId = params.get("studentId");
            if (studentId == null || studentId.isBlank()) {
                exchange.sendResponseHeaders(400, -1); // Bad Request
                return;
            }

            exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=UTF-8");
            exchange.getResponseHeaders().set("Cache-Control", "no-cache");
            exchange.getResponseHeaders().set("Connection", "keep-alive");
            exchange.sendResponseHeaders(200, 0);

            PrintWriter writer = new PrintWriter(exchange.getResponseBody(), true, StandardCharsets.UTF_8);
            sseClients.put(studentId, writer);
            System.out.println("✅ SSE 클라이언트 연결됨: " + studentId);

            // 클라이언트가 연결을 끊을 때까지 이 핸들러가 종료되지 않도록 대기합니다.
            // 클라이언트가 브라우저를 닫으면 getRequestBody()에서 IOException이 발생합니다.
            try (InputStream is = exchange.getRequestBody()) {
                while (is.read() != -1) {
                    // Do nothing, just block to keep connection alive
                }
            } catch (IOException e) {
                // This is expected when the client disconnects
            } finally {
                sseClients.remove(studentId);
                System.out.println("❌ SSE 클라이언트 연결 종료: " + studentId);
            }
        }
    }

    private static Map<String, String> queryToMap(String query) {
        Map<String, String> result = new HashMap<>();
        if (query == null || query.isEmpty()) {
            return result;
        }
        for (String param : query.split("&")) {
            String[] pair = param.split("=", 2);
            if (pair.length > 1) {
                result.put(URLDecoder.decode(pair[0], StandardCharsets.UTF_8), URLDecoder.decode(pair[1], StandardCharsets.UTF_8));
            } else {
                result.put(URLDecoder.decode(pair[0], StandardCharsets.UTF_8), "");
            }
        }
        return result;
    }

    private static void sendJsonResponse(HttpExchange exchange, int statusCode, String json) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        byte[] jsonBytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(statusCode, jsonBytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(jsonBytes);
        }
    }
}
