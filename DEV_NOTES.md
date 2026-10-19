# WorldReplay 构建与环境备忘

> 给未来的自己（或下一个 AI 会话）：这里记录所有踩过的坑和当前可用的工具链。改环境前先读这份。

## 工具链（2026-10-19 验证可用）

| 项 | 值 | 备注 |
|---|---|---|
| JDK | `D:\tools\jdk21` | 唯一可用 JDK；core 用它产出 17 字节码 |
| Gradle | `D:\tools\gradle811clean\gradle-8.11\bin\gradle.bat` | **必须用 clean 版**；`D:\tools\gradle-8.11` 的 worker jar 损坏（CNFE），别用 |
| GRADLE_USER_HOME | `D:\tools\gradle-home` | 所有 build/server 命令都要设 |
| NeoForge 模块 | `D:\tools\gradle-9.4.1` | 仅 nf-1.21.1 用（moddev 2.0.78 需要 9.x） |

标准构建命令（每模块独立，**不要在 repo 根目录跑**——根 settings.gradle 会拉起所有模块）：

```powershell
Set-Location 'F:\vibe coding工程\sfx\worldreplay\<模块>'
$env:JAVA_HOME='D:\tools\jdk21'; $env:Path="$env:JAVA_HOME\bin;$env:Path"; $env:GRADLE_USER_HOME='D:\tools\gradle-home'
& 'D:\tools\gradle811clean\gradle-8.11\bin\gradle.bat' build --console=plain
```

## 网络限制

- `repo.maven.apache.org` DNS 不通 → **Fabric 模块禁止 mavenCentral()**，settings.gradle 用阿里云 + Fabric maven
- 大文件下载（Mojang server.jar、Gradle dist）会被中断 → 用 `curl.exe -L -C - --noproxy '*' --ssl-no-revoke` 循环续传
- Modrinth 上传：`curl.exe --noproxy '*' --ssl-no-revoke`，脚本 `upload_v2.ps1`（已泛化：`-Version -Changelog -JarPath -GameVersions -Loaders`）

## UTF-8 红线

**PS 5.1 的 `Get-Content -Raw` + `Set-Content -Encoding utf8` 会把 UTF-8 源文件按 GBK 解析再写回 → 中文注释变乱码 + 换行合并 + 代码损坏（已发生过一次，13 个文件报废）**。

安全做法：
- 用 DSH 的 `write` / `edit` 工具
- 或 `[System.IO.File]::ReadAllText($p)` / `WriteAllText($p, $t, [System.Text.UTF8Encoding]::new($false))`
- PowerShell 脚本里写中文注释也有同款风险（upload_v2.ps1 曾因此语法报错）→ PS 脚本注释用英文

## 冒烟测试（已验证 2026-10-19）

三个平台都能真实启动 dedicated server（mod 加载 + 世界就绪）：
- NeoForge 1.21.1：`nf-1.21.1` → gradle-9.4.1 `runServer` → 25565（常驻测试服）
- Fabric 1.20.1：`fabric-1.20.1` → 811clean `runServer` → 25566（run/ 目录已配 eula，可复跑）
- Fabric 1.19.4：`fabric-1.19.4` → 811clean `runServer` → 25567（同上）

Loom 首次跑某 MC 版本会下载 `piston-data.mojang.com/.../server.jar`（1.19.4 是 47 515 675 B），
断了就手动 curl 续传到 `D:\tools\gradle-home\caches\fabric-loom\<版本>\minecraft-server.jar`。

## core 自检

`core/src/test/java/.../CoreSelfTest.java`：零依赖（mavenCentral 不可达装不了 JUnit），
`gradle selfTest` 或 `gradle check` 跑，29 项断言，失败即构建失败。改 ReplayEngine/RecordingSession/编解码后必跑。

## Modrinth

- 项目 `SvKPzm09`（WorldReplay），token 在 upload_v2.ps1 里
- 版本命名：`<semver>+<mc>-<loader>`（如 `0.2.1+1.20.1-fabric`）
- **上传前务必核对 jar 内 `fabric.mod.json` 的 version 字段与 version_number 一致**（0.2.1 首传时踩过：build.gradle 忘升版本，jar 元数据还是 0.2.0，只能删了重传）
- 项目 status=processing 时匿名 API 404 是正常的，带 token 能查

## 已知事项

- GlowTrack 巡检项：`D:\player-tracker` 不存在（全盘搜过 tracker/glow/player 关键词），等用户给路径
- Fabric 客户端 GUI（录制库/回放/重命名）只在服务端侧验证过加载，**客户端渲染链路未实测**（无 Fabric 客户端环境）
