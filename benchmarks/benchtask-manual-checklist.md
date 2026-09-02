# Umbra Benchtask 人工计分清单

> 只关注 `Manual=YES` 的任务。运行器会显示当前 task id 和 instruction；到最终页面后，根据 `Prompt` 回答 `y`=通过、`n`=失败、`s`=跳过。

| # | Task ID | Mode | Reps | Manual | Instruction | Final check |
|---:|---|---:|---:|---|---|---|
| 1 | main-launch-01 | MAIN_SCREEN | 3 | AUTO | 打开QQ |  |
| 2 | virtual-launch-02 | VIRTUAL_DISPLAY | 3 | AUTO | 打开QQ |  |
| 3 | main-launch-03 | MAIN_SCREEN | 3 | AUTO | 打开扣扣 |  |
| 4 | virtual-launch-04 | VIRTUAL_DISPLAY | 3 | AUTO | 打开扣扣 |  |
| 5 | main-camera-05 | MAIN_SCREEN | 3 | YES | 打开相机准备拍照 | 相机是否已打开拍照界面，并且没有实际拍照？ |
| 6 | virtual-camera-06 | VIRTUAL_DISPLAY | 3 | YES | 打开相机准备拍照 | 接管后相机是否显示拍照界面，并且没有实际拍照？ |
| 7 | main-camera-07 | MAIN_SCREEN | 3 | YES | 打开相机准备录像 | 相机是否已打开录像界面，并且没有实际录像？ |
| 8 | virtual-camera-08 | VIRTUAL_DISPLAY | 3 | YES | 打开相机准备录像 | 接管后相机是否显示录像界面，并且没有实际录像？ |
| 9 | main-system-tool-09 | MAIN_SCREEN | 3 | YES | 设置每天早上7点15分的闹钟，标签为 UmbraBench | 闹钟界面是否显示了每天 07:15、标签 UmbraBench，并且没有保存？ |
| 10 | virtual-system-tool-10 | VIRTUAL_DISPLAY | 3 | YES | 设置每天早上7点15分的闹钟，标签为 UmbraBench | 接管后闹钟界面是否正确且没有保存？ |
| 11 | main-system-tool-11 | MAIN_SCREEN | 3 | YES | 创建明天下午3点到3点半的日历事件，标题为 Umbra Benchmark | 日历事件是否填写了明天 15:00-15:30、标题 Umbra Benchmark，并且停在保存前？ |
| 12 | virtual-system-tool-12 | VIRTUAL_DISPLAY | 3 | YES | 创建明天下午3点到3点半的日历事件，标题为 Umbra Benchmark | 接管后日历事件是否正确且未保存？ |
| 13 | main-system-tool-13 | MAIN_SCREEN | 3 | YES | 导航到上海站一号线一号口 | 导航是否打开并显示“上海站一号线一号口”？ |
| 14 | virtual-system-tool-14 | VIRTUAL_DISPLAY | 3 | YES | 导航到上海站一号线一号口 | 接管后导航是否显示“上海站一号线一号口”？ |
| 15 | main-system-tool-15 | MAIN_SCREEN | 3 | YES | 导航到离我最近的银行 | 导航是否打开并定位到一家合理的最近银行？ |
| 16 | virtual-system-tool-16 | VIRTUAL_DISPLAY | 3 | YES | 导航到离我最近的银行 | 接管后导航是否定位到一家合理的最近银行？ |
| 17 | main-system-tool-17 | MAIN_SCREEN | 3 | YES | 新建联系人 Umbra测试联系人，手机号10000000000 | 联系人创建界面是否包含 Umbra测试联系人 和 10000000000，并且未保存？ |
| 18 | virtual-system-tool-18 | VIRTUAL_DISPLAY | 3 | YES | 新建联系人 Umbra测试联系人，手机号10000000000 | 接管后联系人信息是否正确且未保存？ |
| 19 | main-system-tool-19 | MAIN_SCREEN | 3 | YES | 给手机号10000000000，编辑短信“[UmbraBench] 固定消息测试，请忽略” | 短信编辑界面是否包含号码 10000000000 和固定消息，并且未发送？ |
| 20 | virtual-system-tool-20 | VIRTUAL_DISPLAY | 3 | YES | 给手机号10000000000，编辑短信“[UmbraBench] 固定消息测试，请忽略” | 接管后短信编辑界面是否正确且未发送？ |
| 21 | main-social-navigation-21 | MAIN_SCREEN | 3 | YES | 打开QQ，进入联系人“我的电脑”的聊天页面 | QQ 是否进入“我的电脑”聊天页面？ |
| 22 | virtual-social-navigation-22 | VIRTUAL_DISPLAY | 3 | YES | 打开QQ，进入联系人“我的电脑”的聊天页面 | 接管后 QQ 是否进入“我的电脑”聊天页面？ |
| 23 | main-social-message-23 | MAIN_SCREEN | 3 | YES | 给QQ联系人“我的电脑”发送“[UmbraBench] 固定消息测试，请忽略” | QQ 是否已成功向“我的电脑”发送固定消息，并且只发送一次？ |
| 24 | virtual-social-message-24 | VIRTUAL_DISPLAY | 3 | YES | 给QQ联系人“我的电脑”发送“[UmbraBench] 固定消息测试，请忽略”（主屏未激活输入法） | 虚拟屏任务是否成功发送固定消息，并且主屏输入法始终未激活？ |
| 25 | virtual-social-message-25 | VIRTUAL_DISPLAY | 3 | YES | 给QQ联系人“我的电脑”发送“[UmbraBench] 固定消息测试，请忽略”（主屏激活输入法） | 虚拟屏任务是否成功发送固定消息，且没有打断主屏已激活的输入法？ |
| 26 | main-social-message-26 | MAIN_SCREEN | 3 | YES | 给QQ联系人“我的电脑”写一句不超过20个字的生日祝福并发送 | QQ 是否已发送生日祝福，且文字不超过 20 个字？ |
| 27 | virtual-social-message-27 | VIRTUAL_DISPLAY | 3 | YES | 给QQ联系人“我的电脑”写一句不超过20个字的生日祝福并发送 | 虚拟屏是否已发送生日祝福，且文字不超过 20 个字？ |
| 28 | main-file-28 | MAIN_SCREEN | 3 | YES | 在手机存储中找到 Download 里的 umbra-benchmark.txt | 是否在 Download 中定位到了 umbra-benchmark.txt？ |
| 29 | virtual-file-29 | VIRTUAL_DISPLAY | 3 | YES | 在手机存储中找到 Download 里的 umbra-benchmark.txt | 接管后是否在虚拟屏文件管理器中定位到了 umbra-benchmark.txt？ |
| 30 | main-music-30 | MAIN_SCREEN | 5 | YES | 在网易云音乐中打开每日推荐的第一首歌，打开正确歌曲的详情页 | 是否打开的是每日推荐第一首歌的正确详情页？ |
| 31 | virtual-music-31 | VIRTUAL_DISPLAY | 5 | YES | 在网易云音乐中打开每日推荐的第一首歌，打开正确歌曲的详情页 | 接管后是否打开的是每日推荐第一首歌的正确详情页？ |
| 32 | main-music-32 | MAIN_SCREEN | 5 | YES | 在网易云音乐中打开每日推荐的第一首歌，打开正确歌曲的详情页，并播放 | 是否正确播放了每日推荐第一首歌？ |
| 33 | virtual-music-33 | VIRTUAL_DISPLAY | 5 | YES | 在网易云音乐中打开每日推荐的第一首歌，打开正确歌曲的详情页，并播放 | 接管后是否正确播放了每日推荐第一首歌？ |
| 34 | main-music-34 | MAIN_SCREEN | 5 | YES | 在网易云音乐中打开每日推荐的第一首歌，打开正确歌曲的详情页，在评论框输入“[UmbraBench] 评论草稿” | 评论框是否输入了“[UmbraBench] 评论草稿”，并且没有发布评论？ |
| 35 | virtual-music-35 | VIRTUAL_DISPLAY | 5 | YES | 在网易云音乐中打开每日推荐的第一首歌，打开正确歌曲的详情页，在评论框输入“[UmbraBench] 评论草稿” | 接管后评论框是否正确且未发布？ |
| 36 | main-video-search-36 | MAIN_SCREEN | 3 | YES | 在哔哩哔哩搜索“Android 无障碍开发”，停在搜索结果页面 | 哔哩哔哩是否停在“Android 无障碍开发”搜索结果页？ |
| 37 | virtual-video-search-37 | VIRTUAL_DISPLAY | 3 | YES | 在哔哩哔哩搜索“Android 无障碍开发”，停在搜索结果页面 | 接管后是否停在“Android 无障碍开发”搜索结果页？ |
| 38 | main-video-search-38 | MAIN_SCREEN | 5 | YES | 在哔哩哔哩搜索“Android 无障碍开发”，停在搜索结果页面，打开播放量最高的视频 | 是否打开了当前播放量最高的视频？ |
| 39 | virtual-video-search-39 | VIRTUAL_DISPLAY | 5 | YES | 在哔哩哔哩搜索“Android 无障碍开发”，停在搜索结果页面，打开播放量最高的视频 | 接管后是否打开了当前播放量最高的视频？ |
| 40 | main-game-40 | MAIN_SCREEN | 3 | YES | 打开明日方舟 | 明日方舟是否进入载入界面？ |
| 41 | virtual-game-41 | VIRTUAL_DISPLAY | 3 | YES | 打开明日方舟 | 接管后明日方舟是否进入载入界面？ |
| 42 | main-browser-42 | MAIN_SCREEN | 3 | YES | 打开浏览器，并搜索”umbra“ | 浏览器是否显示“umbra”搜索结果页？ |
| 43 | virtual-browser-43 | VIRTUAL_DISPLAY | 3 | YES | 打开浏览器，并搜索”umbra“ | 接管后浏览器是否显示“umbra”搜索结果页？ |
| 44 | main-browser-44 | MAIN_SCREEN | 5 | YES | 打开浏览器，并搜索”umbra“并进入第一个推荐项 | 浏览器是否进入了第一个推荐项？ |
| 45 | virtual-browser-45 | VIRTUAL_DISPLAY | 5 | YES | 打开浏览器，并搜索”umbra“并进入第一个推荐项 | 接管后浏览器是否进入了第一个推荐项？ |
| 46 | main-notes-46 | MAIN_SCREEN | 3 | YES | 打开便签，创建新便签，并输入”[UmbraBench] 固定文字测试“ | 便签是否创建成功且内容为“[UmbraBench] 固定文字测试”？ |
| 47 | virtual-notes-47 | VIRTUAL_DISPLAY | 3 | YES | 打开便签，创建新便签，并输入”[UmbraBench] 固定文字测试“（主屏未激活输入法） | 虚拟屏便签是否创建成功且内容正确，并保持主屏输入法未激活？ |
| 48 | virtual-notes-48 | VIRTUAL_DISPLAY | 3 | YES | 打开便签，创建新便签，并输入”[UmbraBench] 固定文字测试“（主屏激活输入法） | 虚拟屏便签是否创建成功且内容正确，且没有打断主屏已激活的输入法？ |
| 49 | main-commerce-49 | MAIN_SCREEN | 1 | YES | 打开美团，点一杯霸王茶姬的大杯伯牙绝弦，外卖到默认地址 | 美团是否选中霸王茶姬大杯伯牙绝弦并停在付款界面？ |
| 50 | virtual-commerce-50 | VIRTUAL_DISPLAY | 1 | YES | 打开美团，点一杯霸王茶姬的大杯伯牙绝弦，外卖到默认地址 | 接管后是否选中霸王茶姬大杯伯牙绝弦并停在付款界面，未发生支付？ |
