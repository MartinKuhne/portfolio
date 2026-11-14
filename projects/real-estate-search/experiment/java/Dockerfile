FROM maven:3.9-eclipse-temurin-21 AS builder

WORKDIR /build

COPY pom.xml .
RUN mvn dependency:go-offline -B

COPY src ./src
RUN mvn package -B

FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

RUN addgroup -S appgroup && adduser -S appuser -G appgroup

COPY --from=builder /build/target/real-estate-api-1.0.0.jar /app/real-estate-api.jar
RUN chown -R appuser:appgroup /app

USER appuser

ENV PORT=4000
EXPOSE 4000

ENTRYPOINT ["java", "-jar", "/app/real-estate-api.jar"]
