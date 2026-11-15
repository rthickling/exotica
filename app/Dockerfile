FROM eclipse-temurin:21-jdk@sha256:0a3f329821dcadf54b2a3033a15e2e9f007069a9db43d7f51897e6b80e726b07 AS jdk
FROM jdk AS development
WORKDIR /repo/app
ENV GRADLE_USER_HOME=/repo/.gradle HOME=/tmp
ENTRYPOINT ["sh", "./gradlew", "--no-daemon"]
CMD ["clean", "test"]

FROM jdk AS build
WORKDIR /build
COPY app/gradlew app/settings.gradle.kts app/build.gradle.kts ./
COPY app/gradle gradle
RUN sh ./gradlew --no-daemon --version
COPY app/src src
RUN sh ./gradlew --no-daemon bootJar
FROM eclipse-temurin:21-jre@sha256:18bb4a91a3dea3f03ee31d3755af34dfb40f904fc77ab56cdc2f092e32fff9c2
WORKDIR /app
COPY --from=build /build/build/libs/structured-products-lab-1.0.0.jar /app/app.jar
COPY fixtures/terms /fixtures/terms
ENV TERMS_DIRECTORY=/fixtures/terms
EXPOSE 8080
ENTRYPOINT ["java","-jar","/app/app.jar"]
