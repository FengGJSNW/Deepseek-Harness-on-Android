# 陪伴形象与表情来源

入口：https://aigengtu.com/DeepSeek%E5%A8%98 和 https://aigengtu.com/games/pet 。后者链接至 PC2005-cloud/dsh-pet。

动画与 5 张贴纸取自 https://github.com/PC2005-cloud/dsh-pet ，提交 f8fc9a8a18dafb9b3545b56b701ad7bc0fce2e89。动画为 assets/webm 下的待机呼吸休闲、工作状态-思考冒泡、点击回应-开心跃动、点击回应-元气挥手、工作状态-雀跃庆祝、工作状态-垂头叹气冒汗、原地小憩沉眠。表情为 assets/memes 下的哦鲸鲸、可爱、深度睡眠、厉害了我的鲸、就一碗。

上游代码为 MIT；素材（动画/提示词/源视频）允许开源使用，禁止商用。表情图的原始作品权利归原作者及社区作者。保留原图，不宣称原创或获得商业再许可。

格式转换：原 VP9 alpha WebM 取中央正方形视口，转为 256×256 / 16fps 动画 WebP，保留透明度与原动作；Android ImageDecoder / AnimatedImageDrawable 本地播放，不调用生成模型。原“哦鲸鲸”主图仍来自 MeteorNOX，见 WHALE-SOURCE.json 和 WHALE-PROVENANCE.md。

## 0.3.3 形象筛选

表情小册使用固定提交的九张女仆小鲸鱼娘原图：哦鲸鲸、可爱、就一碗、深度睡眠、Ciallo、吃饱饱摸肚子、压力一只大肥鱼、假如给我三天多模态、誓死践行开源精神。未使用的旧“厉害了我的鲸”移出 APK。新增素材均为原文件复制，没有生成、描画或改造人物。
