package noticenow;

import java.util.List;

public class MonitoredSite {
    private String name;
    private String url;
    private List<String> lastTitles;

    // Gson 라이브러리가 JSON 변환 시 기본 생성자를 사용하므로 추가해줍니다.
    public MonitoredSite() {}

    public MonitoredSite(String name, String url) {
        this.name = name;
        this.url = url;
    }

    // Getters and Setters
    public String getName() {
        return name;
    }

    public String getUrl() {
        return url;
    }

    public List<String> getLastTitles() {
        return lastTitles;
    }

    public void setLastTitles(List<String> lastTitles) {
        this.lastTitles = lastTitles;
    }
}