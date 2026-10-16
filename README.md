# 随机强化 / Random Enhancement

给**敌方波次单位**随机挂上强化效果的 Mindustry Java 模组。每一波敌人身上的组合都不一样，偶尔会刷出同时挂着多种强化的「精英怪」。

- **游戏版本**：Mindustry `v160` 及以上
- **平台**：桌面 / Android
- **仓库**：<https://github.com/WaNg-0-1/Random-Enhancement>

> A Java Mindustry mod that gives random buffs to the enemy army.

---

## 安装

1. 获取 `Random-Enhancement.jar`：
   - 在仓库的 **Actions** 页面选择最近一次成功的构建，从 **Artifacts** 下载；
   - 或按下方说明自行构建。
2. 放入 Mindustry 的 `mods` 目录，或在游戏内 **模组 → 导入模组** 选择该 jar。
3. 进入游戏后在 **设置 → PVE 随机强化** 中选择难度。

---

## 构建

需要 **JDK 17**。

### 桌面测试

```bash
./gradlew jar
```

产物为 `build/libs/Random-EnhancementDesktop.jar`。**该版本仅供桌面测试，无法在 Android 上运行。**

### 全平台（含 Android）

需要自行准备 Android SDK：解压后将 `ANDROID_HOME` 指向其目录，安装 API level 30 及以上，并把 `build-tools` 加入 `PATH`。

```bash
./gradlew deploy
```

产物为 `build/libs/Random-Enhancement.jar`，桌面与 Android 通用。

### 通过 GitHub Actions

仓库已配置 CI（[`.github/workflows/commitTest.yml`](.github/workflows/commitTest.yml)），每次 push / PR 都会自动执行 `./gradlew deploy` 并把产物上传为 Artifact，名称即仓库名。

> Windows 下使用 `gradlew.bat`；Linux / macOS 下若 `./gradlew` 无执行权限，先 `chmod +x gradlew`。
