---
title: 出生点与文件哈希工具
description: SpawnPointSafety、FileHashUtil 与 HashAlgorithm 的行为和边界。
---

# 出生点与文件哈希工具

## `SpawnPointSafety`

`isSafe(ServerLevel level, BlockPos feet)` 将坐标解释为玩家脚部位置，并同时检查：脚下方块碰撞形状非空；脚部和头部方块碰撞形状为空；脚部和头部没有流体。它只验证最低物理条件，不检查地图边界、维度、队伍、重复点、世界边界或比赛状态。

```java
boolean accepted = area.contains(feet)
        && SpawnPointSafety.isSafe(level, feet)
        && !spawnPoints.contains(feet);
```

调用必须发生在服务端世界。需要检查姿态、实体碰撞或更高角色模型时，应在扩展模组中增加规则，而不是修改存档中的点位格式。

## `FileHashUtil`

`calculateHash(file, HashAlgorithm.SHA256)` 使用 8 KiB 流式缓冲；`getFileHash(file, "SHA-256")` 使用最多 8 MiB 的内存映射分块。两者返回小写十六进制字符串。文件不存在、不可读或读取中变化时会抛出 `IOException`；字符串算法版本还会抛出 `NoSuchAlgorithmException`。

`HashAlgorithm` 提供 `MD5`、`SHA1`、`SHA256` 和 `SHA512`。下载完整性通常使用 SHA-256；MD5 和 SHA-1 只适合兼容已有描述文件，不应用作安全签名。哈希计算会读取整个文件，放在下载线程或后台任务中，不要在服务器 tick 或渲染线程处理大型文件。
