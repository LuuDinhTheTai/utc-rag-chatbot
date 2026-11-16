FROM maven:3.9-eclipse-temurin-21 AS build
RUN apt-get update && apt-get install -y --no-install-recommends fonts-dejavu-core && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY pom.xml .
RUN mvn -B dependency:go-offline
COPY src ./src
RUN mvn -B package

FROM eclipse-temurin:21-jre-alpine
RUN apk add --no-cache font-dejavu
WORKDIR /app
RUN addgroup -S app && adduser -S app -G app
RUN mkdir -p /app/uploads && chown app:app /app/uploads
COPY --from=build /app/target/app.jar app.jar
USER app
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
