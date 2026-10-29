package com.justinneed.analytics;
import java.net.URI;
import java.util.*;
public enum DomainCategory {
    BLOG, OFFICIAL, COMMUNITY, NEWS, OTHER;
    public static String host(String url) { return URI.create(url).getHost().toLowerCase(Locale.ROOT); }
    private static boolean domain(String host,String domain) { return host.equals(domain)||host.endsWith("."+domain); }
    public static DomainCategory classify(String url) {
        String h=host(url);
        if(List.of("tistory.com","velog.io","medium.com","naver.com").stream().anyMatch(d->domain(h,d))) return BLOG;
        if(h.startsWith("docs.")||h.startsWith("developer.")||domain(h,"github.com")||h.endsWith(".go.kr")||h.endsWith(".edu")) return OFFICIAL;
        if(List.of("reddit.com","dcinside.com","stackoverflow.com").stream().anyMatch(d->domain(h,d))) return COMMUNITY;
        if(List.of("bbc.com","bbc.co.uk","reuters.com","nytimes.com","cnn.com","yna.co.kr","hani.co.kr","chosun.com","khan.co.kr").stream().anyMatch(d->domain(h,d))) return NEWS;
        return OTHER;
    }
}
