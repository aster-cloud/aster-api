#!/usr/bin/env bash
#
# Aster API 一键部署脚本
# 作用：构建 uber-jar、打包镜像并推送至 Docker Hub，随后更新远程 K3S 集群的 aster-api Deployment。
# 用法示例：
#   scripts/deploy-aster-api.sh --tag jvm-latest
#   scripts/deploy-aster-api.sh --tag v1.2.3 --skip-build --skip-push --kubeconfig ~/.kube/k3s-config
# 参数：
#   --tag <tag>          镜像标签（默认：jvm-latest）
#   --clean              构建前执行 gradle clean 清除缓存
#   --skip-build         跳过 Gradle 构建
#   --skip-push          跳过镜像推送
#   --skip-deploy        跳过 K3S 更新
#   --dry-run            仅打印将执行的命令，不产生副作用
#   --kubeconfig <path>  指定 kubeconfig（默认：~/.kube/k3s-config）
#   --namespace <ns>     部署命名空间（默认：aster-cloud）
#   --debug              显示调试日志
#   -h/--help            打印帮助

set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${PROJECT_ROOT}"

DOCKERFILE_PATH="${PROJECT_ROOT}/Dockerfile.jvm"
GRADLE_CMD=(./gradlew build --no-build-cache --no-configuration-cache -Dquarkus.package.jar.type=fast-jar -x test -x compileTestJava)
IMAGE_REPO="wontlost/aster-api"
DEPLOYMENT_NAME="aster-api"
POD_SELECTOR="app=aster-api"

TAG="jvm-latest"
CLEAN=false
SKIP_BUILD=false
SKIP_PUSH=false
SKIP_DEPLOY=false
DRY_RUN=false
DEBUG=false
KUBECONFIG_PATH="${HOME}/.kube/k3s-config"
NAMESPACE="aster-cloud"

COLOR_INFO="\033[1;34m"
COLOR_WARN="\033[1;33m"
COLOR_ERROR="\033[1;31m"
COLOR_SUCCESS="\033[1;32m"
COLOR_DEBUG="\033[0;36m"
COLOR_RESET="\033[0m"

CURRENT_STEP="初始化"
CONTAINER_CLI=""
KUBECTL_GLOBAL=()
KUBECTL_NS=()

usage() {
  cat <<'EOF'
Aster API 一键部署脚本
参数：
  --tag <tag>          镜像标签（默认：jvm-latest）
  --clean              构建前执行 gradle clean 清除缓存
  --skip-build         跳过 Gradle 构建
  --skip-push          跳过镜像推送
  --skip-deploy        跳过 K3S 更新
  --dry-run            仅打印将执行的命令
  --kubeconfig <path>  kubeconfig 路径（默认：~/.kube/k3s-config）
  --namespace <ns>     K8S 命名空间（默认：aster-cloud）
  --debug              显示调试日志
  -h, --help           显示此帮助
EOF
}

log_info() { printf "${COLOR_INFO}[INFO]${COLOR_RESET} %s\n" "$1"; }
log_warn() { printf "${COLOR_WARN}[WARN]${COLOR_RESET} %s\n" "$1"; }
log_error() { printf "${COLOR_ERROR}[ERROR]${COLOR_RESET} %s\n" "$1" >&2; }
log_success() { printf "${COLOR_SUCCESS}[SUCCESS]${COLOR_RESET} %s\n" "$1"; }
log_debug() {
  if [ "${DEBUG}" = true ]; then
    printf "${COLOR_DEBUG}[DEBUG]${COLOR_RESET} %s\n" "$1"
  fi
}

set_step() {
  CURRENT_STEP="$1"
  log_debug "进入步骤：${CURRENT_STEP}"
}

on_error() {
  local exit_code="$1"
  local line="$2"
  log_error "步骤【${CURRENT_STEP}】在第 ${line} 行失败（退出码 ${exit_code}）"
  exit "${exit_code}"
}

trap 'on_error $? $LINENO' ERR

format_cmd() {
  local formatted=""
  local arg
  for arg in "$@"; do
    printf -v arg '%q' "$arg"
    formatted+="${arg} "
  done
  printf '%s' "${formatted}"
}

run_cmd() {
  local display
  display="$(format_cmd "$@")"
  if [ "${DRY_RUN}" = true ]; then
    log_info "DRY-RUN：${display}"
    return 0
  fi

  log_debug "执行命令：${display}"
  "$@"
}

require_arg() {
  local opt="$1"
  local value="$2"
  if [ -z "${value}" ]; then
    log_error "${opt} 需要参数"
    usage
    exit 1
  fi
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --tag)
      require_arg "--tag" "${2:-}"
      TAG="$2"
      shift 2
      ;;
    --clean)
      CLEAN=true
      shift
      ;;
    --skip-build)
      SKIP_BUILD=true
      shift
      ;;
    --skip-push)
      SKIP_PUSH=true
      shift
      ;;
    --skip-deploy)
      SKIP_DEPLOY=true
      shift
      ;;
    --dry-run)
      DRY_RUN=true
      shift
      ;;
    --kubeconfig)
      require_arg "--kubeconfig" "${2:-}"
      KUBECONFIG_PATH="$2"
      shift 2
      ;;
    --namespace)
      require_arg "--namespace" "${2:-}"
      NAMESPACE="$2"
      shift 2
      ;;
    --debug)
      DEBUG=true
      shift
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      log_error "未知参数：$1"
      usage
      exit 1
      ;;
  esac
done

CONTAINER_IMAGE="${IMAGE_REPO}:${TAG}"

detect_container_cli() {
  if command -v docker >/dev/null 2>&1; then
    CONTAINER_CLI="docker"
  elif command -v podman >/dev/null 2>&1; then
    CONTAINER_CLI="podman"
  else
    log_error "未检测到 docker 或 podman，请先安装容器运行时"
    exit 1
  fi
  log_info "使用容器工具：${CONTAINER_CLI}"
}

prepare_kubectl() {
  if ! command -v kubectl >/dev/null 2>&1; then
    log_error "未找到 kubectl，请安装后重试"
    exit 1
  fi
  KUBECTL_GLOBAL=(kubectl --kubeconfig "${KUBECONFIG_PATH}")
  KUBECTL_NS=("${KUBECTL_GLOBAL[@]}" -n "${NAMESPACE}")
  log_info "使用 kubeconfig：${KUBECONFIG_PATH}，命名空间：${NAMESPACE}"
}

preflight_checks() {
  set_step "环境预检"
  log_info "项目根目录：${PROJECT_ROOT}"
  log_info "目标镜像：${CONTAINER_IMAGE}"

  if [ ! -f "${DOCKERFILE_PATH}" ]; then
    log_error "缺失 Dockerfile：${DOCKERFILE_PATH}"
    exit 1
  fi

  detect_container_cli

  if [ "${SKIP_BUILD}" = false ] && [ ! -x "${GRADLE_CMD[0]}" ]; then
    log_error "Gradle 包装器不可执行：${GRADLE_CMD[0]}"
    exit 1
  fi

  if [ "${SKIP_DEPLOY}" = false ]; then
    prepare_kubectl
    log_info "校验 K3S 连接"
    run_cmd "${KUBECTL_GLOBAL[@]}" --request-timeout=10s get nodes
    run_cmd "${KUBECTL_GLOBAL[@]}" cluster-info
  else
    log_warn "跳过 K3S 部署相关检查"
  fi
}

gradle_build() {
  if [ "${SKIP_BUILD}" = true ]; then
    log_warn "已按参数跳过 Gradle 构建"
    return
  fi
  set_step "Gradle 构建"
  if [ "${CLEAN}" = true ]; then
    log_info "执行 Gradle clean 清除缓存"
    run_cmd ./gradlew clean
  fi
  log_info "执行 Gradle 构建 uber-jar"
  run_cmd "${GRADLE_CMD[@]}"
}

build_image() {
  set_step "镜像构建"
  log_info "使用 ${CONTAINER_CLI} 构建 ${CONTAINER_IMAGE}"
  run_cmd "${CONTAINER_CLI}" build -f "${DOCKERFILE_PATH}" -t "${CONTAINER_IMAGE}" .
}

check_docker_login() {
  # Check if logged in to Docker Hub by inspecting auth config
  local auth_file="${HOME}/.docker/config.json"
  if [ "${CONTAINER_CLI}" = "podman" ]; then
    # 优先检查 XDG_RUNTIME_DIR，然后回退到 ~/.config/containers
    local xdg_runtime="${XDG_RUNTIME_DIR:-}"
    if [ -n "${xdg_runtime}" ] && [ -f "${xdg_runtime}/containers/auth.json" ]; then
      auth_file="${xdg_runtime}/containers/auth.json"
    else
      auth_file="${HOME}/.config/containers/auth.json"
    fi
  fi

  if [ -f "${auth_file}" ] && grep -q "docker.io\|index.docker.io" "${auth_file}" 2>/dev/null; then
    log_debug "已检测到 Docker Hub 登录凭据"
    return 0
  fi

  log_warn "未检测到 Docker Hub 登录凭据"
  log_info "正在尝试登录 Docker Hub..."
  if ! "${CONTAINER_CLI}" login docker.io; then
    log_error "Docker Hub 登录失败，请手动执行: ${CONTAINER_CLI} login docker.io"
    exit 1
  fi
  log_success "Docker Hub 登录成功"
}

push_image() {
  if [ "${SKIP_PUSH}" = true ]; then
    log_warn "已按参数跳过镜像推送"
    return
  fi
  set_step "镜像推送"
  if [ "${DRY_RUN}" = false ]; then
    check_docker_login
  fi
  run_cmd "${CONTAINER_CLI}" push "${CONTAINER_IMAGE}"
}

deploy_to_k3s() {
  if [ "${SKIP_DEPLOY}" = true ]; then
    log_warn "已按参数跳过部署"
    return
  fi
  set_step "K3S 部署更新"
  run_cmd "${KUBECTL_NS[@]}" set image "deployment/${DEPLOYMENT_NAME}" "${DEPLOYMENT_NAME}=${CONTAINER_IMAGE}"
}

wait_rollout() {
  if [ "${SKIP_DEPLOY}" = true ]; then
    return
  fi
  set_step "滚动更新"
  run_cmd "${KUBECTL_NS[@]}" rollout status "deployment/${DEPLOYMENT_NAME}" --timeout=180s
}

health_check() {
  if [ "${SKIP_DEPLOY}" = true ]; then
    return
  fi
  if [ "${DRY_RUN}" = true ]; then
    log_warn "Dry-run：跳过 Pod 健康检查"
    return
  fi
  set_step "健康检查"

  # 获取当前 Deployment 的最新 ReplicaSet
  local current_rs
  current_rs="$("${KUBECTL_NS[@]}" get deployment "${DEPLOYMENT_NAME}" -o jsonpath='{.status.updatedReplicas}' 2>/dev/null || echo "0")"
  local desired_replicas
  desired_replicas="$("${KUBECTL_NS[@]}" get deployment "${DEPLOYMENT_NAME}" -o jsonpath='{.spec.replicas}' 2>/dev/null || echo "2")"

  log_info "期望副本数: ${desired_replicas}, 已更新副本数: ${current_rs}"

  # 获取活动 Pod（排除 Failed/Succeeded 状态）
  local pods
  pods="$("${KUBECTL_NS[@]}" get pods -l "${POD_SELECTOR}" \
    --field-selector=status.phase!=Failed,status.phase!=Succeeded \
    -o custom-columns=NAME:.metadata.name,STATUS:.status.phase,DELETION:.metadata.deletionTimestamp --no-headers)"

  if [ -z "${pods}" ]; then
    log_error "未找到 label=${POD_SELECTOR} 的活动 Pod"
    exit 1
  fi

  local running_count=0
  local unhealthy=()
  while read -r name status deletion _; do
    [ -z "${name}" ] && continue
    # 跳过正在终止的 Pod（deletionTimestamp 不为空表示正在删除）
    if [ "${deletion}" != "<none>" ] && [ -n "${deletion}" ]; then
      log_debug "跳过正在终止的 Pod: ${name}"
      continue
    fi
    if [ "${status}" = "Running" ]; then
      running_count=$((running_count + 1))
    else
      unhealthy+=("${name}:${status}")
    fi
  done <<<"${pods}"

  if [ "${#unhealthy[@]}" -gt 0 ]; then
    log_warn "以下 Pod 未就绪（非 Running）：${unhealthy[*]}"
  fi

  if [ "${running_count}" -lt 1 ]; then
    log_error "没有 Running 状态的 Pod"
    exit 1
  fi

  log_success "健康检查通过: ${running_count} 个 Pod 处于 Running 状态"
}

preflight_checks
gradle_build
build_image
push_image
deploy_to_k3s
wait_rollout
health_check

log_success "Aster API 部署流程完成"
