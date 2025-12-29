package com.example.test_android_dev.utils;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

public class AppRegistry {
    private static final Map<String, String> APP_PACKAGES = new HashMap<>();

    static {
        // Social & Messaging
        APP_PACKAGES.put("微信", "com.tencent.mm");
        APP_PACKAGES.put("QQ", "com.tencent.mobileqq");
        APP_PACKAGES.put("微博", "com.sina.weibo");

        // E-commerce
        APP_PACKAGES.put("淘宝", "com.taobao.taobao");
        APP_PACKAGES.put("京东", "com.jingdong.app.mall");
        APP_PACKAGES.put("拼多多", "com.xunmeng.pinduoduo");
        APP_PACKAGES.put("淘宝闪购", "com.taobao.taobao");
        APP_PACKAGES.put("京东秒送", "com.jingdong.app.mall");

        // Lifestyle & Social
        APP_PACKAGES.put("小红书", "com.xingin.xhs");
        APP_PACKAGES.put("豆瓣", "com.douban.frodo");
        APP_PACKAGES.put("知乎", "com.zhihu.android");

        // Maps & Navigation
        APP_PACKAGES.put("高德地图", "com.autonavi.minimap");
        APP_PACKAGES.put("百度地图", "com.baidu.BaiduMap");

        // Food & Services
        APP_PACKAGES.put("美团", "com.sankuai.meituan");
        APP_PACKAGES.put("大众点评", "com.dianping.v1");
        APP_PACKAGES.put("饿了么", "me.ele");
        APP_PACKAGES.put("肯德基", "com.yek.android.kfc.activitys");

        // Travel
        APP_PACKAGES.put("携程", "ctrip.android.view");
        APP_PACKAGES.put("铁路12306", "com.MobileTicket");
        APP_PACKAGES.put("12306", "com.MobileTicket");
        APP_PACKAGES.put("去哪儿", "com.Qunar");
        APP_PACKAGES.put("去哪儿旅行", "com.Qunar");
        APP_PACKAGES.put("滴滴出行", "com.sdu.didi.psnger");

        // Video & Entertainment
        APP_PACKAGES.put("bilibili", "tv.danmaku.bili");
        APP_PACKAGES.put("抖音", "com.ss.android.ugc.aweme");
        APP_PACKAGES.put("快手", "com.smile.gifmaker");
        APP_PACKAGES.put("腾讯视频", "com.tencent.qqlive");
        APP_PACKAGES.put("爱奇艺", "com.qiyi.video");
        APP_PACKAGES.put("优酷视频", "com.youku.phone");
        APP_PACKAGES.put("芒果TV", "com.hunantv.imgo.activity");
        APP_PACKAGES.put("红果短剧", "com.phoenix.read");

        // Music & Audio
        APP_PACKAGES.put("网易云音乐", "com.netease.cloudmusic");
        APP_PACKAGES.put("QQ音乐", "com.tencent.qqmusic");
        APP_PACKAGES.put("汽水音乐", "com.luna.music");
        APP_PACKAGES.put("喜马拉雅", "com.ximalaya.ting.android");

        // Reading
        APP_PACKAGES.put("番茄小说", "com.dragon.read");
        APP_PACKAGES.put("番茄免费小说", "com.dragon.read");
        APP_PACKAGES.put("七猫免费小说", "com.kmxs.reader");

        // Productivity
        APP_PACKAGES.put("飞书", "com.ss.android.lark");
        APP_PACKAGES.put("QQ邮箱", "com.tencent.androidqqmail");

        // AI & Tools
        APP_PACKAGES.put("豆包", "com.larus.nova");

        // Health & Fitness
        APP_PACKAGES.put("keep", "com.gotokeep.keep");
        APP_PACKAGES.put("美柚", "com.lingan.seeyou");

        // News & Information
        APP_PACKAGES.put("腾讯新闻", "com.tencent.news");
        APP_PACKAGES.put("今日头条", "com.ss.android.article.news");

        // Real Estate
        APP_PACKAGES.put("贝壳找房", "com.lianjia.beike");
        APP_PACKAGES.put("安居客", "com.anjuke.android.app");

        // Finance
        APP_PACKAGES.put("同花顺", "com.hexin.plat.android");

        // Games
        APP_PACKAGES.put("星穹铁道", "com.miHoYo.hkrpg");
        APP_PACKAGES.put("崩坏：星穹铁道", "com.miHoYo.hkrpg");
        APP_PACKAGES.put("恋与深空", "com.papegames.lysk.cn");

        // System & English Names
        APP_PACKAGES.put("AndroidSystemSettings", "com.android.settings");
        APP_PACKAGES.put("Android System Settings", "com.android.settings");
        APP_PACKAGES.put("Android  System Settings", "com.android.settings");
        APP_PACKAGES.put("Android-System-Settings", "com.android.settings");
        APP_PACKAGES.put("Settings", "com.android.settings");
        APP_PACKAGES.put("AudioRecorder", "com.android.soundrecorder");
        APP_PACKAGES.put("audiorecorder", "com.android.soundrecorder");
        APP_PACKAGES.put("Bluecoins", "com.rammigsoftware.bluecoins");
        APP_PACKAGES.put("bluecoins", "com.rammigsoftware.bluecoins");
        APP_PACKAGES.put("Broccoli", "com.flauschcode.broccoli");
        APP_PACKAGES.put("broccoli", "com.flauschcode.broccoli");
        APP_PACKAGES.put("Booking.com", "com.booking");
        APP_PACKAGES.put("Booking", "com.booking");
        APP_PACKAGES.put("booking.com", "com.booking");
        APP_PACKAGES.put("booking", "com.booking");
        APP_PACKAGES.put("BOOKING.COM", "com.booking");
        APP_PACKAGES.put("Chrome", "com.android.chrome");
        APP_PACKAGES.put("chrome", "com.android.chrome");
        APP_PACKAGES.put("Google Chrome", "com.android.chrome");
        APP_PACKAGES.put("Clock", "com.android.deskclock");
        APP_PACKAGES.put("clock", "com.android.deskclock");
        APP_PACKAGES.put("Contacts", "com.android.contacts");
        APP_PACKAGES.put("contacts", "com.android.contacts");
        APP_PACKAGES.put("Duolingo", "com.duolingo");
        APP_PACKAGES.put("duolingo", "com.duolingo");
        APP_PACKAGES.put("Expedia", "com.expedia.bookings");
        APP_PACKAGES.put("expedia", "com.expedia.bookings");
        APP_PACKAGES.put("Files", "com.android.fileexplorer");
        APP_PACKAGES.put("files", "com.android.fileexplorer");
        APP_PACKAGES.put("File Manager", "com.android.fileexplorer");
        APP_PACKAGES.put("file manager", "com.android.fileexplorer");
        APP_PACKAGES.put("gmail", "com.google.android.gm");
        APP_PACKAGES.put("Gmail", "com.google.android.gm");
        APP_PACKAGES.put("GoogleMail", "com.google.android.gm");
        APP_PACKAGES.put("Google Mail", "com.google.android.gm");
        APP_PACKAGES.put("GoogleFiles", "com.google.android.apps.nbu.files");
        APP_PACKAGES.put("googlefiles", "com.google.android.apps.nbu.files");
        APP_PACKAGES.put("FilesbyGoogle", "com.google.android.apps.nbu.files");
        APP_PACKAGES.put("GoogleCalendar", "com.google.android.calendar");
        APP_PACKAGES.put("Google-Calendar", "com.google.android.calendar");
        APP_PACKAGES.put("Google Calendar", "com.google.android.calendar");
        APP_PACKAGES.put("google-calendar", "com.google.android.calendar");
        APP_PACKAGES.put("google calendar", "com.google.android.calendar");
        APP_PACKAGES.put("GoogleChat", "com.google.android.apps.dynamite");
        APP_PACKAGES.put("Google Chat", "com.google.android.apps.dynamite");
        APP_PACKAGES.put("Google-Chat", "com.google.android.apps.dynamite");
        APP_PACKAGES.put("GoogleClock", "com.google.android.deskclock");
        APP_PACKAGES.put("Google Clock", "com.google.android.deskclock");
        APP_PACKAGES.put("Google-Clock", "com.google.android.deskclock");
        APP_PACKAGES.put("GoogleContacts", "com.google.android.contacts");
        APP_PACKAGES.put("Google-Contacts", "com.google.android.contacts");
        APP_PACKAGES.put("Google Contacts", "com.google.android.contacts");
        APP_PACKAGES.put("google-contacts", "com.google.android.contacts");
        APP_PACKAGES.put("google contacts", "com.google.android.contacts");
        APP_PACKAGES.put("GoogleDocs", "com.google.android.apps.docs.editors.docs");
        APP_PACKAGES.put("Google Docs", "com.google.android.apps.docs.editors.docs");
        APP_PACKAGES.put("googledocs", "com.google.android.apps.docs.editors.docs");
        APP_PACKAGES.put("google docs", "com.google.android.apps.docs.editors.docs");
        APP_PACKAGES.put("Google Drive", "com.google.android.apps.docs");
        APP_PACKAGES.put("Google-Drive", "com.google.android.apps.docs");
        APP_PACKAGES.put("google drive", "com.google.android.apps.docs");
        APP_PACKAGES.put("google-drive", "com.google.android.apps.docs");
        APP_PACKAGES.put("GoogleDrive", "com.google.android.apps.docs");
        APP_PACKAGES.put("Googledrive", "com.google.android.apps.docs");
        APP_PACKAGES.put("googledrive", "com.google.android.apps.docs");
        APP_PACKAGES.put("GoogleFit", "com.google.android.apps.fitness");
        APP_PACKAGES.put("googlefit", "com.google.android.apps.fitness");
        APP_PACKAGES.put("GoogleKeep", "com.google.android.keep");
        APP_PACKAGES.put("googlekeep", "com.google.android.keep");
        APP_PACKAGES.put("GoogleMaps", "com.google.android.apps.maps");
        APP_PACKAGES.put("Google Maps", "com.google.android.apps.maps");
        APP_PACKAGES.put("googlemaps", "com.google.android.apps.maps");
        APP_PACKAGES.put("google maps", "com.google.android.apps.maps");
        APP_PACKAGES.put("Google Play Books", "com.google.android.apps.books");
        APP_PACKAGES.put("Google-Play-Books", "com.google.android.apps.books");
        APP_PACKAGES.put("google play books", "com.google.android.apps.books");
        APP_PACKAGES.put("google-play-books", "com.google.android.apps.books");
        APP_PACKAGES.put("GooglePlayBooks", "com.google.android.apps.books");
        APP_PACKAGES.put("googleplaybooks", "com.google.android.apps.books");
        APP_PACKAGES.put("GooglePlayStore", "com.android.vending");
        APP_PACKAGES.put("Google Play Store", "com.android.vending");
        APP_PACKAGES.put("Google-Play-Store", "com.android.vending");
        APP_PACKAGES.put("GoogleSlides", "com.google.android.apps.docs.editors.slides");
        APP_PACKAGES.put("Google Slides", "com.google.android.apps.docs.editors.slides");
        APP_PACKAGES.put("Google-Slides", "com.google.android.apps.docs.editors.slides");
        APP_PACKAGES.put("GoogleTasks", "com.google.android.apps.tasks");
        APP_PACKAGES.put("Google Tasks", "com.google.android.apps.tasks");
        APP_PACKAGES.put("Google-Tasks", "com.google.android.apps.tasks");
        APP_PACKAGES.put("Joplin", "net.cozic.joplin");
        APP_PACKAGES.put("joplin", "net.cozic.joplin");
        APP_PACKAGES.put("McDonald", "com.mcdonalds.app");
        APP_PACKAGES.put("mcdonald", "com.mcdonalds.app");
        APP_PACKAGES.put("Osmand", "net.osmand");
        APP_PACKAGES.put("osmand", "net.osmand");
        APP_PACKAGES.put("PiMusicPlayer", "com.Project100Pi.themusicplayer");
        APP_PACKAGES.put("pimusicplayer", "com.Project100Pi.themusicplayer");
        APP_PACKAGES.put("Quora", "com.quora.android");
        APP_PACKAGES.put("quora", "com.quora.android");
        APP_PACKAGES.put("Reddit", "com.reddit.frontpage");
        APP_PACKAGES.put("reddit", "com.reddit.frontpage");
        APP_PACKAGES.put("RetroMusic", "code.name.monkey.retromusic");
        APP_PACKAGES.put("retromusic", "code.name.monkey.retromusic");
        APP_PACKAGES.put("SimpleCalendarPro", "com.scientificcalculatorplus.simplecalculator.basiccalculator.mathcalc");
        APP_PACKAGES.put("SimpleSMSMessenger", "com.simplemobiletools.smsmessenger");
        APP_PACKAGES.put("Telegram", "org.telegram.messenger");
        APP_PACKAGES.put("temu", "com.einnovation.temu");
        APP_PACKAGES.put("Temu", "com.einnovation.temu");
        APP_PACKAGES.put("Tiktok", "com.zhiliaoapp.musically");
        APP_PACKAGES.put("tiktok", "com.zhiliaoapp.musically");
        APP_PACKAGES.put("Twitter", "com.twitter.android");
        APP_PACKAGES.put("twitter", "com.twitter.android");
        APP_PACKAGES.put("X", "com.twitter.android");
        APP_PACKAGES.put("VLC", "org.videolan.vlc");
        APP_PACKAGES.put("WeChat", "com.tencent.mm");
        APP_PACKAGES.put("wechat", "com.tencent.mm");
        APP_PACKAGES.put("Whatsapp", "com.whatsapp");
        APP_PACKAGES.put("WhatsApp", "com.whatsapp");
    }

    /**
     * 根据应用名称获取包名
     * @param appName 应用名称 (如 "微信", "Settings")
     * @return 包名 (如 "com.tencent.mm") 或 null
     */
    public static String getPackageName(String appName) {
        if (appName == null) return null;
        return APP_PACKAGES.get(appName);
    }

    /**
     * 获取所有支持的应用列表
     */
    public static Set<String> getSupportedApps() {
        return APP_PACKAGES.keySet();
    }
}
