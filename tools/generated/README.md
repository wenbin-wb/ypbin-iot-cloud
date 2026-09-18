# tools/generated

本目录存放**由构建产物生成**的文件（不手工维护），当前是**空的**，这是有意的：

| 文件 | 生成命令 | 状态 |
|---|---|---|
| `cloud-coverage.json` | `node tools/export-coverage.mjs` | **M0b 首次生成**（M0a 骨架没有可度量的真实代码，空快照会把文档里的覆盖率变成永远为零的假数据） |

覆盖率快照的 `--check`（模块集合不得静默缩小）属于 IOT-CLOUD-SPEC.md §9.1 的第 8 项门禁，
M0b 接入 CI；脚本本体已就位（`tools/export-coverage.mjs`），在快照生成之前它的 `--check`
会明确报错并提示先执行生成命令。

生成的 JSON **需要入库**（它是门禁基线），因此不要在本目录添加 `.gitignore`。
