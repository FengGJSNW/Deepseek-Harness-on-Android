# 第三方代码与素材

Deepseek Harness 是独立的 Android 移植项目。第三方许可按其各自范围适用。

- DeepSeek Harness Cordis 运行时来自 [锁定的上游提交](https://github.com/deepseek-ai/deepseek-harness/tree/22ddee3792402c3e70ec5f86a63c3e3e895a2f60)。构建入口见 `scripts/build-harness.mjs`，上游许可见 [HARNESS-LICENSE.txt](app/src/main/assets/HARNESS-LICENSE.txt)。
- AndroidX Compose、Material 3、JavaScriptEngine 和 libsu 使用 Apache-2.0。Shizuku API 使用 MIT。CommonMark、Coil、Zod 和相关依赖的声明随应用资源保留。
- v86、musl、xterm、KaTeX 和字体的许可证位于 `app/src/main/assets/linux/`、`console/`、`math/` 和 `fonts/`。各字体的出处与校验值见 [字体清单](app/src/main/assets/fonts/manifest.json)。
- 深度滑杆的参考实现来自 Bai-2008/dsh-better-panel，连续圆角几何参考 phamfoo/figma-squircle。完整声明见 [EFFORT-SLIDER-LICENSE.txt](app/src/main/assets/EFFORT-SLIDER-LICENSE.txt) 和 [CONTINUOUS-CORNER-LICENSE.txt](app/src/main/assets/CONTINUOUS-CORNER-LICENSE.txt)。
- 陪伴动画复用 DeepSeek Harness 的鲸尾动图，来源和 MIT 许可见 [AGENT-WHALE-LICENSE.txt](app/src/main/assets/AGENT-WHALE-LICENSE.txt)。公开版的静态鲸鱼图标与触碰音效为本发布快照新绘制和合成。此前使用过的上游代码仍保留对应署名。
