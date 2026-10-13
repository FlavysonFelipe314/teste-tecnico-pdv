FROM maven:3.9.9-eclipse-temurin-21@sha256:3a4ab3276a087bf276f79cae96b1af04f53731bec53fb2e651aca79e4b10211e AS build
WORKDIR /src
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
ENV MAVEN_OPTS="-Xmx384m"
COPY src src
RUN mvn -B -q -DargLine=-Xmx384m verify
FROM eclipse-temurin:21-jre@sha256:cff19e6215689161eb6162c11b86b0c60ddf802164f2eaf48d570f8fb79a36c5
WORKDIR /app
COPY --from=build /src/target/rota-clara-core-1.0.0.jar app.jar
ENV JAVA_TOOL_OPTIONS="-Xmx512m"
ENTRYPOINT ["java","-jar","app.jar"]
