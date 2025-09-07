# 1. Use a base image with Java and Maven installed
FROM maven:3.9-eclipse-temurin-21

# 2. Set the working directory inside the container
WORKDIR /app

# 3. Copy the pom.xml file to leverage Docker cache
COPY pom.xml .

# 4. Download all dependencies
RUN mvn dependency:go-offline

# 5. Copy the rest of the source code
COPY src ./src

# 6. Build the project into an executable JAR
RUN mvn package

# 7. Expose the port the server will run on
EXPOSE 8080

# 8. The command to run the application
CMD ["java", "-jar", "target/NoticeNow-1.0-jar-with-dependencies.jar"]
