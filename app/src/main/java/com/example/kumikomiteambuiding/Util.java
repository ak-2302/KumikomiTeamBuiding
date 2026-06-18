package com.example.kumikomiteambuiding;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;

public class Util {

    // Twitterの投稿URLを生成するやつ
    // 例：https://twitter.com/intent/tweet?text=hello%20world
    public static String generateTweet(String text) {
        String tweet = Uri.encode(text);
        return "https://twitter.com/intent/tweet?text=" + tweet;
    }

    // URLを開く
    // 使用例：openURL("https://example.com", this);
    public static void openURL(String url, Context context) {
        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
    }
}
