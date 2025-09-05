# 1. 베이스 이미지로 OpenJDK 21 버전을 사용합니다.
# 우리 프로젝트가 Java 21 버전으로 만들어졌다는 의미입니다.
FROM maven:3.9-eclipse-temurin-21

# 2. 컨테이너(서버 컴퓨터) 내부에 /app 이라는 작업 폴더를 만듭니다.
WORKDIR /app

# 3. Maven 빌드를 위해 pom.xml 파일을 먼저 복사합니다.
COPY pom.xml .

# 4. Maven을 사용해 필요한 라이브러리(Jsoup, Firebase)들을 다운로드합니다.
RUN mvn dependency:go-offline

# 5. 나머지 프로젝트 소스 코드 전체를 복사합니다.
COPY . .

# 6. Maven으로 프로젝트를 빌드하여 모든 코드를 하나의 실행 파일로 묶습니다.
RUN mvn package

# 7. 우리 서버가 사용하는 8080 포트를 외부와 연결할 수 있도록 열어줍니다.
EXPOSE 8080

# 8. 최종적으로 빌드된 실행 파일을 실행하는 명령어입니다.
# 이 명령어로 NoticeServer.java가 24시간 실행됩니다.
CMD ["java", "-jar", "target/NoticeNow-1.0-jar-with-dependencies.jar"]
