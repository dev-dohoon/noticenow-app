package noticenow;

import java.util.List;

// 감시할 사이트 하나의 정보를 담는 클래스
public class MonitoredSite {
    private final String name;
    private final String url;
    // 이 사이트에서 마지막으로 확인된 공지사항 제목 목록
    private List<String> lastTitles;

    public MonitoredSite(String name, String url) {
        this.name = name;
        this.url = url;
        this.lastTitles = null; // 처음에는 null로 초기화
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