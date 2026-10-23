# RC Gson Fix

修复整合包在现代化运行时（gson ≥ 2.11）下 **RecurrentComplex 资源加载失败**（`Error loading resource` + `ClassCastException: Type adapter ... returned wrong type`）的问题。以 [RetroFuturaBootstrap (RFB)](https://github.com/GTNewHorizons/RetroFuturaBootstrap) 插件形式实现，不修改任何文件本体。

English summary below.

## 问题现象

服务端启动时 RecurrentComplex 打出多条：

```
[Server thread/ERROR] [reccomplex/reccomplex]: Error loading resource
java.lang.ClassCastException: Type adapter 'com.google.gson.internal.bind.TreeTypeAdapter@...'
    returned wrong type; requested class net.minecraft.nbt.NBTTagCompound
    but got instance of class net.minecraft.nbt.NBTTagInt
```

受影响的是 RC 自带的结构资源（每条 CCE 丢一个资源）。

## 根因

- 1.7.10 时代的 mod 是对着 **gson 2.2.4** 编译的，那个版本的 `Gson.fromJson` 对 adapter 的返回值**不做任何类型检查**——返回什么就用什么（泛型擦除）。
- RecurrentComplex 的 JSON↔NBT adapter 设计正好依赖这种宽松行为：`NbtToJson.getNBTTypeSmart()` 按 JSON 形状猜类型、`NBTTagVanillaDeserializer` 用 `JsonToNBT` 把字符串解析成任意 NBT 基类，实际返回的具体类型（如 `NBTTagInt`）经常和声明的请求类型（如 `NBTTagCompound`）不一致——在旧 gson 下无副作用。
- **gson 2.11.0** 给 `fromJson(JsonReader, TypeToken)` 加了严格检查 `expectedType.isInstance(result)`，不匹配直接抛 `ClassCastException`。整合包给现代 Java 运行时配了 **gson 2.14.0**（TFRU 4.4.0.0 服务端），RC 于是加载资源即炸。

## 修复方式

RFB 转换器对 `com.google.gson.Gson` 做一个结构性小手术：定位 `fromJson(JsonReader, TypeToken)` 中 `"... returned wrong type ..."` 的 `ClassCastException` 抛出块（含守卫跳转之后、`athrow` 之前的全部异常构造指令），将其整体删除——守卫跳转的两条路径随之汇合到同一处，检查不复存在，行为与 gson ≤ 2.10 完全一致（这正是整个 1.7.10 mod 生态被编译时的行为）。删块前会校验：块内无真正的控制流目标（防止有分支跳进块中间）、守卫跳转目标在 `athrow` 之后、异常构造锚点字符串匹配。

- **纯删除**：不改任何跳转、不触碰任何栈帧/异常表，补丁前后守卫指令原样
- 按结构匹配（锚点为 CCE 构造 + 特征字符串），gson ≤ 2.10 没有该检查，自动 no-op（装在客户端 gson 2.10.1 环境也无害，已实测 0 方法命中）
- 与 Crucible 自带的 jopt-simple 兼容转换器（`JoptSimpleCompatTransformer`）同一思路：为老 mod 恢复老库的宽松行为

## 安装

- 需要 lwjgl3ify（RFB 随附）。
- 放入**服务端** `mods/`（崩溃只发生在 gson ≥ 2.11 的一侧；两端都装也安全）。
- 验证：日志出现 `[RC-Gson-Fix] Restored legacy lenient Gson.fromJson adapter handling`，且 RC 不再刷 `Error loading resource`。

## 移除条件

RC 官方修复其 JSON-NBT adapter 与新版 gson 的兼容后即可删除。

---

### English

Fixes RecurrentComplex resource-loading failures (`ClassCastException: Type adapter ... returned wrong type`) on packs that ship gson >= 2.11 for modern Java runtimes. 1.7.10-era mods were compiled against gson 2.2.4, whose `Gson.fromJson` never checked adapter result types; RC's JSON-to-NBT adapter design relies on that leniency (it routinely returns e.g. `NBTTagInt` where `NBTTagCompound` was declared). gson 2.11.0 added a strict `isInstance` check that throws instead.

This RFB plugin structurally neutralizes that single check in `com.google.gson.Gson` (the guarding conditional jump becomes `POP; GOTO`, dead-codeing the throw block without touching any branch targets or stack map frames), restoring legacy semantics for the whole mod ecosystem. Pattern-matched; no-ops on gson <= 2.10.

## License

MIT
