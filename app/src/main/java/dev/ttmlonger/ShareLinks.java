package dev.ttmlonger;

import java.net.URI;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class ShareLinks {
    private static final Pattern URL = Pattern.compile("https?://[^\\s<>\\\"]+", Pattern.CASE_INSENSITIVE);

    static String cleanText(String text) {
        Matcher urls = URL.matcher(text);
        StringBuffer out = new StringBuffer();
        while (urls.find()) {
            String token = urls.group();
            int end = token.length();
            while (end > 0 && ".,!?;:)]}".indexOf(token.charAt(end - 1)) >= 0) end--;
            String result = cleanUrl(token.substring(0, end)) + token.substring(end);
            urls.appendReplacement(out, Matcher.quoteReplacement(result));
        }
        urls.appendTail(out);
        return out.toString();
    }

    private static String cleanUrl(String link) {
        try {
            URI uri = URI.create(link);
            String host = uri.getHost();
            if (host == null || uri.getRawUserInfo() != null) return link;
            host = host.toLowerCase(Locale.ROOT);
            if (!host.equals("tiktok.com") && !host.endsWith(".tiktok.com")) return link;
            String query = uri.getRawQuery();
            if (query == null) return link;
            StringBuilder keep = new StringBuilder();
            boolean removed = false;
            for (String pair : query.split("&", -1)) {
                int equals = pair.indexOf('=');
                String key = (equals < 0 ? pair : pair.substring(0, equals)).toLowerCase(Locale.ROOT);
                if (key.startsWith("utm_") || key.equals("_t") || key.equals("_r")
                        || key.equals("share_app_id") || key.equals("share_link_id")
                        || key.equals("sender_device") || key.equals("sender_web_id")
                        || key.equals("share_iid")) {
                    removed = true;
                    continue;
                }
                if (keep.length() > 0) keep.append('&');
                keep.append(pair);
            }
            if (!removed) return link;
            return link.substring(0, link.indexOf('?')) + (keep.length() == 0 ? "" : "?" + keep)
                    + (uri.getRawFragment() == null ? "" : "#" + uri.getRawFragment());
        } catch (IllegalArgumentException e) {
            return link;
        }
    }
}
