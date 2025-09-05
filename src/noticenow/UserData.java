package noticenow;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

// 한 명의 사용자에 대한 모든 데이터를 담는 클래스
public class UserData {
    // 사용자가 등록한 사이트 목록
    private final List<MonitoredSite> sites = new CopyOnWriteArrayList<>();

    // 사이트 목록을 반환하는 메소드
    public List<MonitoredSite> getSites() {
        return sites;
    }

    // 새 사이트를 추가하는 메소드
    public void addSite(MonitoredSite site) {
        // 이미 등록된 URL인지 확인
        boolean alreadyExists = sites.stream().anyMatch(s -> s.getUrl().equals(site.getUrl()));
        if (!alreadyExists) {
            sites.add(site);
        }
    }

    // URL을 기준으로 사이트를 삭제하는 메소드
    public void removeSite(String url) {
        sites.removeIf(site -> Objects.equals(site.getUrl(), url));
    }
}