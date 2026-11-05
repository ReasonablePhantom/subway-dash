# Subway Dash (地铁极速跑酷) 🚇🏃💨

这是一个类似于《地铁跑酷 (Subway Surfers)》的 3 轨道极速跑酷游戏，专为发布到 **Google Play Console** 开发设计。

---

## 📋 Google Play 填表指南 (快速复制)

在您的 Google Play Console 创建应用页面中，请按如下建议填写：

| 字段 | 填写推荐值 | 说明 |
| :--- | :--- | :--- |
| **App name (应用名称)** | `Subway Dash: Track Runner` 或 `Subway Dash 3D` | 简短好记（建议在 30 字符内，不要包含侵权词汇如 Official 等） |
| **Package name (包名)** | `com.brucelyu.subwaydash` | 全球唯一标识符。填好后点击右侧「查看是否有货」(Check availability) 确认可用 |
| **Default language (默认语言)** | `English (United States) – en-US` | 建议保留 en-US 或按您目标受众选择简体中文 |
| **App or game (应用或游戏)** | 勾选 **Game (游戏)** | 分类请选游戏 |
| **Free or paid (免费或付费)** | 勾选 **Free (免费)** | 注意：发布为免费后无法直接改成付费应用 |
| **Declarations (声明)** | **全部勾选** 3 个复选框：<br>1. Confirm app meets Developer Program Policies<br>2. Accept Play App Signing Terms<br>3. Accept US export laws | Google Play 强制要求的合规与签名许可 |

点击右下角 **「创建应用 (Create app)」** 即可完成第一步！

---

## 🎮 游戏核心玩法与特性

1. **三轨道穿梭 (3 Lanes)**：
   - 经典地铁跑酷经典视角，轨道从远方地平线透视延伸。
2. **手势与操作**：
   - **瞬时滑动响应**：手指在屏幕任意位置滑动即可瞬间变道/跳跃/滑铲，无抬手延迟，支持连招！
   - **底部四大虚拟按键**：左下与右下配置大尺寸触摸按键（【◀ 左道】、【▲ 跳跃】、【▼ 滑铲】、【▶ 右道】）。
   - 键盘控制：`A`/`D` 或 `←`/`→` 变道，`W`/`↑`/空格 跳跃，`S`/`↓` 滑铲。
3. **动态渐进加速系统 (Progressive Acceleration)**：
   - **新手平稳期 (0 ~ 150m)**：基础速度 5.2，视野开阔，从容适应节奏。
   - **提速阶段 (150 ~ 500m)**：速度提升至 1.5x，触发提速提示音与横幅。
   - **极速狂飙 (500 ~ 1200m)**：速度飙升至 2.0x+，出现高速辐射风痕线条。
   - **超光速极限 (1200m+)**：速度突破 2.5x~3.0x，屏边烈焰光晕，挑战人类神经极限！
   - 变道灵敏度随车速自适应提高，确保高速时也能闪电切道。
4. **云端战绩上传与排行榜 (brucelyu.cc)**：
   - 游戏结算时弹出上传战绩卡片，支持自定义玩家昵称。
   - 自动采集记录日期与时间（如 `2026-11-05 14:35:20`），一键上传至云端数据库。
   - 个人博客集成：可在 [https://www.brucelyu.cc/subway-dash](https://www.brucelyu.cc/subway-dash) 实时查看前 50 巅峰跑酷英雄榜！
3. **障碍物与收集物**：
   - 🚇 **地铁列车 (Train)**：不可翻越，必须及时变道避让。
   - 🚧 **矮栅栏 (Hurdle)**：必须跳跃翻过。
   - 🛑 **高架指示牌 (High Barrier)**：必须下蹲滑铲通过。
   - 🪙 **旋转金币 (Coins)**：轨道中成排的金币，拾取增加积分与金币数，带有拾取火花粒子与清脆音效。
4. **性能与兼容性**：
   - 采用 Android 原生 60FPS SurfaceView 渲染架构，零外部沉重引擎依赖，APK 仅约 2~3MB，秒开秒玩。
   - 内置程序化合成音效（SoundPool / AudioTrack），不需要外置巨额 MP3 资源。
   - 本地自动保存历史最高分（Best Score）。

---

## 🚀 运行与测试方法

### 1. 立即在浏览器中试玩（无需编译）
在终端中运行：
```bash
open preview/index.html
```
或者直接用浏览器打开项目中的 `preview/index.html`，即可使用键盘/鼠标立即试玩！

---

### 2. 在 Android Studio 中编译与打包

1. 打开 **Android Studio**。
2. 点击 **Open**，选择当前文件夹：`/Users/brucelyu/Documents/antigravity/First Play App`。
3. 等待 Gradle 同步完成。
4. 连接安卓手机（开启 USB 调试）或启动模拟器，点击顶部绿色三角形 **Run** 按钮即可在手机上体验。

---

### 3. 生成 Google Play 上架用的 `.aab` (Android App Bundle)

1. 在 Android Studio 顶部菜单栏选择：
   `Build` -> `Generate Signed Bundle / APK...`
2. 选择 **Android App Bundle (.aab)** -> 点击 Next。
3. 创建或选择你的发布密钥库 (Keystore) 并输入密码。
4. 选择 **release** 构建变体，勾选 V1/V2 签名。
5. 点击 **Finish**，Android Studio 会在 `app/release/` 下生成 `app-release.aab` 文件。
6. 直接上传该 `.aab` 到 Google Play Console 的「正式版」或「内部测试」发布轨道即可！
