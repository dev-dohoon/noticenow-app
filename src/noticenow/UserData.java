package noticenow;

import java.util.ArrayList;
import java.util.List;

public class UserData {
    // transient 키워드는 이 필드를 JSON으로 변환할 때 제외하라는 의미입니다.
    // Firebase를 사용하지 않으므로 이제 필요 없습니다.
    // private transient String fcmToken;
    private List<MonitoredSite> sites;

    public UserData() {
        this.sites = new ArrayList<>();
    }

    public List<MonitoredSite> getSites() {
        return sites;
    }

    public void addSite(MonitoredSite site) {
        if (this.sites.size() < 3) {
            this.sites.add(site);
        }
    }

    public void removeSite(String url) {
        this.sites.removeIf(site -> site.getUrl().equals(url));
    }
}

