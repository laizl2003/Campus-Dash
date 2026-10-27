# 应用镜像：app 与 worker 共用一个镜像，靠不同的 ENTRYPOINT 决定跑哪个 jar。
# jar 由宿主机 mvn package 产出（fat jar 里已经包含全部依赖），
# 所以这里没有构建阶段，镜像只做"搬运 + 启动"。
FROM eclipse-temurin:21-jre

ENV TZ=Asia/Shanghai
WORKDIR /app

COPY dash-bootstrap/target/dash-bootstrap-1.0.0-SNAPSHOT.jar /app/app.jar
COPY dash-worker/target/dash-worker-1.0.0-SNAPSHOT.jar /app/worker.jar

EXPOSE 8080

# MaxRAMPercentage 让 JVM 按容器内存自适应，避免 -Xmx 写死后被 cgroup 杀掉
ENTRYPOINT ["java","-XX:MaxRAMPercentage=70","-jar","/app/app.jar"]
