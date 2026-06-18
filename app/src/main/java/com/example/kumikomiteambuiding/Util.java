public class Util {

    // Twitterの投稿URLを生成するやつ
    // 例：https://twitter.com/intent/tweet?text=hello%20world
    public static String generateTweet(String text) {
        String tweet = URLEncoder.encode(text, StandardCharsets.UTF_8);
        return "https://twitter.com/intent/tweet?text=" + tweet;
    }
}