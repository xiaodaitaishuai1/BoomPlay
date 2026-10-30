# 仓库指南

## 项目结构与模块组织

本项目是单模块 Android 应用。Kotlin 源码位于 `app/src/main/java/com/boomplay/musicplayer/`，按职责放入 `library/`、`playback/`、`ui/` 和 `utils/`；Activity 保留在应用根包中。资源位于 `app/src/main/res/`：界面布局放在 `layout/`，形状与图标放在 `drawable/`，公共字符串、颜色和主题放在 `values/`。JVM 测试在 `app/src/test/java/` 下镜像生产代码的包结构。产品截图存放在仓库根目录，实施计划放在 `docs/`。

## 构建、测试与开发命令

在仓库根目录使用项目自带的 Gradle Wrapper：

- `.\gradlew.bat assembleDebug`：构建调试版 APK。
- `.\gradlew.bat installDebug`：将调试版安装到已连接的模拟器或设备。
- `.\gradlew.bat testDebugUnitTest`：运行本地 JUnit 测试。
- `.\gradlew.bat lintDebug`：检查调试变体的 Android 与 Kotlin 问题。
- `.\gradlew.bat check`：运行所有已配置的验证任务。
- `.\gradlew.bat clean`：清除构建产物，用于排查缓存导致的问题。

开发环境需安装 JDK 21（Gradle 与广告 SDK 要求）和包含 API 36 的 Android SDK；应用 JVM 目标保持 17。

## 编码风格与命名约定

遵循 `gradle.properties` 配置的 Kotlin 官方风格：使用四空格缩进，并在有利于减少差异时保留尾随逗号。类名使用 `PascalCase`，函数和属性使用 `camelCase`，包名全部小写。Android 资源使用 `snake_case`，并采用清晰前缀，例如 `activity_player.xml`、`item_song.xml` 和 `bg_surface.xml`。面向用户的文本应放入 `strings.xml`；视图访问优先使用 View Binding。

## 测试规范

本项目使用 JUnit 4。测试类以被测对象命名，例如 `SongLibraryTest`；测试方法使用可读的行为描述，例如 `` `next wraps to first item` ``。过滤、队列导航、格式化等与 Android 平台无关的逻辑应添加快速单元测试。设备相关测试放在 `app/src/androidTest/java/`，使用 `.\gradlew.bat connectedDebugAndroidTest` 运行。目前未配置覆盖率阈值，但新增分支和回归问题必须覆盖。

## 提交与拉取请求规范

当前工作区不含 Git 历史，无法推断既有约定。提交信息应简短、使用祈使语气并带明确前缀，例如 `feat: add repeat mode` 或 `fix: handle empty media queue`。拉取请求应说明行为变化、列出验证命令、关联相关 Issue；涉及界面修改时附上修改前后的截图。

## 安全与 Agent 注意事项

不要提交 `local.properties`、密钥库、签名凭据或机器专属路径。将 `app-config.gradle` 中的值视为配置项，并在发布前替换占位内容。新增图标可使用 [Iconfont](https://www.iconfont.cn/) 或 [Google Material Symbols Rounded](https://fonts.google.com/icons?icon.query=music&icon.style=Rounded)；使用前确认授权，并保持视觉风格一致。

## 智能代理工作约定

- 默认使用简体中文回复，除非明确要求英文；先给结论，再用日常语言说明原因、影响和建议，必要术语需先解释。
- 涉及最新信息、规则、价格、公告或生产状态时，先通过工具核验事实再下结论。给建议时明确推荐方案及原因，不回避问题的复杂度。
- 遵循 KISS、YAGNI、DRY 和 SOLID：优先最小可行改动，避免过度设计；保持职责单一、接口清晰、依赖可替换。
- 工作流程为“理解、规划、定位、修改、验证、汇报”：先阅读代码、配置和日志；复杂任务用 `update_plan`
  拆分为可验证步骤；搜索优先使用 `rg`/`rg --files`；修改使用 `apply_patch`；先运行最相关的小范围测试，再逐步扩大验证。
- 任务独立且适合并行时，可采用 Subagent-Driven 方式减少上下文污染。最终汇报需说明完成情况、验证结果、风险和建议的下一步。

## 隔离工作区位置

- 始终选择 项目内 .worktrees/

## 关于Policy类

- 不要使用 Policy 类 相关判断写在类里面,公共的判断可以写一个 object的utils

## 关于 `strings.xml`

- 默认为英文
- 其他语言按照文件去翻译
- 绝对的禁止中文
- 不要新建xx_strings 文件,直接写在strings里面,按照功能放一起

## 关于部分控件

- 不允许使用 `ImageButton`   需要改为 `ImageFilterView`
- `ImageView` 需要改为 `ImageFilterView`
- 尽量使用 `ConstraintLayout` 不要使用 `LinearLayout`

## 关于`子智能体`

- 最多只允许开启两个 `子智能体`

## 关于strings

- 禁止新增 strings_xx 所有的字符串 写在strings 里面
- 新增的往下放,相同的功能字符串 放一起
- 每次新增strings 都要给每个已有的strings语言文件翻译
- 禁止自动新增strings文件

## 关于模拟器

- 不要主动新建模拟器

## 关于 `/docs` 文件夹

- `/docs` 文件夹不要提交
