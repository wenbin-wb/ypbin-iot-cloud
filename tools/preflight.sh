#!/usr/bin/env bash
#
# 门禁一次性总检（M0a 只含第一批门禁）。
#
# 为什么需要它：根 pom 的 dev-only profile 是 activeByDefault，
# **只要显式激活任一 profile（-Pit / -Pnullaway / ...）它就会失效** ——
# 架构约束门禁（ArchUnit / 源码规范 / 模块发布边界）与两个非发布模块随之从反应堆里消失。
# 因此「跑门禁」只能是 `mvn -B -ntp clean verify`（不带任何 -P），不要在本地把多个 -P 组合起来。
#
# ⚠️ 步骤顺序有硬约束（母仓 CI 上的真实事故）：**凡是会 clean 的步骤，必须排在「产出覆盖率报告」
# 的构建步骤之前**，否则 target/site/jacoco/ 会被删掉，归档步骤会「成功但零产物」。
# 所以本脚本保持「先构建、最后断言覆盖率报告仍在」的顺序。
#
# 用法：tools/preflight.sh
#
set -euo pipefail

cd "$(dirname "$0")/.."
export MAVEN_OPTS="${MAVEN_OPTS:--Xmx768m}"

# 覆盖率报告数量期望值：M0a 骨架阶段只有「既有主源码又有测试数据」的模块会产出 jacoco.csv。
# 实测（2026-09-18，本机 2 vCPU / JDK 21 / Maven 3.8.7，命令 `mvn -B -ntp -fae clean test`）：
#   ypbin-iot-cloud-common → 有（CloudConstants + 用例，指令 13/13）
#   其余模块 → 「missing execution data file」（没有测试）或
#              architecture-tests → 「missing classes directory」（没有主源码）
#   合计 = 1。这里只断言 >= 1：目的是抓「测试没跑 / 报告被 clean 删了」，不锁死具体数字。
EXPECTED_JACOCO_CSV=1

step() { echo; echo "===== $* ====="; }

step "1/2 全量构建与单元测试（架构约束 / 源码规范 / 模块发布边界 / spotless / 覆盖率门禁）"
# 不带任何 -P：这是唯一会跑架构门禁的调用方式。
mvn -B -ntp clean verify

step "2/2 覆盖率报告存在性断言（防止「到归档时才发现报告被 clean 删了」）"
count="$(find . -path '*/target/site/jacoco/jacoco.csv' | wc -l)"
echo "jacoco.csv 数量 = ${count}（期望 >= ${EXPECTED_JACOCO_CSV}）"
if [ "${count}" -lt "${EXPECTED_JACOCO_CSV}" ]; then
  echo "失败：覆盖率报告缺失或不足（实测 ${count} 个）—— 多半是某一步执行了 clean。" >&2
  echo "  排查方向：把带 clean 的步骤移到第 1 步之前，或让该步骤不要复用默认 target/。" >&2
  exit 1
fi

echo
echo "M0a 第一批门禁全部通过。"

# =====================================================================================
# M0b 接入（本阶段**不跑**，脚本与门禁项先在此登记，避免遗忘）：
#   ① NullAway 空值语义          tools/check-nullaway.sh（需先给各模块加 nullaway.packages）
#   ② 依赖版本收敛               mvn -B -ntp -Pdep-convergence validate
#   ③ 配置元数据漂移             node tools/export-config-metadata.mjs --check（脚本待移植）
#   ④ 覆盖率快照模块集合         node tools/export-coverage.mjs --check（需先生成基线快照）
#   ⑤ 供应链 SBOM                mvn -B -ntp -Psbom verify -DskipTests（需先加 sbom profile，
#                                且该 profile 必须显式带回两个非发布模块）
#   ⑥ 集成测试                   mvn -B -ntp -Pit verify（M0a 只有占位 IT，P5 才有真实场景）
# 依据：IOT-CLOUD-SPEC.md §9.1 的 13 项门禁清单与 §10 的 M0b 出口条件（13 项全绿）。
# =====================================================================================
