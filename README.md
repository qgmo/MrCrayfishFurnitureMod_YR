MrCrayfishFurnitureMod
======================

MrCrayfish's Furniture Mod for Minecraft

Download: https://mrcrayfish.com/mods?id=cfm

这是悠然服务器使用的魔改版（6.30.x，基于原版 6.3.2-1.12.2），修了服务端的安全问题，并让电器、用水家具与科技模组联动（要用电、从流体容器取水）；电视可以播放网络视频。与原版的区别见本仓库的提交记录。

## 开发

- 构建：`./gradlew build`。**运行 Gradle 需要 JDK 25**（RetroFuturaGradle 2.x 插件为 Java 25 字节码）；编译目标是 Java 8，编译用的 JDK 8 由 Gradle 工具链（foojay）自动下载，本机已有 JDK 8 时用本机的。
- 产物在 `build/libs/`：`furniture-<版本>-1.12.2.jar` 是可放进 `mods/` 的发布包，`-dev.jar` 是开发环境用的未重混淆版本，`-sources.jar` 是源码包。
- 测试：`./gradlew test`（JUnit 5）。
- 版本号只写在 `gradle.properties` 的 `mod_version`，`Tags.VERSION` 与 `mcmod.info` 的版本由构建生成。
- 容器等默认 POSIX locale 的环境里，请用 UTF-8 locale 运行 Gradle（`LANG=C.utf8 LC_ALL=C.utf8`）。
- GitHub Actions 在 `1.12.2` 分支的推送与 PR 上自动构建并上传 jar。
