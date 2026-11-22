# JHookMod | English
JHookMod is a Hook library implemented based on FFM-API. Without modifying the class bytecode, it can inject any method including the boot class loader, such as `System::exit`.
## Features:
- Pure Java implementation
- Cross-platform
- Does not modify class bytecode
- Does not use JavaAgent
- Dynamically enables NativeAccess
- Uses FFM-API to simulate JVMTIAgent
## Requirements:
- JDK 22 (for Minecraft 1.21.x)
- JDK 25 (for Minecraft 26.x)
## Supported Versions:
- Fabric 1.21 ~ 26.x
- NeoForge 1.21.1 ~ 26.x (does not support 1.21)
### Warning: This library has modified a large number of internal structures of the JVM. Even after numerous tests in various scenarios, it may still cause instability or crashes of the JVM.

## Usage Method

Public methods of the hook:
JHookUtils::hookPublic(Class<? > originalClazz, Method originalM,
Class<? > hookClazz, Method hookM)void

It will generate the bytecode for the invocation of `originalM` to execute the bytecode of `hookM`.
Parameters:
- `originalClazz`: The class where the target method is located (used for parsing `jclass`)
- `originalM`: The method to be replaced
- `hookClazz`: The class where the `hook` method is located
- `hookM`: The replacement body; the descriptors and static nature must be consistent with `originalM`

#### Note: This method internally calls `JHookUtils::hookWithBackup` 

### Hooking non-public methods:
JHookUtils::hookNonPublic(Class<? > originalClazz, Method originalM,
Class<? > hookClazz, Method hookM)void

Parameters and usage methods are the same as above.

### Hooking public methods with backup hooks
JHookUtils::hookWithBackup(Class<? > originalClazz, Method originalM,Class<? > hookClazz, Method hookM,Class<? > backupClazz, Method backupM)void

Similar to `JHookUtils::hookPublic`, additionally move the original implementation of `originalM` to `backupM`, so that the hook can call `backupM` to execute the original logic (surrounding the hook).
#### Note: This mode requires rewriting the own `ConstMethod` of the target method (assigning the name to backup). It has been tested and found that it may be rejected by the parser in some JVM runs. This method will be verified upon implementation, and in case of failure, it will automatically roll back and throw an exception, without leaving any damaged classes. For scenarios with high reliability requirements, please use `JHookUtils::hookPublic`

- `backupClazz`: The class where the backup method is located (can be `null`)
- `backupM`: The method that takes over the original implementation; the descriptor must be consistent with `originalM`
# JHookMod | 简体中文
JHookMod 是一个基于 FFM-API 实现的 Hook 库.不修改类字节码,可以注入包括 引导类加载器 在内的任何方法,比如 `System::exit`.
## 特点:
- 纯 Java 实现
- 跨平台
- 不修改类字节码
- 不使用 JavaAgent
- 动态启用 NativeAccess
- 使用 FFM-API 模拟实现 JVMTIAgent
## 环境需要:
- JDK 22 (for Minecraft 1.21.x)
- JDK 25 (for Minecraft 26.x)
## 支持版本:
- Fabric 1.21 ~ 26.x
- NeoForge 1.21.1 ~ 26.x (not support 1.21)
### 警告: 该库修改了大量 JVM 内部结构,即使已经过多场景下的多次测试,依旧将可能导致 JVM 不稳定/崩溃.

## 使用方法

### hook 公开的方法:
JHookUtils::hookPublic(Class<?> originalClazz, Method originalM,
                                  Class<?> hookClazz, Method hookM)void

- `originalM` 的调用执行 hookM 的字节码。
- `originalClazz`  目标方法所在的类（用于解析 `jclass`）
- `originalM`     被替换的方法
- `hookClazz`     hook 方法所在的类
- `hookM`         替换体；描述符与 static 性质必须与 `originalM` 保持一致

#### 注意: 该方法内部调用 `JHookUtils::hookWithBackup`

### hook 非公开的方法:
JHookUtils::hookNonPublic(Class<?> originalClazz, Method originalM,
                                     Class<?> hookClazz, Method hookM)void

参数和使用方法同上

### hook 带有备份地 hook 公开的方法
JHookUtils::hookWithBackup(Class<?> originalClazz, Method originalM,Class<?> hookClazz, Method hookM,Class<?> backupClazz, Method backupM)void

同 `JHookUtils::hookPublic`，额外把 `originalM` 的原始实现搬到 `backupM` 上,这样 hook 内部可以调用 `backupM` 来执行原版逻辑（环绕钩子）
#### 注意：这个模式需要改写目标方法自己的 `ConstMethod`（把名字让给 backup）， 实测在部分 JVM 运行中会被解析器拒绝。本方法会在落笔后校验，失败时自动回滚并抛异常，不会留下损坏的类。对可靠性要求高的场景请用 `JHookUtils::hookPublic`

- `backupClazz` 备份方法所在的类（可为 `null`）
- `backupM`     承接原始实现的方法；描述符必须与`originalM` 一致
