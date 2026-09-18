#!/usr/bin/env bash
#
# NullAway 空值语义门禁（含「门禁是否真的执行过」自检）。
#
# ⚠️ **本阶段（M0a）不接 CI**：NullAway 属于第二批门禁（IOT-CLOUD-SPEC.md §9.1 第 5 项），
#    在 M0b 随「首批业务代码」一起接入。本文件是从 ypbin-iot-starter 移植过来的**备用工具**，
#    M0a 阶段各模块**还没有声明 nullaway.packages**，直接跑它只会分析一个占位包
#    （父 pom 的默认值 cn.ypbin.starter.__nullaway_placeholder__），「0 违规」没有意义。
#    M0b 接入时必须同时做两件事：
#      ① 每个含 src/main/java 的模块 pom 里声明 <nullaway.packages>cn.ypbin.iotcloud.<模块></nullaway.packages>；
#      ② 把本脚本挂进 CI 的第一步（它内部是 clean compile，必须排在产出覆盖率的步骤之前）。
#
# 为什么需要「执行自检」：Error Prone / NullAway 只在 javac 真正执行时生效。
# 若上一步已经编译过，`mvn compile` 会直接输出「Nothing to compile」并成功返回 ——
# 此时「0 违规」是假象（母仓踩过这个坑）。因此本脚本：
#   ① 强制带 clean，保证真的重新编译；
#   ② 断言输出里出现每个模块的「Compiling N source files」；
#   ③ 出现「Nothing to compile」即判失败。
#
# 用法：tools/check-nullaway.sh
#
set -euo pipefail

cd "$(dirname "$0")/.."

MODULES=(
  ypbin-iot-cloud-common
  ypbin-iot-cloud-api
  ypbin-iot-cloud-auth
  ypbin-iot-cloud-core
  ypbin-iot-cloud-openapi
  ypbin-iot-cloud-gateway
  ypbin-iot-cloud-business
  ypbin-iot-cloud-access
)
LIST="$(IFS=,; echo "${MODULES[*]}")"
LOG="$(mktemp)"
trap 'rm -f "$LOG"' EXIT

# 打印「实际生效的分析器版本」，避免用到旧分析器却以为门禁是绿的
# （实机踩过：本地仓库里的旧快照是 NullAway 0.11.3，而声明版本是 0.14.1，前者把违规放过去了）。
PARENT_VERSION="$(grep -oP '(?<=<version>)[^<]+' <<< "$(sed -n '/<parent>/,/<\/parent>/p' pom.xml)" | head -1)"
PARENT_POM="${HOME}/.m2/repository/cn/ypbin/ypbin-starter-dependencies/${PARENT_VERSION}/ypbin-starter-dependencies-${PARENT_VERSION}.pom"

# 本脚本将来会作为 CI 的**第一步**运行（它内部是 clean compile，必须排在产出覆盖率的步骤之前），
# 此时本地仓库往往是空的（首次运行 / 缓存未命中）—— 父 pom 还没被解析下来。
# 因此缺失时先解析一次，而不是直接判失败（干净 ~/.m2 会在这里假失败）。
if [ ! -f "$PARENT_POM" ]; then
  echo "[nullaway] 本地仓库还没有父 pom ${PARENT_VERSION}（CI 首次运行属正常），先解析一次……"
  if ! mvn -B -ntp -N -q validate >/dev/null 2>&1; then
    echo "[nullaway] 失败：无法解析父 pom ${PARENT_VERSION}" >&2
    echo "  正式版应从 Central 自动解析；请检查网络与 ~/.m2/settings.xml 的镜像配置。" >&2
    exit 1
  fi
fi
if [ ! -f "$PARENT_POM" ]; then
  echo "[nullaway] 失败：解析后本地仓库仍没有父 pom ${PARENT_VERSION}" >&2
  echo "  本仓依赖的是已发布的正式版父 pom，无需先安装母仓。" >&2
  exit 1
fi
ANALYZER_VERSION="$(grep -oP '(?<=<nullaway.version>)[^<]+' "$PARENT_POM" | head -1)"
echo "[nullaway] 父 pom ${PARENT_VERSION} · NullAway ${ANALYZER_VERSION:-未知} · Error Prone $(grep -oP '(?<=<error-prone.version>)[^<]+' "$PARENT_POM" | head -1)"

echo "[nullaway] 参与模块（${#MODULES[@]} 个）：${LIST}"
set +e
MAVEN_OPTS="${MAVEN_OPTS:--Xmx768m}" mvn -B -ntp -Pnullaway -pl "$LIST" -am -DskipTests clean compile > "$LOG" 2>&1
STATUS=$?
set -e

if grep -q "Nothing to compile" "$LOG"; then
  echo "[nullaway] 失败：出现「Nothing to compile」—— 门禁没有真正执行，本次结果不可信" >&2
  tail -30 "$LOG" >&2
  exit 1
fi

# 每个参与模块都必须留下编译记录，否则它可能根本没被编译（被上游失败跳过）
MISSING=()
for module in "${MODULES[@]}"; do
  if ! grep -q "Compiling [0-9]* source files\?" <(awk -v m="$module" '
        /--- .*@ /{ if ($0 ~ ("@ " m " ---")) found=1; else found=0 }
        found && /Compiling [0-9]+ source files?/{print}' "$LOG"); then
    MISSING+=("$module")
  fi
done

if [ "${#MISSING[@]}" -gt 0 ]; then
  echo "[nullaway] 失败：以下模块没有编译记录（可能被上游失败跳过，其「0 违规」不可信）：${MISSING[*]}" >&2
  tail -30 "$LOG" >&2
  exit 1
fi

if [ "$STATUS" -ne 0 ]; then
  echo "[nullaway] 失败：构建未通过" >&2
  grep -E "\[NullAway\]|ERROR" "$LOG" | head -40 >&2
  exit 1
fi

echo "[nullaway] 通过：${#MODULES[@]} 个模块均实际编译，0 违规"
