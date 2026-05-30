# 洛克王国协议解析器

**项目名称：** Rock Kingdom Protocol Parser
**项目简称：** RKPP
**版本：** Rock Kingdom Protocol Parser-Ver2.2(Notgedrungen)

---

## 项目简介

RKPP 是一个用于学习、研究和分析洛克王国协议数据结构、字段语义、报文格式及解码流程的开源项目。

本项目面向协议研究、数据结构理解、网络数据分析、教学示例、互操作性研究与安全研究，提供可审阅、可修改、可复现的参考实现。Ver2.2 重点适配 2026-05 新版本协议数据、RocoMITMServer Ver2.1 的 protobuf/schema 解读逻辑，以及新版 0x4013 报文解析链路。

---

## 讨论群号

QQ：180106447

## 当前能力

- 抓取 0x1002 会话 key，并用于 0x4013 报文解密。
- 支持 live 抓包、离线 pcap 回放、协议实时解析和 opencode relay server。
- 支持 `embedded_iv` 与新版 `fixed_iv` 两类 0x4013 解密候选。
- 支持 TGCP internal header、payload 原文保留和 schema/payload 双路径解码。
- 接入 RocoMITMServer Ver2.1 的 opcode、message schema、decoder override 与 opcode payload 解码逻辑。
- 刷新 `Attr`、`Pet`、`Skill`、`opcode`、`proto_schema` 数据，增强名称映射与摘要输出。
- 继续保留高语义战斗 opcode 的手写摘要，用于回合、技能、伤害、能量、换人、道具和战斗结束分析。

---

## 文件说明

- `rkpp_live_tools.py`：命令行入口，提供抓 key、实时解码、协议分析和 opencode relay。
- `rkpp_analyzer.py`：抓包流重组、解密调度、协议解析和摘要分发。
- `rkpp_proto.py`：TGCP、protobuf wire、TSF4G trailer 与记录解析。
- `rkpp_network.py`：网络流识别、key 处理和 0x4013 解密。
- `rkpp_analysis.py`：opcode/schema 查询、字段树解码、payload codec fallback 和名称增强。
- `rkpp_codec.py`：从 RocoMITMServer 接入的通用 protobuf codec。
- `rkpp_opcode_payload.py`：从 RocoMITMServer 接入的 opcode payload decoder。
- `rkpp_io.py`：CSV、opencode summary、离线 pcap 输入与会话日志。
- `rkpp_reporter.py`：协议实时摘要输出。
- `rkpp_relay.py`：本地 HTTP NDJSON relay server。
- `Data.py`：数据加载入口。
- `Data/`：属性、宠物、技能、opcode 与 proto schema 数据。
- `tools/update_roco_data.py`：从 RocoMITMServer 与 world-data 刷新 RKPP 数据文件。
- `tests/`：协议守卫、analyzer dispatch 与新版本适配回归测试。

---

## 依赖安装

建议使用 Python 3.11 或更新版本：

```powershell
python -m pip install -r requirements.txt
```

实时抓包还需要系统侧抓包驱动与权限，例如 Windows 上的 Npcap。离线 pcap 回放和数据刷新不需要打开实时抓包接口。

---

## 基本使用

列出网卡：

```powershell
python rkpp_live_tools.py --list-ifaces
```

抓取 key：

```powershell
python rkpp_live_tools.py capture-key --iface 以太网 --port 8195 --out-dir runs\key_capture
```

实时协议解析：

```powershell
python rkpp_live_tools.py analyze --iface 以太网 --port 8195 --out-dir runs\analyze
```

离线 pcap 回放：

```powershell
python rkpp_live_tools.py analyze --read-pcap capture.pcap --key <16字节ASCII或32位hex> --out-dir runs\offline
```

启动 opencode relay：

```powershell
python rkpp_live_tools.py opencode-server --iface 以太网 --port 8195 --relay-host 127.0.0.1 --relay-port 8765
```

---

## 数据刷新

Ver2.2 的数据刷新脚本需要两个外部来源：

- RocoMITMServer Ver2.1：提供最新 opcode、message schema、decoder override 与 payload 解码逻辑。
- Roco-Kingdom-World-Data：提供属性、宠物、技能等 world-data 导出。

示例：

```powershell
python tools\update_roco_data.py --mitm-root <RocoMITMServer_Ver2.1_Verzweifelt> --world-root <Roco-Kingdom-World-Data-2026-05-21-main> --out-data-dir Data
```

---

## 发布验证

本版本发布前已完成：

- `python -m unittest discover -s tests`
- `python -m compileall -q .`
- 真实登录与一场完整战斗 live capture 验证

实测战斗链路已覆盖进入战斗、回合开始、技能宣告、动作结算、换人、能量瓶、愿力强化和战斗结束摘要。

---

## 许可协议

本项目采用 **GNU Affero General Public License v3.0 only（AGPL-3.0-only）** 发布。

这意味着：

1. 任何人都可以在遵守 AGPL-3.0-only 的前提下使用、复制、修改和再发布本项目；
2. 如对本项目进行修改并再次分发，必须继续以 AGPL-3.0-only 开源，并提供对应源码；
3. 如将修改后的版本部署为网络服务、在线接口、远程解析平台或其他可供他人通过网络交互使用的形式，亦必须按 AGPL-3.0-only 向相关用户提供对应源码；
4. 再发布或衍生版本必须保留原作者署名、版权声明、`LICENSE` 文件与 `NOTICE` 文件。

---

## 重要用途声明

**作者不支持将本项目用于外挂、作弊、破坏游戏环境、绕过安全机制、恶意攻击、数据滥用或其他损害游戏生态及第三方合法权益的行为。**

本项目发布的主要目的仅为：

- 学习研究
- 协议结构分析
- 教学示例
- 互操作性研究
- 安全研究与数据格式理解

请在使用前自行确认你的行为是否符合适用法律法规、服务条款、用户协议、EULA、第三方知识产权与相关权利要求。

**如你基于本项目实施任何违反法律法规、违反服务条款、破坏游戏环境或侵害第三方权益的行为，相关风险与责任均由你自行承担。**

---

## 免责声明

本项目按“**原样**”（**AS IS**）提供，不附带任何明示或默示担保，包括但不限于可用性、适销性、特定用途适用性、不侵权、安全性、正确性和稳定性担保。

作者不保证本项目适用于任何生产环境，不保证本项目一定符合任何游戏、平台或服务商的规则，也不保证本项目不存在缺陷、错误、兼容性问题或法律风险。

因使用、修改、分发、部署本项目所导致的任何直接或间接后果，包括但不限于账号处罚、服务封禁、数据丢失、系统损坏、第三方索赔、合同争议、行政责任、民事责任或刑事风险，均由使用者自行承担，**原作者不承担任何责任**。

---

## 侵权与联系说明

如果你认为本项目中的内容存在侵权、权利冲突或其他不适宜公开的问题，请联系作者处理。作者在核实后会尽快处理相关问题；如情况属实，将尽快删除、修改或下线相关内容。

---

## 作者

**花吹雪又一年**
