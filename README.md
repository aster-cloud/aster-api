# Quarkus Policy API 部署指南

## 1. 概述
Quarkus Policy API 提供策略管理、GraphQL 查询与工作流执行能力，基于 **Quarkus 3.28.3** 构建，并使用 **GraalVM Native Image** 提供低时延部署。核心技术栈：
- Quarkus + MicroProfile (REST, GraphQL, OpenAPI, SmallRye Health)
- Hibernate ORM & Reactive + PostgreSQL
- Flyway 数据库迁移
- GraalVM Native (JDK 25) + Podman/Docker 容器

## 2. 本地开发
### 前置条件
- JDK 21 或 GraalVM 25
- Node.js 22
- Gradle 8.x（项目附带 Wrapper，可直接使用 `./gradlew`）
- PostgreSQL 或 Podman

### 启动数据库
```bash
podman run -d --name postgres-dev \
  -p 5433:5432 \
  -e POSTGRESQL_USERNAME=postgres \
  -e POSTGRESQL_PASSWORD=postgres \
  -e POSTGRESQL_DATABASE=aster_policy \
  docker.io/bitnami/postgresql:latest
```

### 构建与运行
```bash
./gradlew :quarkus-policy-api:quarkusDev
```

### 访问端点
- REST/Swagger UI: http://localhost:8080/q/swagger-ui
- GraphQL UI: http://localhost:8080/q/graphql-ui
- 健康检查: http://localhost:8080/q/health

## 3. Docker / Podman 部署
```bash
# 构建 native 镜像
podman build -f quarkus-policy-api/Dockerfile -t aster/policy-api:latest .

# 使用 Podman Compose
cd quarkus-policy-api
podman-compose up -d

# 或根目录 Compose（仅启 policy-api + postgres）
podman-compose -f docker-compose.yml up policy-api postgres
```

## 4. K3s / Kubernetes 部署
```bash
# 推送镜像到 registry
podman tag aster/policy-api:latest <registry>/policy-api:v1.0.0
podman push <registry>/policy-api:v1.0.0

# 部署到集群
kubectl apply -f k8s/policy-api-deployment.yaml
kubectl apply -f k8s/policy-api-service.yaml
kubectl apply -f k8s/postgres-statefulset.yaml
```

## 5. 环境变量
| 变量 | 说明 | 默认值 |
|---|---|---|
| `QUARKUS_DATASOURCE_JDBC_URL` | JDBC URL | `jdbc:postgresql://localhost:5433/aster_policy` |
| `QUARKUS_DATASOURCE_USERNAME` | 数据库用户名 | `postgres` |
| `QUARKUS_DATASOURCE_PASSWORD` | 数据库密码 | `postgres` |
| `QUARKUS_DATASOURCE_REACTIVE_URL` | Reactive URL | `postgresql://localhost:5433/aster_policy` |
| `QUARKUS_FLYWAY_MIGRATE_AT_START` | 启动迁移 | `true` |

## 6. 故障排查
- **健康检查失败**：确认 PostgreSQL 端口与凭证；确保 `host.containers.internal` 可解析。
- **Flyway 迁移失败**：检查数据库权限或已存在表结构；必要时 `DROP SCHEMA public CASCADE` 重建。
- **Native 构建失败**：确认 GraalVM 25、8G+ RAM、`SKIP_GENERATE_ASTER_JAR` 未误设。
- **Podman Compose 启动失败**：延长 `policy-api` 的 `start_period` 或手动确认 `pg_isready` 已通过。

## 7. 路径参考
- Dockerfile: `quarkus-policy-api/Dockerfile`
- Podman Compose: `quarkus-policy-api/podman-compose.yml`
- Root Compose: `docker-compose.yml`
- CI/CD: `.github/workflows/ci.yml`
