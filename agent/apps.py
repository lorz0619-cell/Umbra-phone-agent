"""App display-name to Android package-name mappings used by Launch."""

from __future__ import annotations


APP_PACKAGES: dict[str, str] = {
    "微信": "com.tencent.mm",
    "QQ": "com.tencent.mobileqq",
    "微博": "com.sina.weibo",
    "淘宝": "com.taobao.taobao",
    "京东": "com.jingdong.app.mall",
    "拼多多": "com.xunmeng.pinduoduo",
    "小红书": "com.xingin.xhs",
    "知乎": "com.zhihu.android",
    "高德地图": "com.autonavi.minimap",
    "百度地图": "com.baidu.BaiduMap",
    "美团": "com.sankuai.meituan",
    "大众点评": "com.dianping.v1",
    "饿了么": "me.ele",
    "携程": "ctrip.android.view",
    "铁路12306": "com.MobileTicket",
    "12306": "com.MobileTicket",
    "滴滴出行": "com.sdu.didi.psnger",
    "bilibili": "tv.danmaku.bili",
    "抖音": "com.ss.android.ugc.aweme",
    "腾讯视频": "com.tencent.qqlive",
    "爱奇艺": "com.qiyi.video",
    "网易云音乐": "com.netease.cloudmusic",
    "QQ音乐": "com.tencent.qqmusic",
    "喜马拉雅": "com.ximalaya.ting.android",
    "飞书": "com.ss.android.lark",
    "设置": "com.android.settings",
    "Settings": "com.android.settings",
    "Chrome": "com.android.chrome",
    "chrome": "com.android.chrome",
    "浏览器": "com.android.chromium",
    "百度": "com.baidu.searchbox",
}


def get_package_name(app_name: str) -> str | None:
    """Return a package name, or pass through names that already look like packages."""
    if "." in app_name and "/" not in app_name:
        return app_name
    return APP_PACKAGES.get(app_name)
