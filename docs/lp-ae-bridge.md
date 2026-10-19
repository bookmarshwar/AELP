# LP → AE 虚拟库存桥接器 设计方案

目标：把 LP 网络的库存作为 AE 可访问、可自动合成的目标，但真实取货必须走 LP 的请求管道
（AE 是"瞬间收取"，LP 是"物理运输"）。

已确认的五条决策：

1. 用**一个方块**取代原来的 LPSR + LPSP 两个方块。
2. 只允许 AE 发起 LP 的 **Provide**（取现有库存）请求，**不触发 LP 自己的合成**。
3. 接受一个针对 AE2 内部类 `MECraftingInventory.commit` 的 late mixin。
4. **不追踪 LP 交付失败**：只保证 LP 请求成功发出；交付失败时 AE 合成任务会卡在等待，
   由玩家自行排查（与 LP 合成终端的行为一致）。
5. **取用顺序：默认优先从 AE 库存拉取，AE 库存不足时再从 LP 库存拉取。**
   这是 AE 的原生行为（`ExtractItemResolver` 优先级 `MAX_VALUE - 100` > 样板优先级），
   不需要额外开关或代码；不做"LP 优先"（见 §7.7）。

---

## 1. 三条硬性语义

| 语义 | 实现手段 |
| --- | --- |
| AE 能看到 LP 库存 | `ICellProvider` 只读投影，数量随 LP 快照刷新 |
| 虚拟库存不可被取出 / 不可被总线输出 / 不可被存储 | `extractItems()` 恒返回 `null`，`injectItems()` 原样返回 |
| 自动合成能跑通 | 动态样板 + `pushPattern` 发 LP 请求 + 真实物品经 LP 送达后注入 ME |

关键点：**虚拟物品从头到尾没有进入任何存储，也没有任何路径能抽取它**。
它是"LP 库存的只读镜像"，不是"AE 里的一份库存副本"。

---

## 2. 这不是"接口那样的缓存"

- **缓存（cache）** = 方块自己存一份物品，AE 从方块取。这必然可被抽取，也会出现
  "AE 抽走了但 LP 里还在"的复制漏洞——上一次提交的失败方案就属于这一类。
- **投影（projection）** = 方块只保存一份"数量快照"，物品本体始终在 LP 的箱子里。
  快照每 N tick 从 LP 重新计算，AE 只能看、不能取。

唯一的真实物品缓冲区是 **LP 交付落点**（见 §5.4）：它只是"过路"，每 tick 排空注入 ME，
不构成库存，也不允许 LP 之外的东西往里塞。

---

## 3. 从依赖源码读到的硬约束

依赖版本（`build/elytra_conventions/2.9.0-RC-1.json`）：
AE2 `rv3-beta-1073-GTNH`，LogisticsPipes `1.5.36-GTNH`。

1. CPU 执行样板时，输入从 **CPU 自己的 `MECraftingInventory`** 抽取
   （`CraftingCPUCluster.executeCrafting` → `this.inventory.extractItems(...)`），不是从网络抽。
2. CPU 的 inventory 由 `ExtractItemResolver.ExtractItemTask.startOnCpu` 的 `addStorage` 填入。
3. `CraftingCPUCluster.submitJob` 在 `startCrafting` 之后调用 `ci.commit(src)`；
   `MECraftingInventory.commit` 会对同一批物品**再次从真实网络**调用
   `IMEMonitor.extractItems`，失败则整个任务不启动。**这是死结**。
4. `CraftingGridCache.injectItems` 只会把物品交给**正在等待它**的 CPU（`canAccept`），
   否则落普通存储。
5. `GridStorageCache.addNode` 会自动把 `ICellContainer` 注册为 cell provider；
   `CraftingGridCache.addNode` 会自动把 `ICraftingProvider` 注册为样板提供者。
   ——两者都不需要存储总线。
6. `NetworkInventoryHandler.extractItems` 直接调 handler 的 `extractItems`；
   终端、输出总线、IO 端口、子网全部走这条路。
7. 样板通过 `getPattern()` 序列化，反序列化走 `ICraftingPatternItem.getPatternForItem`；
   CPU 任务键 = `priority` + `hashCode`；`CraftingGridCache` 的样板表是 `HashMap`。
   ——**`equals`/`hashCode` 必须稳定**。
8. `CraftingContext.isPatternComplex()` 对 `isCraftable()==false` 直接返回 false，
   不会为每个请求物品生成一个任务。
9. `MEInventoryCrafting`（非工作台样板用）有 `getAEStackInSlot(int)`，且同时写了 MC 的
   `ItemStack`，所以 `pushPattern` 里两种读法都可行。
10. LP `PipeItemsRequestLogistics` 就是 `IRequestItems`，可以直接作为请求发起者；
    `RequestTree.request(item, requester, log, acceptPartial, simulateOnly, logMissing,
    logUsed, requestFlags, info)` 支持自定义 `EnumSet<ActiveRequestType>`。
11. LP 的 `ModuleProvider.getAllItems` / `PipeItemsProviderLogistics.getAllItems` 在返回
    可用量时**已经减去了 orderManager 里在途的订单**。所以"发出请求后 LP 的可用量会自己减少"，
    桥接器不需要再维护一份在途预留。
12. LP 交付路径：`PipeTransportLogistics.handleTileReachedServer` 用
    `InventoryHelper.getTransactorFor(tile, dir).add(stack, insertion, true)` 往方块里插，
    方块只要实现 `IInventory` 即可收到物品。

第 3 条是唯一必须用 mixin 的地方。

---

## 4. 总体结构

```
                LP 网络                                   ME 网络
   ┌──────────────────────────┐   请求 / 物理交付   ┌────────────────────────────────┐
   │ PipeItemsRequestLogistics │ ◄────────────────► │ BlockLPBridge / TileLPBridge   │
   │ （请求管道，放在 LP 线末端）│  物品插入方块缓冲   │  ├ AENetworkTile  → 网格节点    │
   └──────────────────────────┘                    │  ├ ICellContainer → 只读投影    │
                                                    │  ├ ICraftingProvider → 动态样板 │
                                                    │  ├ IInventory     → 交付缓冲    │
                                                    │  └ tick：快照 / 排空 / 刷新样板 │
                                                    └────────────────────────────────┘
                                                              │ 虚拟物品（只读）
                                                              ▼
                                                    终端 / 存储 / 合成 CPU
```

### 4.1 一次合成的完整链路

1. 方块每 `refreshInterval`（默认 20）tick 从相邻请求管道拉一次 LP 可用量快照；
2. 快照与上次上报的差值用 `IStorageGrid.postAlterationOfStoredItems(...)` 推给 AE
   → 终端出现"X（LP 虚拟）"，数量 = LP 真实可用量；
3. 玩家请求 X ×N → 规划器在 `itemModel`（网络存储副本）里看到虚拟 X，
   用样板 `[虚拟X×1 → X×1]` 规划 N 次；
4. `submitJob` → `ExtractItemTask.startOnCpu` 把虚拟 X 放进 CPU inventory
   → `ci.commit` 时 mixin 对虚拟物品**只记账、返回成功**，不触碰任何存储；
5. CPU `executeCrafting` 从自己的 inventory 抽虚拟 X → `pushPattern`；
6. `pushPattern`：`RequestTree.simulate` 预检 → 通过后 `RequestTree.request`
   （`EnumSet.of(ActiveRequestType.Provide)`）→ 返回 `true`；
7. LP 把真实 X 送到请求管道 → 管道把物品插入方块（方块实现 `IInventory`）；
8. 方块 tick 把缓冲里的真实 X 注入 ME 网络；
9. `CraftingGridCache` 只把它交给正在等 X 的 CPU → 任务推进；
   没人等时才落普通存储（真实物品，不丢）。

---

## 5. 组件规格

### 5.1 `ItemLPVirtualStorage`（虚拟物品）

- NBT 键 `lpRealStack` 存真实 `ItemStack`（`stackSize = 1`）；
- 实现 `ICraftingPatternItem`，`getPatternForItem` 返回新的 `LPRequestPatternDetails`
  （**必须与注册实例 `equals`**，否则任务反序列化后找不到 medium）；
- `equals`/`hashCode` 用 `ItemIdentifier.get(real)`（不要用 `ItemStack.hashCode`，
  1.7.10 里它是 identity hash）；
- 无 NBT / NBT 非法 → 视为无效：不进投影、不进样板；
- 生存不可获得：无配方、不进创造标签、NEI 隐藏；
- 显示名 `<真实物品名>（LP 虚拟）`，图标用真实物品图标；
- 工具提示："只读投影，无法取出"。

### 5.2 `LPRequestPatternDetails`（样板）

```java
isCraftable()      -> false          // 处理样板，关键
isInputOnly()      -> false
inputs             -> { virtual.copy().setStackSize(1) }   // 构造时缓存
outputs            -> { real.copy().setStackSize(1) }      // 构造时缓存
canSubstitute()    -> false
getPattern()       -> virtual.copy()
equals/hashCode    -> ItemIdentifier.get(real)
getPriority()      -> 可配置，默认 1（不要用 0，见 §7.3）
```

`getInputs/getCondensedInputs/getOutputs/getCondensedOutputs` 返回同一缓存实例。
`getOutput(inv, world)` 仅防御性返回真实物品（处理样板不会被调用）。

### 5.3 `TileLPBridge`（核心方块）

字段（全部服务端，`worldObj.isRemote` 时只读）：

```java
Map<ItemIdentifier, Long> lpAvailable;     // LP 快照
Map<ItemIdentifier, Long> reported;        // 上次上报给 AE 的数量
Map<ItemIdentifier, LPRequestPatternDetails> patterns;
ItemStack[] buffer;                        // 交付落点，例如 9 格
int pushesThisTick;
```

接口：

- `extends AENetworkTile` → 网格节点（`getProxy()`）；
- `implements ICellContainer` → 自动注册为存储（`saveChanges(IMEInventory)` 空实现）；
- `implements ICraftingProvider` → 自动注册为样板源；
- `implements IInventory` → 接收 LP 交付。

**`getCellArray(IAEStackType)`** 只对 `ITEM_STACK_TYPE` 返回
`Collections.singletonList(virtualInventory)`，其余返回空列表。

**`VirtualInventory implements IMEInventoryHandler<IAEItemStack>`**

| 方法 | 行为 |
| --- | --- |
| `getAccess()` | `AccessRestriction.READ` |
| `getAvailableItems(out, iteration)` | 读 `reported` 缓存，**不现场算 LP** |
| `extractItems(...)` | **恒返回 `null`**（终端/总线/IO 全抽不出来） |
| `injectItems(input, ...)` | 原样返回（拒收） |
| `canAccept` / `isPrioritized` / `getSticky` | `false` |
| `getPriority()` | 可配置，默认 0 |
| `isAutoCraftingInventory()` | `false` |
| `validForPass(i)` | `true` |

**`provideCrafting(helper)`**：遍历 `patterns` 调 `addCraftingOption(this, details)`。

**tick（服务端，每 tick）**

1. 每 `refreshInterval` tick 刷新快照：
   `requester.getAvailableItems()`（内部即
   `logisticsManager.getAvailableItems(router.getIRoutersByCost())`）；
2. 计算 `reported = lpAvailable`，与旧值比较，把**差值**用
   `storageGrid.postAlterationOfStoredItems(ITEM_STACK_TYPE, deltas, new MachineSource(this))`
   推给 AE（终端数量才会刷新）；
3. 物品**集合**变化才重建 `patterns` 并 `postEvent(new MENetworkCraftingPatternChange(...))`；
   只有数量变化时不重建（避免频繁刷样板）；
4. 排空 `buffer`：注入 ME 网络（见 §5.4）；
5. 重置 `pushesThisTick`。

**`pushPattern(details, table)`**

```java
if (!isConnected() || isBusy()) return false;
long amount = 表内虚拟物品数量（优先 getAEStackInSlot，回退 getStackInSlot）；
if (amount <= 0) return false;
if (!requestLP(item, (int) amount)) return false;   // 预检 + 请求
reported.merge(item, -amount);                      // 立刻反馈，防同 tick 超发
pushesThisTick++;
return true;
```

**`isBusy()`**（背压，替代 `BlockingMode`）

```java
return !isConnected() || pushesThisTick >= maxPushesPerTick;   // 默认 4
```

`getBlockingMode()` 保持默认 `NONE`；用 `isBusy` 限流即可（`executeCrafting` 会把
busy 的 medium 放进 `knownBusyMediums`，本 tick 不再重试）。

**`requestLP(item, amount)`**（只 Provide，不 Craft）

```java
if (!requester.useEnergy(cost)) return false;
int sim = RequestTree.simulate(idStack, requester, null);
if (sim < amount) return false;
int got = RequestTree.request(idStack, requester, log, false, false, true, false,
                              EnumSet.of(ActiveRequestType.Provide), null);
return got == amount;
```

不保留"待交付"状态、不做超时、不做失败重试——按决策 4。

### 5.4 交付缓冲（`IInventory`）

- 只作为 LP 落点：`getSizeInventory()` 例如 9，`getInventoryStackLimit() = 64`；
- `isItemValidForSlot` 返回 `true`；`getStackInSlotOnClosing` 正常；
- 每 tick 排空：

```java
for (int i = 0; i < buffer.length; i++) {
    ItemStack s = buffer[i];
    if (s == null) continue;
    IAEItemStack left = AEItemStack.create(s);
    left = storageGrid.getItemInventory().injectItems(left, MODULATE, new MachineSource(this));
    if (left == null || left.getStackSize() <= 0) buffer[i] = null;
    else buffer[i].stackSize = (int) left.getStackSize();
}
```

- 注入顺序天然满足"先给正在等的 CPU，再落存储"（`CraftingGridCache` 是
  `isAutoCraftingInventory` 且排在优先级最前）；
- 注入失败（存储满）→ 留在缓冲，下 tick 重试，不丢物品。

### 5.5 `LPBridgeAccounting`（可选）

只有一处需要它：`commit` mixin 里没有方块引用。如果采用"不做在途预留"的模型（§3.11），
这里只需一个**类型判定**（是不是虚拟物品），不需要记账表。若后续要做全局防超卖，
再加 `Map<ItemIdentifier, Long> reserved`。

### 5.6 唯一的 mixin（late）

`Mixin_MECraftingInventory_Commit`：

```java
@Redirect(method = "commit", at = @At(value = "INVOKE",
    target = "Lappeng/api/storage/IMEMonitor;extractItems(Lappeng/api/storage/data/IAEStack;"
           + "Lappeng/api/config/Actionable;Lappeng/api/networking/security/BaseActionSource;)"
           + "Lappeng/api/storage/data/IAEStack;"))
private IAEStack<?> cropsnh$virtualCommitExtract(IMEMonitor<?> monitor, IAEStack<?> req,
        Actionable mode, BaseActionSource src) {
    if (LPBridgeAccounting.isVirtual(req)) {
        return req.copy();          // 假装抽到，不碰任何存储
    }
    return monitor.extractItems(req, mode, src);
}
```

配套：

- `Mixins.java` 增加 `Phase.LATE` 枚举项，`.addRequiredMod(TargetMods.AE2)`，
  `.addCommonMixins("Mixin_MECraftingInventory_Commit")`；
- `TargetMods.java` 增加 `AE2(new TargetModBuilder().setModId("appliedenergistics2"))`；
- `mixins.cropsnhenhance.late.json` 已有，无需改。

**不要**在 `MECraftingInventory.extractItems` 上注入（现在的写法）：那个方法同时被规划器
副本和 CPU inventory 使用，注入会让所有副本"凭空多出物品"，而且管不到 `commit`
对真实网络的抽取——这正是现在"规划能过、提交就死"的根因。

---

## 6. 放置与配对规则

**没有"背面"要求。** 桥接方块放在请求管道的**任意一个相邻面**即可（上下东西南北都行），
放哪一面都能收到物品。

LP 的投递链路（源码）：

1. 物品路由到目的地（请求管道）；
2. `RouteLayer.getOrientationForItem` 发现 `destination == 本管道`，调用
   `_transport.itemArrived(item, blocked)`；
3. `PipeTransportLayer.itemArrived` 收集**该管道所有相邻的非物品管道方块**
   （排除路由出口、被 `denyed` 的、被 `isLockedExit` 锁住的），
   然后 `possibleForgeDirection.get(_worldAccess.getRandomInt(size))` **随机**选一个方向；
4. `moveSolids` → `handleTileReachedServer(item, tile, item.output)`，
   用 `InventoryHelper.getTransactorFor(tile, dir.getOpposite()).add(stack, insertion, true)`
   把物品插进那个方块。

所以真正的硬约束不是"放哪一面"，而是：

- **该请求管道只能有一个相邻的非管道库存。** 如果旁边还挂着箱子 / 机器，
  物品会被随机分流，桥接器只能拿到一部分（其余进了箱子，AE 任务会卡住等剩余部分）。
- 建议把这个请求管道单独放在 LP 线末端，除 LP 管道外只贴桥接方块。
- 一个 LP 网络只允许一个桥接器：服务端按 router simpleID 去重，第二个方块
  亮红 / 聊天提示 / 不提供库存与样板；
- 方块被拆 / 断电 / 区块卸载 → `GridStorageCache.removeNode` 自动注销投影，
  终端条目消失；正在跑的任务只会"等待"，不会凭空产出。

---

## 7. 语义细节

### 7.1 "不可被取出"的覆盖面

| 路径 | 结果 |
| --- | --- |
| ME 终端 / 合成终端 | `extractItems` 恒 null → 拿不出来 |
| 输出总线 / 存储总线 / 子网 | 同上 |
| IO 端口 / ME 箱子 / 冷凝器 / 量子网络仓 | 同上 |
| AE 内部搬运（`NetworkInventoryHandler.extractItems`） | 同上 |
| 玩家把虚拟物品编码进自己的样板 | 生存不可获得 + NEI 隐藏；如需彻底堵死，第二阶段在
样板编码器加一个小 mixin 拒绝虚拟物品 |

### 7.2 与真实库存的区分

虚拟物品是**独立物品**（`ItemLPVirtualStorage` + NBT），不与真实物品混淆：
ME 里同时存在真实 X 和虚拟 X 时，终端会显示两行；AE 优先用真实的（`ExtractItemResolver`
优先级最高），LP 样板只在真实不足时被规划。

### 7.3 样板优先级

`CraftingGridCache.setPatternsFromCraftingMethods` 用 `TreeSet`（只按 `getPriority()` 比较）
去重同输出的样板。若 LP 样板 `getPriority() == 0`，会和别的 0 优先级样板互相吞掉。
所以默认给 1，并做成配置项。

### 7.4 交付失败（决策 4）

- 不追踪、不重试、不释放任何状态；
- LP 自己的订单超时（`ItemRoutingInformation` 640 tick）会把订单释放，可用量在
  下一次快照里自然回升；
- AE 侧合成任务会停在等待，玩家在合成终端里能看到卡住的任务并自行排查。

### 7.5 为什么不能把 LP 数量直接并进"真实物品"那一行

"并入 AE"有两种可能的理解，必须区分：

| 方案 | 做法 | 结果 |
| --- | --- | --- |
| A. 存储层合并（**否决**） | 投影 handler 返回**真实** `ItemStack X`，数量 = LP 库存 | 终端显示 X 的总数虚高，但 `extractItems` 拿不出来 → 数量在骗人；规划器会把它当"网络里已有的 X"直接 `ExtractItemResolver` 抽走，`commit` 再从真实网络抽同一批 → 抽不到 → 任务不启动；若把 mixin 也扩展到真实物品，就会"凭空变出 X"，而 CPU 的 `waitingFor` 永远等不到交付 |
| B. 合成层并入（**采用**） | 投影返回**虚拟物品**（独立物品 + NBT），另给"真实 X"注册动态样板 | 终端里 X 是两行：真实 X（真实数量）与"X（LP 虚拟）"（LP 可用量）；请求真实 X 时，若真实不足，AE 用 LP 样板补足；虚拟物品只是**合成输入 token**，永远抽不出来 |

关键洞察：AE 里"能看见"和"能抽出"是同一条通道（`IMEInventoryHandler`）。
只要 LP 库存要满足"不可抽取"，它就**不能**以真实物品的身份出现在存储层。
所以并入点只能是**合成层**——用样板把"LP 有货"翻译成"可以合成出真实物品"。

虚拟物品的数量不是装饰，它是**可用性闸门**：数量 = LP 当前可用量，
规划器只有在 token 够的时候才会用 LP 样板，从而天然防止"LP 没货却规划成功"。

### 7.6 并入机制的三个环节

**① 存储层（看得见）**

```
TileLPBridge extends AENetworkTile implements ICellContainer
   ↓ 网格节点激活时
GridStorageCache.addNode → inactiveCellProviders.add(bridge)
   ↓ MENetworkCellArrayUpdate → cellUpdate → addCellProvider
bridge.getCellArray(ITEM_STACK_TYPE) → [VirtualInventory]
   ↓
NetworkInventoryHandler.addNewStorage(VirtualInventory)
   ↓
终端/规划器读取 → NetworkMonitor.getStorageList()
   → VirtualInventory.getAvailableItems(out, iteration) → 返回快照
```

数量刷新不是自动的：`NetworkMonitor` 会缓存 `cachedList`，只有 `hasChanged` 才重算。
所以快照变化时必须：

```java
// 1. 先更新自己的快照（正确性来源）
lpAvailable = newSnapshot;
reported    = newSnapshot;
// 2. 再通知 AE 失效缓存 + 通知监听者（差值列表用于 watcher/终端增量）
storageGrid.postAlterationOfStoredItems(ITEM_STACK_TYPE, deltas, new MachineSource(this));
```

`postAlterationOfStoredItems` 内部只是 `monitor.postChange(true, deltas, src)`：
它把 `hasChanged` 置 true 并通知监听者，**不会**按差值去加减库存——
真实数字永远来自 `getAvailableItems`。所以"先更新快照、再 post"的顺序不能反。

**② 合成层（能规划）**

```
TileLPBridge implements ICraftingProvider
   ↓ 网格节点激活时
CraftingGridCache.addNode → craftingProviders.add(bridge) → updatePatterns()
   → bridge.provideCrafting(helper) → helper.addCraftingOption(this, LPRequestPatternDetails)
   ↓
样板表：输出 = 真实 X（可被"请求真实 X"命中）
        输入 = 虚拟 X（数量来自投影，构成可用性闸门）
```

注意样板的**输出是真实物品**，所以玩家在终端请求"真实 X"时能命中 LP 样板；
而样板的**输入是虚拟物品**，所以 CPU 执行时消耗的是 token 而不是真实库存。

**③ 执行层（走 LP）**

`pushPattern` 把 token 数量翻译成 LP 请求（`ActiveRequestType.Provide`），
LP 把真实物品送到请求管道 → 插进桥接方块的 `IInventory` 缓冲 →
桥接方块每 tick 把缓冲注入 ME 网络 → 正在等 X 的 CPU 收下 → 任务推进。

---

## 7.7 AE 优先级能做什么、不能做什么

AE2 里有三种互不相同的"优先级"，容易混淆：

| 优先级 | 作用位置 | 实际语义 |
| --- | --- | --- |
| 存储优先级（`ICellProvider.getPriority()` / 存储总线） | `NetworkInventoryHandler` | **插入**时高优先级先填满；**抽取**时从**最低**优先级开始抽干（`for (i = size - 1; i >= 0; i--)`，反直觉但确实如此） |
| 样板优先级（`ICraftingPatternDetails.getPriority()`） | `CraftingGridCache` / `CraftingCPUCluster` | 同一输出的多个样板之间谁先被选中；同时决定 CPU 内任务的执行顺序 |
| 已存物品 vs 合成 | `ExtractItemResolver.PRIORITY_EXTRACT = MAX_VALUE - 100` | **已存物品永远优先于合成**，优先级改不了 |

### 结论 1（= 采纳的行为）：AE 库存优先，不足才走 LP —— 这正是 AE 的默认语义

`ExtractItemResolver` 的优先级是 `MAX_VALUE - 100`，而样板任务的优先级只有
`PRIORITY_CRAFT_OFFSET + patterns.size() - 1`（很小的数）。规划器**先抽光 itemModel 里的真实库存，
再去合成**。所以：

> ME 里有 300 铁锭、LP 里有 1000 铁锭，请求 1200 时：先用 ME 的 300，
> 再用 LP 样板补 900，最后由 LP 管道送来 900 个真实铁锭，ME 里变成 1200。

**这是 AE 原生行为，我们不需要写任何代码去实现"AE 优先"，也不需要开关。**
反过来，想让 LP 抢先也是做不到的（除非让投影可抽取，那就违反"虚拟库存不可取出"）。

### 结论 2：样板优先级只用于"多条合成路径竞争"

如果某物品既能由 AE 机器样板产出、又能从 LP 取货（例如铁锭：矿石合成 vs LP 取货），
`lpBridge.patternPriority` 决定 AE 优先走哪条合成路径。默认 1。

**注意**：这个优先级**不会**让 LP 抢在真实库存前面（结论 1），
它只在"已经确定要合成"之后，决定用哪个样板来合成。

### 不采纳的方案（记录备查）

- **强制 LP 优先的 mixin**：在 `ExtractItemResolver` 里让规划器跳过抽取。
  代价是全局生效、LP 缺货时规划直接失败而非回退、侵入 `CraftingRequest` 剩余量。
  已按最终决定放弃。
- **GUI"从 LP 直接请求"按钮**：绕过 AE 合成直接向 LP 下单。语义清晰但非必需，暂不实现。

### 7.8 取消订单导致虚拟物品泄漏（已修复）

**现象**：在合成终端取消任务后，终端里多出真实的「LP 虚拟存储」物品，而且可以取出来；
每取消一次就多一批。

**根因**：`CraftingCPUCluster.cancel()` 最后一行调用 `storeItems()`：

```java
// CraftingCPUCluster.cancel() 第 742 行
this.storeItems(); // marks dirty
```

`storeItems()` 会把 CPU 的 `MECraftingInventory` 里剩下的**所有东西**注回真实网络：

```java
for (IAEStack<?> is : inventory.getValue()) {
    is = this.inventory.extractItems(is.copy(), Actionable.MODULATE);
    if (is != null) {
        is = monitor.injectItems(is, Actionable.MODULATE, this.machineSrc);  // ← 虚拟 token 在这里入库
    }
}
```

CPU 的 inventory 里留着没用完的虚拟 token（`ExtractItemTask.startOnCpu` 放进去的），
取消时被当成普通物品存进了存储单元 → 终端里出现一个**真实的**虚拟物品。

**修复（三层防护）**：

| 层 | 类 | 作用 |
| --- | --- | --- |
| 1. 具体流程 | `Mixin_CraftingCPUCluster_StoreItems` | `@Redirect` `storeItems` 里唯一的 `IMEMonitor.injectItems`，遇到 token 返回 `null`（丢弃） |
| 2. 边界兜底 | `Mixin_NetworkMonitor_BlockVirtual` | `@Inject(HEAD)` `NetworkMonitor.injectItems`，**任何**注入路径的 token 一律拒绝 |
| 3. 自愈 | `TileLPBridge.purgeStoredVirtualItems()` | 每 100 tick 扫一次网络，把超出投影数量的 token 抽走并销毁 |

第 2 层是关键：`NetworkMonitor.injectItems` 是所有物品注入网络的**唯一收口**
（存储单元、存储总线、接口、输入总线、CPU 回刷都经过它）。在这里拒绝 token，
等于从架构上保证"投影永远不会变成可存储物品"，即使将来 AE 加了新的回刷路径也不会漏。

第 3 层用于修复已经被污染的旧存档：网络报的数量 = `投影 + 已存`，
超过投影的部分就是幽灵，抽出来直接丢弃。日志会打印
`LPSP: voided N phantom LP virtual items at (x, y, z)`。

**为什么不能只做第 1 层**：`storeItems` 有 2 个调用点（`cancel()` 和 `updateCraftingLogic`
的完成分支），而且 AE 以后可能新增调用点。第 2 层是结构性保证。

### 7.9 虚拟物品是一个真实注册的 MC 物品

```java
public class ItemLPVirtualStorage extends Item implements ICraftingPatternItem { ... }
GameRegistry.registerItem(LP_VIRTUAL_STORAGE, "lpVirtualStorage");
```

**它是 MC 里独立注册的一个 itemID**，不是伪装成别的物品。原因：

- AE 的 `IAEItemStack` 底层必须持有 `Item` 实例，才能参与存储、网络、合成链；
- 用独立物品 + NBT（`lpRealStack`）携带真实身份，`AEItemDef.equals` 用 NBT 区分，
  所以每个 LP 物品对应**一个**唯一可寻址的 `IAEItemStack`。

它"不是正常物品"体现在：

| 手段 | 结果 |
| --- | --- |
| 贴图 | 独立贴图 `cropsnhenhance:lp_virtual_storage`（不会显示成真实物品的图标） |
| 名字 | 前缀 `[LP] 物品名`（终端里一眼可辨） |
| 创造模式 | 不在创造标签页 |
| NEI | 客户端 `NeiIntegration.hideVirtualItem()` 隐藏 |
| 配方 | 无 |
| 存储 | 投影拒绝抽取；网络层拒绝注入 |
| 缓冲 | `isItemValidForSlot` 拒收 |

所以玩家**不可能通过正常途径拿到它**，它只作为"LP 有货"的合成输入 token 在 CPU 内部流转。

### 7.10 退出世界后桥接器失效（已修复）

**现象**：退出世界再进入后，桥接器再也读不到 LP 内容，重新挖掉重放也没用。

**根因**：`LPBridgeRegistry` 是静态 `HashMap`，**退出世界时从不清理**。
LP 的 `router.getSimpleID()` 在每次进入世界时从头分配（1、2、3…），
新世界的第一个桥接器算出的 key（如 `0:1`）恰好等于旧世界残留的 key。
`claim()` 发现该 key 已被占用，而旧 tile 对象还活着（未 `isInvalid()`），
于是判定"这个 LP 网络已经被别的桥接器投影了"，新桥接器永久不工作。

**修复**：

- `CropsNHEnhance` 在 `FMLServerStartingEvent` 和 `FMLServerStoppedEvent` 都调用
  `LPBridgeRegistry.reset(...)`；
- `claim()` 增加"旧持有者已失效则自动回收"；
- `OWNED_KEYS` 用 `WeakHashMap`，避免静态表钉住已卸载世界的 tile；
- 一个 tile 换 key 时自动释放旧 key（之前会同时占两个 key，互相阻塞）。

### 7.11 防超卖：投影数量扣除 CPU 中的 token（已实现）

**问题**：投影原来直接报 LP 可用量，但规划器已经把虚拟 token 提取进 CPU 了。
两个任务会基于同一份"看起来还有 64 个"的投影各自规划 64 个，第二单必然卡死。

**算法**：

```
投影数量 = LP 可用量 − Σ(所有 CPU 库存里属于本 LP 网络的虚拟 token)
```

`countCpuTokens()` 遍历 `ICraftingGrid.getCpus()`，对每个 `CraftingCPUCluster` 调
`getListOfItem(list, CraftingItemList.STORAGE)`（该枚举值精确等于
`inventory.getAvailableItems(list)`），统计虚拟 token 并按真实物品聚合。

**为什么读 CPU 而不是维护一张全局预留表**：

| 场景 | 读 CPU 的结果 | 需要额外维护吗 |
| --- | --- | --- |
| 规划完成，64 个 token 进 CPU | 投影立即归零 | 不需要 |
| push 消耗 token | CPU token 减少，LP 同时减少，投影保持 0 | 不需要 |
| 任务取消，token 被丢弃 | CPU token 归零，投影自动恢复到 LP 可用量 | **不需要** |
| 交付失败 | token 已消耗，投影不变 | **不需要** |
| 服务器重启 | token 随 CPU 一起持久化/恢复 | **不需要** |

全局预留表需要处理上述每一条的归还逻辑，漏一个就永久偏低；读 CPU 则是**无状态**的，
CPU 库存本身就是唯一事实来源。

**刷新时机**（三个）：

1. LP 快照刷新时（每 `refreshInterval` tick）—— LP 侧数量变化；
2. **每 tick** —— CPU token 数会随 push 变化，不刷新的话投影会落后一拍；
   这一分支只重算 CPU 侧，不访问 LP 网络，开销很小；
3. **`pushPattern` 成功后立刻** —— LP 下单的瞬间投影就要掉下去，
   否则同 tick 内的另一次规划会看到旧数字。

**性能**：每 tick 遍历 CPU 列表（通常个位数）× 每个 CPU 的 token 种类数。
相比 LP 网络的 `getAvailableItems`（遍历所有 router），这个代价可以忽略。

### 7.12 方块外观：复用 AE 的黄色接口贴图（已实现）

AE 的 ME 接口本身就有 16 色染色变体，每种颜色都有一整套贴图：

```
appliedenergistics2:textures/blocks/interface/BlockInterface_Yellow.png
appliedenergistics2:textures/blocks/interface/BlockInterfaceAlternate_Yellow.png
appliedenergistics2:textures/blocks/interface/BlockInterfaceAlternateArrow_Yellow.png
```

`BlockInterface.registerBlockIcons` 里的拼法是：

```java
this.getTextureName().replace(":", ":interface/") + "_" + color.name()
```

所以贴图 ID 是 `appliedenergistics2:interface/BlockInterface_Yellow`。

**桥接器直接引用这两张贴图，不复制文件、不写渲染器**：

| 面 | 贴图 |
| --- | --- |
| 正面（`getIcon` 时朝玩家那一面） | `BlockInterfaceAlternateArrow_Yellow`（带箭头，和 AE 接口一致） |
| 其余五面 | `BlockInterface_Yellow`（普通黄色边框接口面） |

朝向由 `onBlockPlaced` 把点击面写入 metadata，`getIcon(IBlockAccess, ...)` 按 metadata 选正面。

**为什么不自己染色**：
- AE 的渲染器按 `AEColor` 索引贴图，任意颜色需要自己写渲染器；
- 复用现成贴图能和 AE 的视觉语言保持一致，玩家一眼认出"这是个接口，只是黄色的"；
- 没有任何资源文件需要维护。

---

## 8. 配置项


| 键 | 默认 | 说明 |
| --- | --- | --- |
| `lpBridge.enabled` | true | 总开关 |
| `lpBridge.refreshInterval` | 20 | LP 快照刷新间隔（tick） |
| `lpBridge.idlePowerUsage` | 1.0 | AE 待机功耗 |
| `lpBridge.debugLog` | false | 打印 LP 快照与请求日志（排查用） |
| `lpBridge.storagePriority` | 0 | 投影的存储优先级（只影响 AE 排序） |
| `lpBridge.crafting.patternPriority` | 1 | 样板优先级。**只影响与其它合成路径的竞争**（见 §7.7 结论 2），不影响已存物品的取用顺序 |
| `lpBridge.crafting.maxPushesPerTick` | 16 | 每 tick 最多接受的 LP 合成数（背压） |
| `lpBridge.crafting.requestEnergyCost` | 15 | 每次 LP 请求消耗的能量 |

---

## 9. 实施阶段

**Phase 0 — 清障（已完成）**
- `Mixins` / `TargetMods` 注册：`TargetMods` 新增 `AE2("appliedenergistics2")`、
  `LOGISTICSPIPES("LogisticsPipes")`（供 Phase 2 的 late mixin 使用）；
- 删除 `LPVirtualStorageAvailability` 全局静态表、`LPStorageExternalHandler` 空壳、
  `TileLPSR` / `TileLPSP` / `BlockLPSR` / `BlockLPSP`，以及旧的
  `Mixin_CraftingPatternTask`。

**Phase 1 — 只读投影（已完成）**

| 文件 | 作用 |
| --- | --- |
| `blocks/BlockLPBridge` | 方块注册、破坏时掉落缓冲内容 |
| `tiles/TileLPBridge` | `AENetworkTile` + `ICellContainer` + `IInventory`；快照、增量上报、缓冲排空、NBT 持久化 |
| `integration/LPVirtualInventory` | 只读投影 handler：`extractItems` 恒 `null`、`injectItems` 拒收、`getAccess = READ` |
| `integration/LPBridgeRegistry` | 按「维度 + router simpleID」保证一个 LP 网络只投影一次 |
| `items/ItemLPVirtualStorage` | 虚拟物品（NBT 携带真实物品、无效 NBT 防护、NEI 隐藏） |
| `tiles/LPRequestPatternDetails` | 样板（`isCraftable=false`、equals/hashCode、priority），Phase 2 接线用 |
| `NeiIntegration` | 隔离 NEI 调用，NEI 缺失时不影响主类加载 |
| `Config` | `lpBridge.*` 配置项 |

已实现的行为：
- 每 `refreshInterval` tick 读一次相邻请求管道的 `getAvailableItems()`，与上次快照做差；
- 先更新快照，再用 `postAlterationOfStoredItems` 推差值（`NetworkMonitor` 才会失效缓存）；
- 节点从「未激活」变「已激活」时主动 `postEvent(MENetworkCellArrayUpdate)`，
  因为 AE 只在收到该事件时重新评估 cell provider；
- 快照用 volatile 整体替换，避免 AE 合成计算线程读取时出现并发修改；
- 缓冲每 tick 注入 ME 网络（`getItemInventory().injectItems`），失败的留到下一 tick。

**Phase 2 — 端到端合成（已完成）**

新增/改动：

| 文件 | 作用 |
| --- | --- |
| `mixins/late/Mixin_MECraftingInventory_Commit` | `@Redirect` 拦截 `commit` 里 3 处 `IMEMonitor.extractItems` 和 2 处 `injectItems` |
| `integration/LPVirtualItemSupport` | 纯类型判定（不查注册表），供 mixin 安全调用 |
| `tiles/TileLPBridge` | 增加 `ICraftingProvider`：`provideCrafting` / `pushPattern` / `isBusy` / `getCrafterIcon` |
| `mixins/Mixins` | 新增 `LP_BRIDGE_CRAFTING_COMMIT`（`Phase.LATE` + `addRequiredMod(TargetMods.AE2)`） |

### commit mixin 的两个 redirect

`MECraftingInventory.commit` 的字节码里有 5 个 `IMEMonitor` 调用点（`javap -c` 实测）：

```
106: IMEMonitor.injectItems   ← 注入回滚
196: IMEMonitor.extractItems  ← 主抽取（从真实网络抽计划中的物品）
273: IMEMonitor.extractItems  ← 失败回滚
494: IMEMonitor.extractItems  ← 失败回滚
559: IMEMonitor.injectItems   ← 注入回滚
```

- `extractItems` 遇到虚拟物品 → 直接 `return request.copy()`（假装抽到，不碰任何存储）；
- `injectItems` 遇到虚拟物品 → 直接 `return null`（`null` 在 AE 语义里是"全部接受"，
  所以回滚路径永远不会把 token 塞进网络）。

**为什么必须拦 `injectItems`**：如果只拦 `extractItems`，任务失败回滚时 AE 会把"已抽取"的
虚拟物品注回网络，虚拟物品就真的进了存储，直接破坏"不可存储"的约束。

### pushPattern 的行为

```java
long amount = countVirtualTokens(table);        // 优先读 MEInventoryCrafting.getAEStackInSlot
if (!requestFromLp(identifier, amount)) return false;
```

- `requestFromLp` 用 `RequestTree.request(..., acceptPartial=false, ...,
  EnumSet.of(ActiveRequestType.Provide), null)`：
  - `Provide` = 只取 LP 现有库存，**不会触发 LP 自己的合成**；
  - `acceptPartial=false` = 要么整单承诺并下单，要么完全不下单，不会留半截订单；
  - 只有 `promised == amount` 才返回 `true`，AE 才不会等一个永远不会来的交付。
- `isBusy()` 用 `pushesThisTick >= maxPushesPerTick`（默认 16）做背压，
  `executeCrafting` 会把 busy 的 medium 放进 `knownBusyMediums`，本 tick 不再重试。

### 样板重建策略

样板只依赖 LP 物品的**集合**，不依赖数量：

```java
if (previous.equals(next)) return;   // 数量变了不重建
```

只有物品增删才重建 `patterns` 并 `postEvent(MENetworkCraftingPatternChange)`。
数量变化只走 `postAlterationOfStoredItems`，避免每 20 tick 刷一次样板缓存。

**Phase 3 — 健壮性**
- 限流细化、GUI 状态、配置项补齐。

**Phase 4 — 表现层**
- 贴图、配方、GUI（连接状态 / 缓冲内容）、工具提示完善。

---

## 10. 验收清单

1. 单 LP 网络 ↔ 单 ME 网络：终端能看到虚拟物品，点击/Shift 点击/拖拽都取不出；
   输出总线指向箱子也输出不了。
2. 请求 1 / 64 / 1000 个 LP 物品：CPU 正常推进，LP 请求被限流，ME 里出现真实物品。
3. LP 库存不足：规划期或 `pushPattern` 预检失败，任务不挂死。
4. LP 断电：不接单（`isBusy()` 为 true）。
5. 运行中拆请求管道 / 拆方块 / 卸载区块 / 重启服务器：不刷物品、不崩。
6. 同一 LP 网络放两个桥接器：第二个被拒绝。
7. 同物品同时有 AE 机器样板和 LP 样板：两者都能被规划。
8. LP 有 1000+ 物品时 TPS 正常（快照 20 tick 一次，样板不重建）。
9. 虚拟物品在 ME 终端、合成计划、CPU 状态里显示正常，且没有任何路径能把它取出。
