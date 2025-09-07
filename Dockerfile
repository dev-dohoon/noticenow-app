# 1. Java와 Maven이 모두 설치된 컴퓨터를 준비합니다.
FROM maven:3.9-eclipse-temurin-21

# 2. 서버 컴퓨터 내부에 /app 이라는 작업 폴더를 만듭니다.
WORKDIR /app

# 3. 먼저 설계도(pom.xml)를 복사합니다.
COPY pom.xml .

# 4. 설계도를 보고 필요한 부품(라이브러리)들을 미리 다운로드합니다.
RUN mvn dependency:go-offline

# 5. 나머지 모든 소스 코드와 리소스 파일들을 복사합니다.
COPY src ./src

# 6. Maven으로 모든 코드를 조립하여 하나의 실행 파일(.jar)로 만듭니다.
RUN mvn package

# 7. 우리 서버가 사용하는 8080번 문을 열어둡니다.
EXPOSE 8080

# 8. 최종적으로 조립된 실행 파일을 실행시킵니다.
CMD ["java", "-jar", "target/NoticeNow-1.0-jar-with-dependencies.jar"]
