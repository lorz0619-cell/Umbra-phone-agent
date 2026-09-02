# Umbra Benchmark Three-Round Summary

| # | Task | Mode | R1 | R2 | R3 | Failure note |
|---:|---|---|:--:|:--:|:--:|---|
| 1 | 打开QQ | MAIN_SCREEN | PASS | PASS | PASS | oracle 的感知窗口问题 |
| 2 | 打开QQ | VIRTUAL_DISPLAY | PASS | PASS | PASS |  |
| 3 | 打开扣扣 | MAIN_SCREEN | PASS | PASS | PASS | oracle 的感知窗口问题 |
| 4 | 打开扣扣 | VIRTUAL_DISPLAY | PASS | PASS | PASS | oracle 的感知窗口问题 |
| 5 | 打开相机准备拍照 | MAIN_SCREEN | PASS | PASS | PASS |  |
| 6 | 打开相机准备拍照 | VIRTUAL_DISPLAY | PASS | PASS | PASS |  |
| 7 | 打开相机准备录像 | MAIN_SCREEN | FAIL | FAIL | FAIL | 人工判定失败 |
| 8 | 打开相机准备录像 | VIRTUAL_DISPLAY | FAIL | FAIL | FAIL | 人工判定失败 |
| 9 | 设置每天早上7点15分的闹钟，标签为 UmbraBench | MAIN_SCREEN | PASS | PASS | PASS |  |
| 10 | 设置每天早上7点15分的闹钟，标签为 UmbraBench | VIRTUAL_DISPLAY | PASS | PASS | PASS |  |
| 11 | 创建明天下午3点到3点半的日历事件，标题为 Umbra Benchmark | MAIN_SCREEN | PASS | PASS | PASS |  |
| 12 | 创建明天下午3点到3点半的日历事件，标题为 Umbra Benchmark | VIRTUAL_DISPLAY | PASS | PASS | PASS |  |
| 13 | 导航到上海站一号线一号口 | MAIN_SCREEN | PASS | PASS | PASS |  |
| 14 | 导航到上海站一号线一号口 | VIRTUAL_DISPLAY | PASS | PASS | PASS |  |
| 15 | 导航到离我最近的银行 | MAIN_SCREEN | PASS | PASS | PASS |  |
| 16 | 导航到离我最近的银行 | VIRTUAL_DISPLAY | PASS | PASS | PASS |  |
| 17 | 新建联系人 Umbra测试联系人，手机号10000000000 | MAIN_SCREEN | PASS | PASS | PASS |  |
| 18 | 新建联系人 Umbra测试联系人，手机号10000000000 | VIRTUAL_DISPLAY | PASS | PASS | PASS |  |
| 19 | 给手机号10000000000，编辑短信“[UmbraBench] 固定消息测试，请忽略” | MAIN_SCREEN | PASS | PASS | PASS |  |
| 20 | 给手机号10000000000，编辑短信“[UmbraBench] 固定消息测试，请忽略” | VIRTUAL_DISPLAY | PASS | PASS | PASS |  |
| 21 | 打开QQ，进入联系人“我的电脑”的聊天页面 | MAIN_SCREEN | PASS | PASS | PASS |  |
| 22 | 打开QQ，进入联系人“我的电脑”的聊天页面 | VIRTUAL_DISPLAY | PASS | PASS | PASS |  |
| 23 | 给QQ联系人“我的电脑”发送“[UmbraBench] 固定消息测试，请忽略” | MAIN_SCREEN | FAIL | PASS | PASS | 人工判定失败 |
| 24 | 给QQ联系人“我的电脑”发送“[UmbraBench] 固定消息测试，请忽略”（主屏未激活输入法） | VIRTUAL_DISPLAY | PASS | PASS | PASS |  |
| 25 | 给QQ联系人“我的电脑”发送“[UmbraBench] 固定消息测试，请忽略”（主屏激活输入法） | VIRTUAL_DISPLAY | PASS | PASS | PASS |  |
| 26 | 给QQ联系人“我的电脑”写一句不超过20个字的生日祝福并发送 | MAIN_SCREEN | PASS | PASS | PASS |  |
| 27 | 给QQ联系人“我的电脑”写一句不超过20个字的生日祝福并发送 | VIRTUAL_DISPLAY | PASS | PASS | PASS |  |
| 28 | 在手机存储中找到 Download 里的 umbra-benchmark.txt | MAIN_SCREEN | FAIL | FAIL | FAIL | 手机文件系统复杂，需要精确提示词 |
| 29 | 在手机存储中找到 Download 里的 umbra-benchmark.txt | VIRTUAL_DISPLAY | FAIL | FAIL | FAIL | 手机文件系统复杂，需要精确提示词 |
| 30 | 在网易云音乐中打开每日推荐的第一首歌，打开正确歌曲的详情页 | MAIN_SCREEN | PASS | PASS | PASS |  |
| 31 | 在网易云音乐中打开每日推荐的第一首歌，打开正确歌曲的详情页 | VIRTUAL_DISPLAY | PASS | PASS | PASS |  |
| 32 | 在网易云音乐中打开每日推荐的第一首歌，打开正确歌曲的详情页，并播放 | MAIN_SCREEN | PASS | PASS | PASS |  |
| 33 | 在网易云音乐中打开每日推荐的第一首歌，打开正确歌曲的详情页，并播放 | VIRTUAL_DISPLAY | PASS | PASS | PASS |  |
| 34 | 在网易云音乐中打开每日推荐的第一首歌，打开正确歌曲的详情页，在评论框输入“[UmbraBench] 评论草稿” | MAIN_SCREEN | PASS | PASS | PASS |  |
| 35 | 在网易云音乐中打开每日推荐的第一首歌，打开正确歌曲的详情页，在评论框输入“[UmbraBench] 评论草稿” | VIRTUAL_DISPLAY | FAIL | FAIL | FAIL | 人工判定失败 |
| 36 | 在哔哩哔哩搜索“Android 无障碍开发”，停在搜索结果页面 | MAIN_SCREEN | PASS | PASS | PASS |  |
| 37 | 在哔哩哔哩搜索“Android 无障碍开发”，停在搜索结果页面 | VIRTUAL_DISPLAY | PASS | PASS | PASS |  |
| 38 | 在哔哩哔哩搜索“Android 无障碍开发”，停在搜索结果页面，打开播放量最高的视频 | MAIN_SCREEN | PASS | PASS | PASS |  |
| 39 | 在哔哩哔哩搜索“Android 无障碍开发”，停在搜索结果页面，打开播放量最高的视频 | VIRTUAL_DISPLAY | PASS | PASS | PASS |  |
| 40 | 打开明日方舟 | MAIN_SCREEN | PASS | PASS | PASS | oracle 的感知窗口问题 |
| 41 | 打开明日方舟 | VIRTUAL_DISPLAY | PASS | PASS | PASS |  |
| 42 | 打开浏览器，并搜索”umbra“ | MAIN_SCREEN | PASS | PASS | PASS | oracle 的感知窗口问题 |
| 43 | 打开浏览器，并搜索”umbra“ | VIRTUAL_DISPLAY | PASS | PASS | PASS | oracle 的感知窗口问题 |
| 44 | 打开浏览器，并搜索”umbra“并进入第一个推荐项 | MAIN_SCREEN | FAIL | PASS | PASS | 人工判定失败 |
| 45 | 打开浏览器，并搜索”umbra“并进入第一个推荐项 | VIRTUAL_DISPLAY | PASS | FAIL | PASS | 人工判定失败 |
| 46 | 打开便签，创建新便签，并输入”[UmbraBench] 固定文字测试“ | MAIN_SCREEN | PASS | PASS | PASS |  |
| 47 | 打开便签，创建新便签，并输入”[UmbraBench] 固定文字测试“（主屏未激活输入法） | VIRTUAL_DISPLAY | PASS | PASS | PASS |  |
| 48 | 打开便签，创建新便签，并输入”[UmbraBench] 固定文字测试“（主屏激活输入法） | VIRTUAL_DISPLAY | PASS | FAIL | FAIL | 主屏输入法被激活 |
| 49 | 打开美团，点一杯霸王茶姬的大杯伯牙绝弦，外卖到默认地址 | MAIN_SCREEN | PASS | PASS | PASS |  |
| 50 | 打开美团，点一杯霸王茶姬的大杯伯牙绝弦，外卖到默认地址 | VIRTUAL_DISPLAY | PASS | FAIL | PASS | 人工判定失败 |
| 51 | 在网易云音乐中搜索歌曲 loveme | MAIN_SCREEN | PASS | PASS | PASS |  |
| 52 | 在网易云音乐中搜索歌曲 loveme | VIRTUAL_DISPLAY | PASS | FAIL | FAIL | 人工判定失败 |

## Failure Analysis
- **main-camera-07** [MAIN_SCREEN] 打开相机准备录像: 人工判定失败
- **virtual-camera-08** [VIRTUAL_DISPLAY] 打开相机准备录像: 人工判定失败
- **main-social-message-23** [MAIN_SCREEN] 给QQ联系人“我的电脑”发送“[UmbraBench] 固定消息测试，请忽略”: 人工判定失败
- **main-file-28** [MAIN_SCREEN] 在手机存储中找到 Download 里的 umbra-benchmark.txt: 手机文件系统复杂，需要精确提示词
- **virtual-file-29** [VIRTUAL_DISPLAY] 在手机存储中找到 Download 里的 umbra-benchmark.txt: 手机文件系统复杂，需要精确提示词
- **virtual-music-35** [VIRTUAL_DISPLAY] 在网易云音乐中打开每日推荐的第一首歌，打开正确歌曲的详情页，在评论框输入“[UmbraBench] 评论草稿”: 人工判定失败
- **main-browser-44** [MAIN_SCREEN] 打开浏览器，并搜索”umbra“并进入第一个推荐项: 人工判定失败
- **virtual-browser-45** [VIRTUAL_DISPLAY] 打开浏览器，并搜索”umbra“并进入第一个推荐项: 人工判定失败
- **virtual-notes-48** [VIRTUAL_DISPLAY] 打开便签，创建新便签，并输入”[UmbraBench] 固定文字测试“（主屏激活输入法）: 主屏输入法被激活
- **virtual-commerce-50** [VIRTUAL_DISPLAY] 打开美团，点一杯霸王茶姬的大杯伯牙绝弦，外卖到默认地址: 人工判定失败
- **round2-netease-search-virtual** [VIRTUAL_DISPLAY] 在网易云音乐中搜索歌曲 loveme: 人工判定失败

## Round-Level Summary

| Round | Tasks | Pass | Fail | Verified success rate |
|---|---:|---:|---:|---:|
| R1 | 52 | 45 | 7 | 0.8654 |
| R2 | 52 | 43 | 9 | 0.8269 |
| R3 | 52 | 45 | 7 | 0.8654 |
