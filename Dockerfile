# 운영 이미지 (EC2 배포용)
# 빌드: docker build -t justinneed-be .
# 실행: docker run -d --name justinneed-be --env-file /etc/justinneed/prod.env -p 127.0.0.1:8080:8080 justinneed-be
#   - 운영 값(DB, JWT, OAuth 키, FE 도메인)은 --env-file 로 주입한다. .env 는 이미지에 넣지 않는다.
#   - 8080 은 127.0.0.1 에만 바인딩하고 외부 노출은 Nginx(HTTPS) 가 담당한다.
#   - Apple Silicon 에서 빌드해 x86 EC2 에 올릴 때는 --platform linux/amd64 를 붙인다.

# 1단계: bootJar 빌드
FROM eclipse-temurin:17-jdk AS build
WORKDIR /workspace

# 의존성 레이어를 소스와 분리해 소스만 바뀌면 의존성 다운로드를 건너뛴다
COPY gradlew settings.gradle build.gradle ./
COPY gradle gradle
RUN chmod +x gradlew && ./gradlew dependencies --no-daemon > /dev/null

COPY src src
RUN ./gradlew bootJar --no-daemon -x test

# 2단계: 실행 (JRE 만 포함)
FROM eclipse-temurin:17-jre
WORKDIR /app

RUN groupadd --system app && useradd --system --gid app app
COPY --from=build /workspace/build/libs/*.jar app.jar
USER app

ENV SPRING_PROFILES_ACTIVE=prod
# 힙을 고정하지 않으면 JVM 이 RAM 의 1/4 을 잡아 같은 VM 의 분석 서비스(Python)와 경합한다
ENV JAVA_OPTS="-Xmx512m"

EXPOSE 8080
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
