FROM eclipse-temurin:21-jdk-alpine AS builder

WORKDIR /labriel

COPY /src ./src
COPY /gradle ./gradle

COPY gradlew .
COPY build.gradle.kts .
COPY gradle.properties .
COPY settings.gradle.kts .

RUN ./gradlew shadowJar --no-daemon

FROM eclipse-temurin:21-jdk-alpine
RUN mkdir -p data

COPY --from=builder /labriel/build/libs/labriel.jar /labriel.jar

ENTRYPOINT ["java", "-jar", "labriel.jar"]
