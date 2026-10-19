# AELP — AE-LP Bridge

把 [Logistics Pipes](https://github.com/GTNewHorizons/LogisticsPipes) 的库存作为
[Applied Energistics 2](https://github.com/GTNewHorizons/Applied-Energistics-2-Unofficial)
可访问、可自动合成的目标，同时保证真实取货必须走 LP 自己的请求管道。

## 它解决什么问题

AE 的存储是"瞬间收取"，LP 的存储是"物理运输"。直接把 LP 库存塞进 AE 会让物品凭空出现，
又或者让 AE 合成任务永远等一个不会到来的交付。

AELP 的做法是：

- **存储层**：把 LP 库存以**只读虚拟投影**的形式报给 AE。终端里能看到，但**永远抽不出来**，
  不能被输出总线输出，也不能被存进任何存储单元。
- **合成层**：为每个 LP 物品生成一个"虚拟物品 → 真实物品"的处理样板。AE 库存不足时，
  自动走这条样板向 LP 下单。
- **执行层**：`pushPattern` 向 LP 发 `Provide` 请求（不触发 LP 自己的合成），LP 把真实物品
  物理送到桥接方块，方块再注入 ME 网络，被等待中的合成 CPU 收下。

## 使用

1. 把一个 **LP 请求管道（Request Logistics Pipe）** 放在 LP 线末端，
   旁边（**任意一面**）放 **LP-AE 桥接器**；
2. 桥接器接上 ME 网络（需要 1 个频道）；
3. 打开 ME 终端，会看到 `[LP] 物品名` 条目，数量 = LP 可用量扣除已被合成任务占用的部分；
4. 在终端请求那个**真实物品**，AE 会先用已有库存，不足部分自动向 LP 下单。

### 注意

- 请求管道旁边**只能有这一个库存**。如果还挂着箱子，LP 会随机分流，桥接器只能拿到一部分。
- 一个 LP 网络只能接一个桥接器（第二个会保持失活并打印警告）。
- 虚拟物品**不可获取、不可存储**，只在合成 CPU 内部作为输入 token 流转。

## 配置

`config/aelp.cfg`，全部在 `aelp` 分类下：

| 键 | 默认 | 说明 |
| --- | --- | --- |
| `enabled` | true | 总开关 |
| `refreshInterval` | 20 | LP 快照刷新间隔（tick） |
| `storagePriority` | 0 | 投影的存储优先级 |
| `idlePowerUsage` | 1.0 | AE 待机功耗 |
| `debugLog` | false | 打印快照/请求日志（排查用） |
| `crafting.patternPriority` | 1 | 样板优先级（只影响与其它合成路径的竞争） |
| `crafting.maxPushesPerTick` | 16 | 每 tick 最多接受的 LP 合成数（背压） |
| `crafting.requestEnergyCost` | 15 | 每次 LP 请求消耗的能量 |

## 依赖

- Minecraft 1.7.10 / Forge
- Applied Energistics 2 (GTNH Unofficial)
- Logistics Pipes (GTNH)

## 设计文档

完整方案见 [docs/lp-ae-bridge.md](docs/lp-ae-bridge.md)。
