本地 Gradle 发行包（免 wrapper 从网上下载 Gradle）
==================================================

1）手动下载与本项目一致的 Gradle 压缩包：
   gradle-wrapper.properties 中声明的版本应为：gradle-8.12.1-bin.zip

   官方直链：
   https://services.gradle.org/distributions/gradle-8.12.1-bin.zip

   腾讯云 Gradle 镜像目录（在列表中选 gradle-8.12.1-bin.zip）：
   https://mirrors.cloud.tencent.com/gradle/

   其它镜像请自行检索「gradle-8.12.1-bin.zip」，校验文件名与上文一致。

2）将下载好的文件改名为下面这个名字，放到本目录（与 README 同级）：
   gradle-8.12.1-bin.zip

3）在项目根目录执行，把 wrapper 改成使用上述本地 ZIP（脚本会对含中文等非 ASCII 的路径做百分号编码，供 Java 解析）：

   PowerShell:
     powershell -ExecutionPolicy Bypass -File .\gradle\use-local-gradle-dist.ps1

   若你已信任脚本，也可以在资源管理器中右键「使用 PowerShell 运行」gradle\use-local-gradle-dist.ps1

   恢复原先用官方地址在线拉取 Gradle（不推荐弱网）：
     powershell -ExecutionPolicy Bypass -File .\gradle\use-online-gradle-dist.ps1

说明
----
· 本项目 gradle.properties 使用的是 Minecraft / Fabric等依赖，
  第一次执行「构建」仍可能从 Maven / Fabric / Mojang 拉取依赖；
  仅「Gradle 本体」改为走本地 ZIP，不重下 gradle-xx-bin.zip。
· 若希望构建阶段也完全离线：先在一台联网机器上好一次 .\gradlew.bat build，
  再把这台机的 %USERPROFILE%\.gradle\caches（及 wrapper/dists 若需要）
  复制到离线机相同位置后，可加参数 --offline。
  （路径以本机用户名下的 .gradle 为准。）
