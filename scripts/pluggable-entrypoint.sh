#!/bin/sh
# 根据 ENABLED_LOCALES 删除未启用的语言包 jar，再启动 Quarkus。
#
# 验证目标：
# - LexiconRegistry 在启动期通过 SPI 发现 lib/main/ 下实际存在的 LexiconPlugin
# - 缺失的语言包应触发 FallbackLexicon 回退到 en-US（而非启动失败）

set -eu

LIB_DIR="/work/lib/main"
ENABLED="${ENABLED_LOCALES:-en,zh,de}"

echo "[pluggable-entrypoint] ENABLED_LOCALES=$ENABLED"

remove_if_disabled() {
  locale="$1"
  case ",$ENABLED," in
    *",$locale,"*)
      echo "[pluggable-entrypoint] keeping aster-lang-$locale"
      ;;
    *)
      pattern="$LIB_DIR/cloud.aster-lang.aster-lang-$locale-*.jar"
      # shellcheck disable=SC2086
      for f in $pattern; do
        if [ -f "$f" ]; then
          echo "[pluggable-entrypoint] removing $f"
          rm -f "$f"
        fi
      done
      ;;
  esac
}

# en 是 backbone，永远保留（即便用户误填）
remove_if_disabled "zh"
remove_if_disabled "de"

# 安全网：en 必须存在
if ! ls "$LIB_DIR"/cloud.aster-lang.aster-lang-en-*.jar >/dev/null 2>&1; then
  echo "[pluggable-entrypoint] FATAL: aster-lang-en jar missing; cannot proceed" >&2
  exit 1
fi

echo "[pluggable-entrypoint] starting Quarkus with $(ls $LIB_DIR/cloud.aster-lang.aster-lang-*.jar | tr '\n' ' ')"

# shellcheck disable=SC2086
exec java $JAVA_OPTS -jar /work/quarkus-run.jar
