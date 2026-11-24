package com.wechatglass;

import android.database.Cursor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Read-only index of the home page's three categories.
 *
 * <p>Plan B: the list itself will be ours, so it can show friends and groups
 * that have no conversation row yet (WeChat does not create one until a message
 * exists). Nothing here writes to storage — it only reads the contact table,
 * the conversation table and WeChat's own friend/chatroom SQL predicates.
 */
final class ConversationIndex {

    static final class Entry {
        String username = "";
        String display = "";
        /** WeChat's own pinyin initials (rcontact.pyInitial), used for A–Z. */
        String pyInitial = "";
        /** Pinyin of the remark (rcontact.conRemarkPYFull) when one exists. */
        String remarkPy = "";

        /** Sort key: the remark's pinyin when there is a remark, else the nickname's. */
        String sortKey() {
            return remarkPy == null || remarkPy.isEmpty() ? safe(pyInitial) : remarkPy;
        }
        boolean group;
        boolean hasConversation;
        /** WeChat pins a conversation with this bit in rconversation.flag. */
        boolean pinned;
        int unread;
        String preview = "";
        long time;
    }

    private static final List<Entry> FRIENDS = new ArrayList<>();
    private static final List<Entry> GROUPS = new ArrayList<>();
    private static final List<Entry> SYSTEM = new ArrayList<>();
    private static volatile boolean loaded;
    private static volatile int version;
    /** Conversation storage handle, cached so refresh() re-queries without re-resolving. */
    private static Object conversationDb;
    /** Read-only contact DB handle, kept only for the P0 column probe. */
    /** Logged-in account, excluded from the contacts list. */
    private static volatile String selfUsername = "";

    private ConversationIndex() {
    }

    static boolean loaded() {
        return loaded;
    }

    static int version() {
        return version;
    }

    /**
     * Re-reads the conversation table and updates unread/read, preview, time and
     * pinned state of the in-memory rows in place, then bumps {@link #version} so
     * the list re-renders. Much cheaper than {@link #load}: it touches only
     * {@code rconversation}, never the full contact table.
     */
    static synchronized void refresh() {
        if (!loaded || conversationDb == null) {
            return;
        }
        try {
            Map<String, Entry> conversations = queryConversations(conversationDb);
            applyConversations(FRIENDS, conversations);
            applyConversations(GROUPS, conversations);
            applyConversations(SYSTEM, conversations);
            version++;
        } catch (Throwable throwable) {
            android.util.Log.e("WeChatGlass", "HL refresh failed", throwable);
        }
    }

    /** Copies the live conversation state onto each existing row. */
    private static void applyConversations(List<Entry> list, Map<String, Entry> conversations) {
        for (Entry e : list) {
            Entry conv = conversations.get(e.username);
            if (conv != null) {
                e.hasConversation = true;
                e.unread = conv.unread;
                e.preview = conv.preview;
                e.time = conv.time;
                e.pinned = conv.pinned;
            }
        }
    }

    static synchronized void load(ClassLoader cl) {
        if (loaded) {
            return;
        }
        try {
            Class<?> serviceClass = de.robv.android.xposed.XposedHelpers
                    .findClass("l51.i9", cl);
            Object container = de.robv.android.xposed.XposedHelpers
                    .callStaticMethod(serviceClass, "b");
            Object contactStorage = de.robv.android.xposed.XposedHelpers
                    .callMethod(container, "r");
            Object conversationStorage = de.robv.android.xposed.XposedHelpers
                    .callMethod(container, "s");
            Object contactDb = de.robv.android.xposed.XposedHelpers
                    .getObjectField(contactStorage, "d");
            conversationDb = de.robv.android.xposed.XposedHelpers
                    .getObjectField(conversationStorage, "d");

            Map<String, Entry> conversations = queryConversations(conversationDb);

            // WeChat's own friend predicate (ContactStorage.t):
            // type & 1 != 0, type & 32 == 0, type & 8 == 0,
            // verifyFlag & 8 == 0, deleteFlag == 0, and not a chatroom.
            List<Entry> friends = queryContacts(contactDb,
                    "select username, nickname, conRemark, pyInitial, conRemarkPYFull"
                            + " from rcontact"
                            + " where (type & 1 != 0) and type & 32 = 0"
                            + " and type & 8 = 0 and verifyFlag & 8 = 0"
                            + " and deleteFlag = 0"
                            + " and username not like '%@chatroom'"
                            + " and username not like '%@im.chatroom'"
                            + " and username not like 'gh_%'"
                            + " and username not like '%@%'"
                            + " order by pyInitial asc");
            // Chatrooms live in rcontact as '%@chatroom'; the separate chatroom
            // table only keeps the rooms WeChat still tracks. This catches the
            // ones we already left as well.
            List<Entry> groups = queryContacts(contactDb,
                    "select username, nickname, conRemark from rcontact"
                            + " where type & 8 = 0"
                            + " and (username like '%@chatroom'"
                            + " or username like '%@im.chatroom')");

            FRIENDS.clear();
            GROUPS.clear();
            SYSTEM.clear();
            selfUsername = querySelfUsername(contactDb);
            for (Entry e : friends) {
                // rcontact contains the logged-in account itself; the contacts
                // page must not list "me" as a friend.
                if (e.username.equals(selfUsername)) {
                    continue;
                }
                Entry conv = conversations.get(e.username);
                if (conv != null) {
                    e.hasConversation = true;
                    e.unread = conv.unread;
                    e.preview = conv.preview;
                    e.time = conv.time;
                    e.pinned = conv.pinned;
                    e.display = e.display.isEmpty() ? conv.display : e.display;
                }
                if (HomeConversationFilter.isSystem(e.username)) {
                    SYSTEM.add(e);
                } else {
                    FRIENDS.add(e);
                }
            }
            for (Entry e : groups) {
                Entry conv = conversations.get(e.username);
                if (conv != null) {
                    e.hasConversation = true;
                    e.unread = conv.unread;
                    e.preview = conv.preview;
                    e.time = conv.time;
                    e.pinned = conv.pinned;
                }
                GROUPS.add(e);
            }
            // 微信自带：直接取现有会话里的系统号（文件传输助手、公众号、
            // 服务号、通知、微信团队……），名字优先用 rcontact 里的昵称。
            Map<String, String> names = queryNameMap(contactDb);
            for (Entry conv : conversations.values()) {
                if (!HomeConversationFilter.isSystem(conv.username)) {
                    continue;
                }
                Entry e = new Entry();
                e.username = conv.username;
                String name = names.get(conv.username);
                e.display = name != null && !name.isEmpty()
                        ? name : systemName(conv.username);
                e.hasConversation = true;
                e.unread = conv.unread;
                e.preview = conv.preview;
                e.time = conv.time;
                e.pinned = conv.pinned;
                SYSTEM.add(e);
            }
            // A system account can arrive twice: once from rcontact (it is a
            // friend too) and once from its conversation row. Keep one row.
            dedupe(FRIENDS);
            dedupe(GROUPS);
            dedupe(SYSTEM);
            version++;
            loaded = true;
            android.util.Log.i("WeChatGlass", "HL index friends=" + FRIENDS.size()
                    + " (with conv=" + countWithConversation(FRIENDS) + ")"
                    + " groups=" + GROUPS.size()
                    + " (with conv=" + countWithConversation(GROUPS) + ")"
                    + " system=" + SYSTEM.size()
                    + " conversations=" + conversations.size());
            android.util.Log.i("WeChatGlass", "HL pinned friends=" + countPinned(FRIENDS)
                    + " groups=" + countPinned(GROUPS)
                    + " system=" + countPinned(SYSTEM));
            int pinnedLogged = 0;
            for (Entry e : FRIENDS) {
                if (e.pinned && pinnedLogged++ < 8) {
                    android.util.Log.i("WeChatGlass", "HL pinnedFriend " + e.username
                            + " name=" + e.display + " t=" + e.time);
                }
            }
            logCount(contactDb, "rcontact @chatroom",
                    "select count(*) from rcontact where username like '%@chatroom'");
            logCount(contactDb, "rcontact @im.chatroom",
                    "select count(*) from rcontact where username like '%@im.chatroom'");
            logCount(contactDb, "chatroom table", "select count(*) from chatroom");
            logCount(contactDb, "rcontact type&2",
                    "select count(*) from rcontact where type & 2 != 0");
            for (int i = 0; i < Math.min(5, FRIENDS.size()); i++) {
                Entry e = FRIENDS.get(i);
                android.util.Log.i("WeChatGlass", "HL friend " + e.username
                        + " name=" + e.display + " conv=" + e.hasConversation);
            }
            for (int i = 0; i < Math.min(5, GROUPS.size()); i++) {
                Entry e = GROUPS.get(i);
                android.util.Log.i("WeChatGlass", "HL group " + e.username
                        + " name=" + e.display + " conv=" + e.hasConversation);
            }
        } catch (Throwable throwable) {
            android.util.Log.e("WeChatGlass", "HL index failed", throwable);
        }
    }

    private static int countPinned(List<Entry> list) {
        int n = 0;
        for (Entry e : list) {
            if (e.pinned) {
                n++;
            }
        }
        return n;
    }

    private static int countWithConversation(List<Entry> list) {
        int n = 0;
        for (Entry e : list) {
            if (e.hasConversation) {
                n++;
            }
        }
        return n;
    }

    private static void logCount(Object db, String label, String sql) {
        Cursor cursor = null;
        try {
            cursor = (Cursor) de.robv.android.xposed.XposedHelpers
                    .callMethod(db, "A", sql, null);
            if (cursor != null && cursor.moveToFirst()) {
                android.util.Log.i("WeChatGlass", "HL count " + label + "=" + cursor.getInt(0));
            }
        } catch (Throwable throwable) {
            android.util.Log.e("WeChatGlass", "HL count failed: " + label, throwable);
        } finally {
            if (cursor != null) {
                try {
                    cursor.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static List<Entry> queryContacts(Object db, String sql) {
        List<Entry> out = new ArrayList<>();
        Cursor cursor = null;
        try {
            cursor = (Cursor) de.robv.android.xposed.XposedHelpers
                    .callMethod(db, "A", sql, null);
            if (cursor != null) {
                int userCol = cursor.getColumnIndex("username");
                int nickCol = cursor.getColumnIndex("nickname");
                int remarkCol = cursor.getColumnIndex("conRemark");
                int pyCol = cursor.getColumnIndex("pyInitial");
                while (cursor.moveToNext()) {
                    String username = cursor.getString(userCol);
                    if (username == null || username.isEmpty()) {
                        continue;
                    }
                    Entry e = new Entry();
                    e.username = username;
                    String remark = remarkCol >= 0 ? cursor.getString(remarkCol) : null;
                    String nick = nickCol >= 0 ? cursor.getString(nickCol) : null;
                    e.display = remark != null && !remark.isEmpty() ? remark : nick;
                    if (e.display == null || e.display.isEmpty()) {
                        e.display = username;
                    }
                    e.pyInitial = pyCol >= 0 ? cursor.getString(pyCol) : "";
                    int remarkPyCol = cursor.getColumnIndex("conRemarkPYFull");
                    if (remarkPyCol >= 0 && remark != null && !remark.isEmpty()) {
                        e.remarkPy = cursor.getString(remarkPyCol);
                    }
                    out.add(e);
                }
            }
        } catch (Throwable throwable) {
            android.util.Log.e("WeChatGlass", "HL query failed: " + sql, throwable);
        } finally {
            if (cursor != null) {
                try {
                    cursor.close();
                } catch (Throwable ignored) {
                }
            }
        }
        return out;
    }

    private static Map<String, Entry> queryConversations(Object db) {
        Map<String, Entry> out = new HashMap<>();
        Cursor cursor = null;
        try {
            cursor = (Cursor) de.robv.android.xposed.XposedHelpers.callMethod(db, "A",
                    "select username, digest, content, msgType, conversationTime,"
                            + " unReadCount, flag from rconversation"
                            + " order by flag desc, conversationTime desc", null);
            if (cursor != null) {
                int userCol = cursor.getColumnIndex("username");
                int digestCol = cursor.getColumnIndex("digest");
                int contentCol = cursor.getColumnIndex("content");
                int typeCol = cursor.getColumnIndex("msgType");
                int timeCol = cursor.getColumnIndex("conversationTime");
                int unreadCol = cursor.getColumnIndex("unReadCount");
                int flagCol = cursor.getColumnIndex("flag");
                int logged = 0;
                while (cursor.moveToNext()) {
                    String username = cursor.getString(userCol);
                    if (username == null) {
                        continue;
                    }
                    Entry e = new Entry();
                    e.username = username;
                    e.hasConversation = true;
                    String digest = digestCol >= 0 ? cursor.getString(digestCol) : "";
                    String content = contentCol >= 0 ? cursor.getString(contentCol) : "";
                    int msgType = typeCol >= 0 ? cursor.getInt(typeCol) : 0;
                    e.preview = formatPreview(digest, content, msgType);
                    e.time = timeCol >= 0 ? cursor.getLong(timeCol) : 0L;
                    e.unread = unreadCol >= 0 ? cursor.getInt(unreadCol) : 0;
                    if (flagCol >= 0) {
                        long flag = cursor.getLong(flagCol);
                        e.pinned = (flag & 0x4000000000000000L) != 0L;
                    }
                    out.put(username, e);
                    if (flagCol >= 0 && logged++ < 15) {
                        long flag = cursor.getLong(flagCol);
                        android.util.Log.i("WeChatGlass", "HL flag " + username
                                + " 0x" + Long.toHexString(flag));
                    }
                }
            }
        } catch (Throwable throwable) {
            android.util.Log.e("WeChatGlass", "HL conv query failed", throwable);
        } finally {
            if (cursor != null) {
                try {
                    cursor.close();
                } catch (Throwable ignored) {
                }
            }
        }
        return out;
    }

    private static Map<String, String> queryNameMap(Object db) {
        Map<String, String> out = new HashMap<>();
        Cursor cursor = null;
        try {
            cursor = (Cursor) de.robv.android.xposed.XposedHelpers.callMethod(db, "A",
                    "select username, nickname, conRemark from rcontact", null);
            if (cursor != null) {
                int userCol = cursor.getColumnIndex("username");
                int nickCol = cursor.getColumnIndex("nickname");
                int remarkCol = cursor.getColumnIndex("conRemark");
                while (cursor.moveToNext()) {
                    String username = cursor.getString(userCol);
                    if (username == null) {
                        continue;
                    }
                    String remark = remarkCol >= 0 ? cursor.getString(remarkCol) : null;
                    String nick = nickCol >= 0 ? cursor.getString(nickCol) : null;
                    String name = remark != null && !remark.isEmpty() ? remark : nick;
                    if (name != null && !name.isEmpty()) {
                        out.put(username, name);
                    }
                }
            }
        } catch (Throwable ignored) {
        } finally {
            if (cursor != null) {
                try {
                    cursor.close();
                } catch (Throwable ignored) {
                }
            }
        }
        return out;
    }

    /** Same idea as WeChat's own row: format the preview by message type. */
    private static String formatPreview(String digest, String content, int msgType) {
        String language = java.util.Locale.getDefault().getLanguage();
        boolean ja = "ja".equals(language);
        boolean zh = "zh".equals(language);
        switch (msgType) {
            case 3:
                return ja ? "[写真]" : zh ? "[图片]" : "[Photo]";
            case 34:
                return ja ? "[音声]" : zh ? "[语音]" : "[Voice]";
            case 43:
                return ja ? "[動画]" : zh ? "[视频]" : "[Video]";
            case 47:
                return ja ? "[ステッカー]" : zh ? "[表情]" : "[Sticker]";
            case 48:
                return ja ? "[位置情報]" : zh ? "[位置]" : "[Location]";
            case 42:
                return ja ? "[連絡先]" : zh ? "[名片]" : "[Contact]";
            case 49: {
                String title = xmlTitle(content);
                if (title != null && !title.isEmpty()) {
                    return title;
                }
                String digestTitle = xmlTitle(digest);
                if (digestTitle != null && !digestTitle.isEmpty()) {
                    return digestTitle;
                }
                return ja ? "[リンク]" : zh ? "[链接]" : "[Link]";
            }
            case 10000: {
                String text = plain(digest);
                if (text == null || text.isEmpty()) {
                    text = plain(content);
                }
                return text == null ? "" : text;
            }
            default: {
                String text = plain(digest);
                if (text == null || text.isEmpty()) {
                    text = plain(content);
                }
                return text == null ? "" : text;
            }
        }
    }

    private static String xmlTitle(String xml) {
        if (xml == null) {
            return null;
        }
        int start = xml.indexOf("<title>");
        int end = xml.indexOf("</title>");
        if (start >= 0 && end > start) {
            return xml.substring(start + 7, end);
        }
        return null;
    }

    private static String plain(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.startsWith("<")) {
            return xmlTitle(trimmed);
        }
        if (trimmed.matches("[0-9a-fA-F]{24,}")) {
            return null; // media/msg hash, not human-readable text
        }
        // Group digests can be "%s: message" — the sender name is not available
        // at this layer, so drop the placeholder instead of showing it.
        String text = trimmed.replace("%s:", "").replace("%s：", "")
                .replace("%s", "").trim();
        while (text.startsWith(":") || text.startsWith("：")) {
            text = text.substring(1).trim();
        }
        return text;
    }

    private static String systemName(String username) {
        String language = java.util.Locale.getDefault().getLanguage();
        boolean ja = "ja".equals(language);
        boolean zh = "zh".equals(language);
        switch (username) {
            case "filehelper":
                return ja ? "ファイル転送" : zh ? "文件传输助手" : "File Transfer";
            case "weixin":
                return ja ? "Weixinチーム" : zh ? "微信团队" : "WeChat Team";
            case "weixinpay":
            case "wxpay":
                return ja ? "Weixin Pay" : zh ? "微信支付" : "WeChat Pay";
            case "officialaccounts":
                return ja ? "公式アカウント" : zh ? "公众号" : "Official Accounts";
            case "service_officialaccounts":
                return ja ? "サービスアカウント" : zh ? "服务号" : "Service Accounts";
            case "notifymessage":
                return ja ? "サービス通知" : zh ? "服务通知" : "Service Notifications";
            case "brandsessionholder":
                return ja ? "購読" : zh ? "订阅号" : "Subscriptions";
            case "brandservicesessionholder":
                return ja ? "サービスアカウント" : zh ? "服务号" : "Services";
            case "mphelper":
                return ja ? "公式プラットフォームアシスタント" : zh ? "公众平台助手" : "MP Helper";
            case "fmessage":
                return ja ? "友だちのおすすめ" : zh ? "朋友推荐" : "Friend Recommendations";
            case "medianote":
                return ja ? "ボイスメモ" : zh ? "语音记事本" : "Voice Notes";
            case "floatbottle":
                return ja ? "ボトルメール" : zh ? "漂流瓶" : "Bottle";
            case "qqmail":
                return ja ? "QQメール" : zh ? "QQ邮箱提醒" : "QQ Mail";
            case "newsapp":
                return "腾讯新闻";
            default:
                if (username.startsWith("gh_")) {
                    return ja ? "公式アカウント" : zh ? "公众号" : "Official Account";
                }
                return username;
        }
    }

    static List<Entry> friends() {
        return FRIENDS;
    }

    /** Own wxid, read from userinfo (id=2/type=3); empty until load(). */
    static String selfUsername() {
        return selfUsername;
    }

    /** Friends sorted A–Z by the visible name: remark pinyin first, else nickname. */
    static List<Entry> contacts() {
        List<Entry> copy = new ArrayList<>(FRIENDS);
        java.util.Collections.sort(copy, new java.util.Comparator<Entry>() {
            @Override
            public int compare(Entry a, Entry b) {
                int byPy = a.sortKey().compareToIgnoreCase(b.sortKey());
                if (byPy != 0) {
                    return byPy;
                }
                return safe(a.display).compareToIgnoreCase(safe(b.display));
            }
        });
        return copy;
    }

    /** Removes duplicate usernames, keeping the first (already-updated) entry. */
    private static void dedupe(List<Entry> list) {
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (int i = list.size() - 1; i >= 0; i--) {
            String username = list.get(i).username;
            if (username != null && !seen.add(username)) {
                list.remove(i);
            }
        }
    }
    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static String querySelfUsername(Object db) {
        Cursor cursor = null;
        try {
            cursor = (Cursor) de.robv.android.xposed.XposedHelpers.callMethod(
                    db, "A", "select value from userinfo where id=2 and type=3 limit 1", null);
            if (cursor != null && cursor.moveToFirst()) {
                String value = cursor.getString(0);
                return value == null ? "" : value;
            }
        } catch (Throwable throwable) {
            android.util.Log.e("WeChatGlass", "self lookup failed", throwable);
        } finally {
            if (cursor != null) {
                try {
                    cursor.close();
                } catch (Throwable ignored) {
                }
            }
        }
        return "";
    }

    /**
     * P0 probe: which columns rcontact actually has, and whether WeChat's own
     * pinyin columns are populated. Read-only SQL, no writes.
     */

    static List<Entry> groups() {
        return GROUPS;
    }

    static List<Entry> system() {
        return SYSTEM;
    }
}
