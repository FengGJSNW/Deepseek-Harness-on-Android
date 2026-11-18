# 第三方代码与素材

Deepseek Harness on Android（DSHA）是独立开发的 Android 项目。第三方组件和素材分别遵守其自身许可；本项目代码的 GPL-3.0-only 声明不会覆盖其他权利人的许可。

- **DeepSeek Harness**：上游固定提交见 [upstream.lock.json](upstream.lock.json)，上游代码为 MIT，许可全文见 [HARNESS-LICENSE.txt](app/src/main/assets/HARNESS-LICENSE.txt)。
- **FlClash**：应用内置并扩展 [FlClash](https://github.com/chen08209/FlClash)，固定提交及子模块见 [integrations/flclash.lock.json](integrations/flclash.lock.json)，修改见 [integrations/flclash.patch](integrations/flclash.patch)。上游代码为 GPL-3.0，许可全文见 [FlClash-GPL-3.0.txt](app/src/main/assets/licenses/FlClash-GPL-3.0.txt)；构建和对应源码说明见 [integrations/README.md](integrations/README.md)。感谢原作者及贡献者。
- **prism_ui**：内置 FlClash 使用其子模块；DSHA 也参考和移植其 UI 组件与交互动效。固定版本见上述锁文件，原项目为 [prism_ui](https://github.com/chen08209/prism_ui)，许可为 GPL-3.0；完整文本见 [prism-ui-GPL-3.0.txt](app/src/main/assets/licenses/prism-ui-GPL-3.0.txt)。
- **Mihomo**：FlClash 内核包含固定版本的 [Mihomo](https://github.com/MetaCubeX/mihomo)，代码为 GPL-3.0。源代码版本见锁文件，许可全文见 [mihomo-GPL-3.0.txt](app/src/main/assets/licenses/mihomo-GPL-3.0.txt)。
- **其他组件与字体**：v86、musl、xterm、KaTeX、CommonMark、Coil、Shizuku、libsu、Zod 和字体的来源及许可证随应用资源保留；字体清单见 [fonts/manifest.json](app/src/main/assets/fonts/manifest.json)。Anthropic 风格是排版预设，使用随包 OFL 字体，不包含 Anthropic 专有字体。
- **鲸娘图标及音效**：当前图标基于 [MeteorNOX/DeepSeek-Balance-Whale-Widget](https://github.com/MeteorNOX/DeepSeek-Balance-Whale-Widget) 的美术资源及其衍生图。上游代码 MIT 不涵盖美术；上游对素材注明仅随原插件运行使用、未授予再许可。完整来源和限制见 [WHALE-PROVENANCE.md](app/src/main/assets/WHALE-PROVENANCE.md)、[WHALE-OUTPAINT.json](app/src/main/assets/WHALE-OUTPAINT.json)。
- **陪伴动画与贴纸**：来自 [PC2005-cloud/dsh-pet](https://github.com/PC2005-cloud/dsh-pet)，上游代码为 MIT，相关素材允许开源使用但禁止商用；原始作品权利归各原作者和社区作者。见 [COMPANION-SOURCES.md](app/src/main/assets/COMPANION-SOURCES.md)。
- **其他移植代码**：深度滑杆与连续圆角的上游版权和 MIT 许可分别记录在 [EFFORT-SLIDER-LICENSE.txt](app/src/main/assets/EFFORT-SLIDER-LICENSE.txt) 和 [CONTINUOUS-CORNER-LICENSE.txt](app/src/main/assets/CONTINUOUS-CORNER-LICENSE.txt)。
