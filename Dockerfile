FROM openjdk:17

WORKDIR /app

COPY . .

EXPOSE 8081

CMD ["java", "-jar", "target/FirstDB-0.0.1-SNAPSHOT.jar"]