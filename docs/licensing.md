# 许可与第三方组件

## 结论：整仓 GPL-3.0

整个仓库按 **GNU General Public License v3.0** 发布：可以自由使用、修改、再分发，包括商业用途，
条件是衍生作品同样以 GPL-3.0 发布并提供对应源码。

| 文件 | 内容 |
| --- | --- |
| `LICENSE` | GPL-3.0 全文 |
| `LICENSES/Apache-2.0.txt` | Apache-2.0 全文，只覆盖从 CyberMeowfia 移植的那几个文件 |
| `NOTICE` | 第三方归属、版本与源码位置 |

为什么改成 GPL-3.0：APK 现在**内置**了 ReZygisk、Vector、KernelSU 管理器三个 GPL-3.0 组件
（原来是运行时由用户自己下载）。分发 GPL 代码的组合作品只能整体按 GPL 兼容的许可发行，
所以之前的 PolyForm Noncommercial 1.0.0 走不通。改用 GPL-3.0 之后也不再限制商用——
OSI 的开源定义第 6 条不允许限制使用领域，`LEGAL.md` 里只保留「仅限自有或已授权设备」
这类风险提示，不当作许可证附加条件。

代价写清楚：衍生作品必须同样以 GPL-3.0 开源并提供对应源码；只提供二进制不发源码的分发方式不允许。
另外 GPL-3.0 不是 AGPL——网络服务形态的分发不触发源码义务。

## 随 APK 分发的第三方组件

| 组件 | 版本 | 仓库内路径 | 许可 |
| --- | --- | --- | --- |
| ReZygisk | 1.0.0 (515) | `app/src/main/assets/modules/rezygisk.zip`（0.6 MB） | GPL-3.0 |
| Vector（LSPosed 系） | 2.2 (3080) | `app/src/main/assets/modules/vector.zip`（8.9 MB） | GPL-3.0 |
| KernelSU 管理器 | 3.3.1 | `app/src/main/assets/kernelsu-manager.apk`（13.0 MB） | GPL-3.0 |
| `libksud.so` | 3.3.1 | `app/src/main/jniLibs/arm64-v8a/libksud.so` | GPL-3.0 |

四项都是 GPL-3.0，与整仓许可一致，组合分发不产生冲突。`libksud.so` 取自 KernelSU release，
构建时放进 `jniLibs`（仓库里不含二进制，`local.properties` 与 `*.so` 都不入库）；
它也留在 `kernelsu-manager.apk` 的 `lib/arm64-v8a/` 里，缺失时可以从那里取。

## 不随包分发的组件

| 组件 | 许可 | 处理 |
| --- | --- | --- |
| ZygiskNext | All rights reserved（v4-0.9.2 起 README 明文禁止再分发） | **永不打包**。同一角色由 ReZygisk 承担 |

## 仓库内包含的第三方代码

| 路径 | 来源 | 许可 |
| --- | --- | --- |
| `src/core/kernelsnitch/{futex_hash,kernelsnitch,timeutils,utils}.h` | [NebuSec/CyberMeowfia](https://github.com/NebuSec/CyberMeowfia)（四个文件在它库里同名同路径） | Apache-2.0 |
| `src/core/kernelsnitch/futex_hash.h` 的 jhash 部分 | Bob Jenkins lookup3（public domain）+ Linux 内核修改版，版权行保留在文件头 | public domain |
| `number_parse.h`、`scan_bounds.h` | 本项目自有 | GPL-3.0 |

Apache-2.0 与 GPL-3.0 单向兼容（Apache-2.0 代码可以并入 GPL-3.0 作品），条件是保留归属和
许可全文——`LICENSES/Apache-2.0.txt` 与 `NOTICE` 就是干这个的。

## 历史清洗（2026-10-22，后来被推翻了）

当时的结论是「不内置任何 GPL 包」，为了不构成再分发，用
`git filter-branch --index-filter 'git rm -r --cached --ignore-unmatch app/src/main/assets/modules'`
把早期提交里的 `vector.zip`（8.9 MB）和 `zygisknext.zip`（6.4 MB）从全部 ref 的历史里剔除，
提交数 597 不变，4 个 ref 的 tip 树逐字节一致，`.git` 从 36 MB 降到 20.33 MiB。

**这一条已经作废**：仓库改为 GPL-3.0 之后重新内置了 `vector.zip` 与 `rezygisk.zip`，
再分发不再有许可障碍。清洗本身仍然有效（省掉历史里的无用大文件），只是当初的理由不再成立。
重写前的完整备份留在宿主 `/tmp/opencode/ghostlock-app-full.bundle`，不入仓库。

## 仓库布局：单仓库，无子模块（2026-11-03）

原来 app 是子模块 `vendor/ghostlock-app`，2026-11-03 把它 594 条历史并入本仓库根目录，
删除 `.gitmodules` 与 gitlink。现在 `app/`、`src/`、`tools/`、`profile-core/` 都在根下，
一份历史、一个许可。

- 合并前的状态有 tag `backup/pre-flatten-2026-11-03`
- 合并前的子模块仓库对象仍留在 `.git/modules/vendor/ghostlock-app`（本机备份，不入仓库）
- 原项目调查说明（Titan 2 可行性、profile 提取过程）移到了
  `docs/titan2-temp-root-project.md`

## brain 不进仓库

`brain/` 是本地项目记忆，已从版本库移除（文件仍在磁盘），`.gitignore` 里有 `brain/`。
需要公开的内容写在 `docs/` 下。

## 还没核完的两件事

1. `JoinChang/ghostlock-oneplus` 与 `x-spy/CVE-2026-43499-popsicle` 两个仓库**没有 LICENSE 文件**
   （GitHub license 字段为空，根目录无 LICENSE/COPYING），默认保留所有权利。目前只能说
   「参考过」，不能证明没有逐行搬运；发布前要做一次逐文件比对（`kernelsnitch/` 那 4 个文件对
   CyberMeowfia 版本做 diff，其余对 popsicle 的 C 源码做结构比对）。
2. `src/core/session/backend/cve_2026_43499_backend.cpp` 的注释写着 `ported from root.c`，
   没记录是哪个 `root.c`。要补出来源，或者把那句注释改成描述实现本身。
