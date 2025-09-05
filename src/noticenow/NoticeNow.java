package noticenow;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.io.IOException;
import java.util.HashSet;
import java.util.Scanner;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;

/**
 * NoticeNow v2
 * 사용자가 입력한 웹사이트의 공지사항 제목을 직접 비교하여
 * 새로운 공지사항이 추가되었을 때 그 내용을 알려주는 콘솔 애플리케이션입니다.
 */
public class NoticeNow {

    // 이전에 확인했던 공지사항 '제목'들을 저장하는 Set
    // Set을 사용하면 중복된 제목을 알아서 관리해줍니다.
    private static Set<String> previousTitles = null;

    public static void main(String[] args) {
        // 사용자로부터 웹사이트 URL을 입력받습니다.
        Scanner scanner = new Scanner(System.in);
        System.out.println("==============================================");
        System.out.println("공지사항을 확인할 웹사이트 주소를 입력해주세요:");
        System.out.print("URL: ");
        String url = scanner.nextLine();
        System.out.println("==============================================");
        System.out.println("[" + url + "] 사이트의 공지사항 확인을 시작합니다.");
        System.out.println("10초마다 새로운 공지사항이 있는지 확인합니다...");
        System.out.println("==============================================");

        // Timer를 사용하여 10초마다 checkWebsite 메소드를 실행합니다.
        Timer timer = new Timer();
        timer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                checkWebsite(url);
            }
        }, 0, 10000);
    }

    /**
     * 지정된 URL의 웹사이트를 확인하고 공지사항 제목의 변화를 감지하는 메소드
     * @param url 확인할 웹사이트 주소
     */
    private static void checkWebsite(String url) {
        try {
            // 1. Jsoup을 사용하여 웹사이트에 연결하고 HTML 문서를 가져옵니다.
            Document doc = Jsoup.connect(url)
                    .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36")
                    .timeout(5000)
                    .get();

            // 2. HTML 문서에서 공지사항 목록에 해당하는 <tr> 태그들을 가져옵니다.
            //    보통 공지사항 목록은 <tbody> 안에 있으므로, "tbody tr"로 더 정확하게 선택합니다.
            Elements trTags = doc.select("tbody tr");
            Set<String> currentTitles = new HashSet<>();

            // 3. 각 <tr> 태그를 순회하며 제목(두 번째 <td>)을 추출하여 Set에 저장합니다.
            for (Element tr : trTags) {
                // "td:nth-of-type(2)"는 tr 태그 안의 '두 번째' td 태그를 의미합니다.
                Elements tds = tr.select("td:nth-of-type(2)");
                if (!tds.isEmpty()) {
                    String title = tds.first().text();
                    currentTitles.add(title);
                }
            }

            // 4. 제목 Set을 비교하여 변화를 감지합니다.

            // 프로그램 첫 실행 시, 현재 상태를 기준으로 설정합니다.
            if (previousTitles == null) {
                System.out.println("[초기 설정] 현재 공지사항 " + currentTitles.size() + "개를 기준으로 확인을 시작합니다.");
                previousTitles = new HashSet<>(currentTitles); // 현재 제목들을 기준으로 저장
                return;
            }

            // 새로운 제목들을 찾기 위해 현재 제목 Set에서 이전 제목 Set을 뺍니다.
            Set<String> newTitles = new HashSet<>(currentTitles);
            newTitles.removeAll(previousTitles);

            if (!newTitles.isEmpty()) {
                // 새로운 제목이 하나 이상 있을 경우
                for (String title : newTitles) {
                    System.out.println("🚨 새로운 공지사항이 추가되었습니다! 내용 : " + title);
                }
            } else if (currentTitles.size() < previousTitles.size()) {
                // 공지사항 개수가 줄었을 경우 (삭제 감지)
                System.out.println("[알림] 일부 공지사항이 삭제된 것 같습니다.");
            }
            else {
                // 변화가 없을 경우
                System.out.println("[" + java.time.LocalTime.now().withNano(0) + "] 추가된 공지사항이 없습니다.");
            }

            // 다음 비교를 위해 현재 제목들을 이전 제목들로 업데이트합니다.
            previousTitles = new HashSet<>(currentTitles);

        } catch (IOException e) {
            System.err.println("[오류] 웹사이트에 연결할 수 없습니다: " + e.getMessage());
        } catch (Exception e) {
            System.err.println("[알 수 없는 오류] " + e.getMessage());
        }
    }
}