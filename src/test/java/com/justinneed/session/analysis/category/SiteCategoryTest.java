package com.justinneed.session.analysis.category;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class SiteCategoryTest {

    @Test
    void sampleSessionUrlIsNews() {
        assertThat(SiteCategory.classify("https://n.news.naver.com/mnews/article/002/0002456290?sid=110"))
                .isEqualTo(SiteCategory.NEWS);
    }

    @Test
    void displayNamesAreKorean() {
        assertThat(Arrays.stream(SiteCategory.values()).map(SiteCategory::getDisplayName))
                .containsExactly("블로그", "공식문서", "커뮤니티", "뉴스", "기타");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "https://blog.naver.com/someone/223456789012",
            "https://m.blog.naver.com/someone/223456789012",
            "https://post.naver.com/viewer/postView.naver?volumeNo=1",
            "https://blog.daum.net/someone/123",
            "https://someone.tistory.com/42",
            "https://velog.io/@someone/spring-jpa",
            "https://brunch.co.kr/@someone/12",
            "https://techblog.woowahan.com/12345/",
            "https://tech.kakao.com/posts/600",
            "https://d2.naver.com/helloworld/123",
            "https://toss.tech/article/slash23",
            "https://medium.com/@someone/hello-world-abc123",
            "https://someone.medium.com/hello-world-abc123",
            "https://someone.substack.com/p/post",
            "https://someone.wordpress.com/2024/01/01/post/",
            "https://someone.blogspot.com/2024/01/post.html",
            "https://dev.to/someone/post",
            "https://someone.github.io/posts/hello",
            "https://someone.notion.site/abc",
            "https://www.baeldung.com/spring-boot",
            "https://blog.jetbrains.com/kotlin/2024/01/post/"
    })
    void classifiesBlogs(String url) {
        assertThat(SiteCategory.classify(url)).isEqualTo(SiteCategory.BLOG);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "https://github.com/spring-projects/spring-boot",
            "https://gist.github.com/someone/abc",
            "https://docs.github.com/en/actions",
            "https://gitlab.com/group/project",
            "https://docs.spring.io/spring-boot/reference/",
            "https://spring.io/guides",
            "https://docs.oracle.com/en/java/javase/17/docs/api/",
            "https://developer.mozilla.org/ko/docs/Web/JavaScript",
            "https://developers.naver.com/docs/common/openapiguide/",
            "https://developers.kakao.com/docs/latest/ko/kakaologin/common",
            "https://developer.android.com/guide",
            "https://developers.google.com/maps",
            "https://learn.microsoft.com/ko-kr/dotnet/",
            "https://docs.python.org/3/library/",
            "https://react.dev/reference/react/useState",
            "https://ko.react.dev/learn",
            "https://www.w3.org/TR/html52/",
            "https://requests.readthedocs.io/en/latest/",
            "https://www.npmjs.com/package/react",
            "https://docs.rs/serde",
            "https://www.data.go.kr/data/15000000/openapi.do",
            "https://www.law.go.kr/법령/민법",
            "https://www.snu.ac.kr/",
            "https://cs.stanford.edu/",
            "https://www.nasa.gov/",
            "https://www.korea.kr/news/policyNewsView.do"
    })
    void classifiesOfficialDocs(String url) {
        assertThat(SiteCategory.classify(url)).isEqualTo(SiteCategory.OFFICIAL_DOCS);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "https://cafe.naver.com/joonggonara/123",
            "https://m.cafe.naver.com/ca-fe/web/cafes/123/articles/456",
            "https://kin.naver.com/qna/detail.naver?d1id=1&dirId=1&docId=1",
            "https://cafe.daum.net/someCafe/abc/1",
            "https://m.cafe.daum.net/someCafe/abc/1",
            "https://gall.dcinside.com/board/view/?id=programming&no=1",
            "https://m.dcinside.com/board/programming/1",
            "https://www.fmkorea.com/123",
            "https://theqoo.net/hot/123",
            "https://bbs.ruliweb.com/community/board/300143/read/1",
            "https://www.clien.net/service/board/park/1",
            "https://www.ppomppu.co.kr/zboard/view.php?id=freeboard&no=1",
            "https://www.instiz.net/pt/1",
            "https://pann.nate.com/talk/1",
            "https://mlbpark.donga.com/mp/b.php?b=bullpen&id=1",
            "https://www.bobaedream.co.kr/view?code=freeb&No=1",
            "https://www.inven.co.kr/board/lol/1/1",
            "https://okky.kr/articles/1",
            "https://www.reddit.com/r/java/comments/abc/",
            "https://stackoverflow.com/questions/1/abc",
            "https://unix.stackexchange.com/questions/1",
            "https://www.quora.com/What-is-Java",
            "https://news.ycombinator.com/item?id=1",
            "https://x.com/someone/status/1",
            "https://twitter.com/someone/status/1",
            "https://forum.unity.com/threads/1/"
    })
    void classifiesCommunities(String url) {
        assertThat(SiteCategory.classify(url)).isEqualTo(SiteCategory.COMMUNITY);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "https://news.naver.com/main/read.naver?oid=001&aid=1",
            "https://m.news.naver.com/article/001/0000000001",
            "https://sports.news.naver.com/news?oid=1&aid=1",
            "https://m.sports.naver.com/article/001/0000000001",
            "https://m.entertain.naver.com/article/001/0000000001",
            "https://v.daum.net/v/20240101000000000",
            "https://news.daum.net/society",
            "https://www.yna.co.kr/view/AKR20240101000100001",
            "https://www.chosun.com/national/2024/01/01/ABC/",
            "https://biz.chosun.com/it-science/2024/01/01/ABC/",
            "https://www.joongang.co.kr/article/1",
            "https://www.donga.com/news/article/all/20240101/1/1",
            "https://www.hani.co.kr/arti/society/1.html",
            "https://www.khan.co.kr/article/1",
            "https://www.hankyung.com/article/2024010100001",
            "https://news.kbs.co.kr/news/pc/view/view.do?ncd=1",
            "https://imnews.imbc.com/news/2024/society/article/1.html",
            "https://news.sbs.co.kr/news/endPage.do?news_id=N1",
            "https://news.jtbc.co.kr/article/article.aspx?news_id=NB1",
            "https://zdnet.co.kr/view/?no=20240101000000",
            "https://www.nytimes.com/2024/01/01/world/abc.html",
            "https://www.bbc.co.uk/news/world-1",
            "https://edition.cnn.com/2024/01/01/world/abc",
            "https://www.reuters.com/world/abc-2024-01-01/",
            "https://techcrunch.com/2024/01/01/abc/",
            "https://news.samsung.com/kr/abc"
    })
    void classifiesNews(String url) {
        assertThat(SiteCategory.classify(url)).isEqualTo(SiteCategory.NEWS);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "https://www.naver.com/",
            "https://naver.com",
            "https://m.naver.com/",
            "https://search.naver.com/search.naver?query=spring",
            "https://comic.naver.com/webtoon",
            "https://www.daum.net/",
            "https://search.daum.net/search?q=spring",
            "https://www.google.com/search?q=spring",
            "https://docs.google.com/document/d/abc/edit",
            "https://www.youtube.com/watch?v=abc",
            "https://namu.wiki/w/Java",
            "https://ko.wikipedia.org/wiki/자바",
            "https://www.coupang.com/vp/products/1",
            "https://chatgpt.com/c/abc",
            "https://unknown-site.example/abc"
    })
    void classifiesOthersAsEtc(String url) {
        assertThat(SiteCategory.classify(url)).isEqualTo(SiteCategory.ETC);
    }

    @Test
    void naverSubdomainsAreClassifiedSeparately() {
        assertThat(SiteCategory.classify("https://blog.naver.com/a/1")).isEqualTo(SiteCategory.BLOG);
        assertThat(SiteCategory.classify("https://n.news.naver.com/article/1/1")).isEqualTo(SiteCategory.NEWS);
        assertThat(SiteCategory.classify("https://cafe.naver.com/a/1")).isEqualTo(SiteCategory.COMMUNITY);
        assertThat(SiteCategory.classify("https://kin.naver.com/qna/1")).isEqualTo(SiteCategory.COMMUNITY);
        assertThat(SiteCategory.classify("https://developers.naver.com/docs")).isEqualTo(SiteCategory.OFFICIAL_DOCS);
        assertThat(SiteCategory.classify("https://www.naver.com")).isEqualTo(SiteCategory.ETC);
    }

    @Test
    void daumSubdomainsAreClassifiedSeparately() {
        assertThat(SiteCategory.classify("https://blog.daum.net/a/1")).isEqualTo(SiteCategory.BLOG);
        assertThat(SiteCategory.classify("https://cafe.daum.net/a/1")).isEqualTo(SiteCategory.COMMUNITY);
        assertThat(SiteCategory.classify("https://v.daum.net/v/1")).isEqualTo(SiteCategory.NEWS);
        assertThat(SiteCategory.classify("https://news.daum.net/1")).isEqualTo(SiteCategory.NEWS);
        assertThat(SiteCategory.classify("https://www.daum.net")).isEqualTo(SiteCategory.ETC);
    }

    @Test
    void mostSpecificDomainWins() {
        assertThat(SiteCategory.classify("https://www.donga.com/news/1")).isEqualTo(SiteCategory.NEWS);
        assertThat(SiteCategory.classify("https://mlbpark.donga.com/mp/1")).isEqualTo(SiteCategory.COMMUNITY);
        assertThat(SiteCategory.classify("https://github.com/a/b")).isEqualTo(SiteCategory.OFFICIAL_DOCS);
        assertThat(SiteCategory.classify("https://someone.github.io/a")).isEqualTo(SiteCategory.BLOG);
    }

    @Test
    void domainRuleBeatsPrefixRule() {
        // news. 접두사보다 도메인 규칙이 먼저
        assertThat(SiteCategory.classify("https://news.ycombinator.com/item?id=1")).isEqualTo(SiteCategory.COMMUNITY);
        // docs. 접두사보다 기타 예외 도메인이 먼저
        assertThat(SiteCategory.classify("https://docs.google.com/spreadsheets/d/abc")).isEqualTo(SiteCategory.ETC);
    }

    @Test
    void matchesOnLabelBoundaryOnly() {
        assertThat(SiteCategory.classify("https://notvelog.io/a")).isEqualTo(SiteCategory.ETC);
        assertThat(SiteCategory.classify("https://box.com/a")).isEqualTo(SiteCategory.ETC);
        assertThat(SiteCategory.classify("https://mygithub.com/a")).isEqualTo(SiteCategory.ETC);
        assertThat(SiteCategory.classify("https://example.go.kr.evil.com/a")).isEqualTo(SiteCategory.ETC);
    }

    @Test
    void normalizesHost() {
        assertThat(SiteCategory.classify("HTTPS://WWW.Velog.IO/@someone")).isEqualTo(SiteCategory.BLOG);
        assertThat(SiteCategory.classify("https://www.reddit.com:443/r/java")).isEqualTo(SiteCategory.COMMUNITY);
        assertThat(SiteCategory.classify("http://news.naver.com.:8080/a")).isEqualTo(SiteCategory.NEWS);
        assertThat(SiteCategory.classify("https://user@stackoverflow.com/questions/1")).isEqualTo(SiteCategory.COMMUNITY);
        assertThat(SiteCategory.classify("  https://www.chosun.com/a  ")).isEqualTo(SiteCategory.NEWS);
        assertThat(SiteCategory.classify("blog.naver.com/someone/1")).isEqualTo(SiteCategory.BLOG);
    }

    @Test
    void handlesHostWithUnderscore() {
        assertThat(SiteCategory.classify("https://my_blog.tistory.com/1")).isEqualTo(SiteCategory.BLOG);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {
            "   ",
            "not a url",
            "https://",
            "https://exa mple.com/a",
            "http://[invalid",
            "javascript:void(0)",
            "about:blank"
    })
    void invalidUrlIsEtc(String url) {
        assertThat(SiteCategory.classify(url)).isEqualTo(SiteCategory.ETC);
    }

    @ParameterizedTest
    @CsvSource(delimiter = ' ', value = {
            "BLOG https://blog.naver.com/x?a=1|2",
            "OFFICIAL_DOCS https://docs.spring.io/a?q={b}",
            "COMMUNITY https://stackoverflow.com/q?x=%zz",
    })
    void queryThatUriRejectsDoesNotChangeCategory(SiteCategory expected, String url) {
        assertThat(SiteCategory.classify(url)).isEqualTo(expected);
    }

    @Test
    void urlInQueryIsNotTakenForScheme() {
        assertThat(SiteCategory.classify("blog.naver.com/x?from=http://a.com")).isEqualTo(SiteCategory.BLOG);
    }

    @Test
    void extractsNormalizedHost() {
        assertThat(SiteCategory.extractHost("https://WWW.Example.com:8080/a?b=c")).contains("example.com");
        assertThat(SiteCategory.extractHost(null)).isEmpty();
    }
}
