package com.justinneed.session.analysis.category;

import java.util.List;

/**
 * {@link SiteCategory} 분류에 쓰는 도메인 규칙 목록.
 *
 * <ul>
 *     <li>DOMAINS: host 가 도메인과 같거나 {@code "." + 도메인} 으로 끝나면 일치 (서브도메인 포함)</li>
 *     <li>PREFIXES: host 의 맨 앞 라벨이 일치 (예: {@code docs.}, {@code developer.})</li>
 *     <li>SUFFIXES: host 가 {@code "." + 접미사} 로 끝나면 일치 (예: {@code go.kr}, {@code edu})</li>
 * </ul>
 *
 * <p>모든 값은 소문자, {@code www.} 없이 작성한다. 같은 도메인을 두 카테고리에 넣으면 클래스 로딩 시 예외가 발생한다.
 * 국내 사용자가 많이 방문하는 사이트를 먼저, 그 다음 주요 글로벌 사이트 순으로 정리한다.
 */
final class SiteCategoryDomains {

    private SiteCategoryDomains() {
    }

    // ===================== 블로그 =====================

    static final List<String> BLOG_DOMAINS = List.of(
            // 국내 포털 블로그
            "blog.naver.com",
            "post.naver.com",
            "blog.me",
            "blog.daum.net",
            "tistory.com",
            "brunch.co.kr",
            "egloos.com",
            // 국내 개발 블로그 플랫폼
            "velog.io",
            "oopy.io",
            "yozm.wishket.com",
            // 국내 기업 기술 블로그
            "d2.naver.com",
            "tech.kakao.com",
            "tech.kakaopay.com",
            "techblog.woowahan.com",
            "toss.tech",
            "helloworld.kurly.com",
            "techblog.lycorp.co.jp",
            "engineering.linecorp.com",
            // 글로벌 블로그 플랫폼
            "medium.com",
            "substack.com",
            "wordpress.com",
            "blogspot.com",
            "blogger.com",
            "tumblr.com",
            "ghost.io",
            "dev.to",
            "hashnode.dev",
            "hashnode.com",
            "hackernoon.com",
            "towardsdatascience.com",
            "notion.site",
            // 개인 블로그 호스팅 (github.com 은 공식문서, *.github.io 는 블로그)
            "github.io",
            "gitlab.io",
            "netlify.app",
            "vercel.app",
            // 기술 아티클 사이트
            "baeldung.com",
            "geeksforgeeks.org",
            "mkyong.com"
    );

    static final List<String> BLOG_PREFIXES = List.of(
            "blog.",
            "blogs.",
            "techblog."
    );

    // ===================== 공식문서 =====================

    static final List<String> OFFICIAL_DOCS_DOMAINS = List.of(
            // 코드 저장소
            "github.com",
            "gitlab.com",
            "bitbucket.org",
            // 국내 공공
            "korea.kr",
            // 웹 표준 / 레퍼런스
            "developer.mozilla.org",
            "w3.org",
            "whatwg.org",
            "ecma-international.org",
            "ietf.org",
            "rfc-editor.org",
            "unicode.org",
            "web.dev",
            "devdocs.io",
            "cppreference.com",
            "man7.org",
            "git-scm.com",
            // 클라우드 / 플랫폼 문서
            "learn.microsoft.com",
            "cloud.google.com",
            "firebase.google.com",
            "platform.openai.com",
            // Java / JVM
            "spring.io",
            "openjdk.org",
            "kotlinlang.org",
            "gradle.org",
            "apache.org",
            "hibernate.org",
            "junit.org",
            "projectlombok.org",
            "thymeleaf.org",
            // Python
            "python.org",
            "djangoproject.com",
            "palletsprojects.com",
            "tiangolo.com",
            "pytorch.org",
            "tensorflow.org",
            "numpy.org",
            "pydata.org",
            "scikit-learn.org",
            // JavaScript / 프론트엔드
            "nodejs.org",
            "typescriptlang.org",
            "react.dev",
            "reactjs.org",
            "vuejs.org",
            "angular.dev",
            "angular.io",
            "svelte.dev",
            "nextjs.org",
            "nuxt.com",
            "nestjs.com",
            "vite.dev",
            "vitejs.dev",
            "webpack.js.org",
            "tailwindcss.com",
            "getbootstrap.com",
            "jestjs.io",
            "graphql.org",
            // 기타 언어 / 모바일
            "go.dev",
            "golang.org",
            "rust-lang.org",
            "swift.org",
            "flutter.dev",
            "dart.dev",
            "php.net",
            "ruby-lang.org",
            "rubyonrails.org",
            // 인프라 / DB
            "kubernetes.io",
            "postgresql.org",
            "dev.mysql.com",
            "redis.io",
            "nginx.org",
            // 패키지 저장소
            "readthedocs.io",
            "readthedocs.org",
            "gitbook.io",
            "npmjs.com",
            "pypi.org",
            "mvnrepository.com",
            "central.sonatype.com",
            "crates.io",
            "pkg.go.dev"
    );

    static final List<String> OFFICIAL_DOCS_PREFIXES = List.of(
            "docs.",
            "doc.",
            "developer.",
            "developers."
    );

    static final List<String> OFFICIAL_DOCS_SUFFIXES = List.of(
            // 국내 정부 / 대학 / 연구기관 / 군
            "go.kr",
            "ac.kr",
            "re.kr",
            "mil.kr",
            // 해외 정부 / 대학
            "gov",
            "edu",
            "mil",
            "gov.uk",
            "ac.uk",
            "go.jp",
            "ac.jp"
    );

    // ===================== 커뮤니티 =====================

    static final List<String> COMMUNITY_DOMAINS = List.of(
            // 국내 포털 커뮤니티 / Q&A
            "cafe.naver.com",
            "kin.naver.com",
            "cafe.daum.net",
            "pann.nate.com",
            "band.us",
            // 국내 종합 커뮤니티
            "dcinside.com",
            "fmkorea.com",
            "theqoo.net",
            "ruliweb.com",
            "clien.net",
            "ppomppu.co.kr",
            "instiz.net",
            "mlbpark.donga.com",
            "bobaedream.co.kr",
            "inven.co.kr",
            "todayhumor.co.kr",
            "humoruniv.com",
            "etoland.co.kr",
            "82cook.com",
            "dogdrip.net",
            "slrclub.com",
            "ygosu.com",
            "arca.live",
            "pgr21.com",
            "damoang.net",
            "quasarzone.com",
            "coolenjoy.net",
            "dvdprime.com",
            "ddanzi.com",
            "meeco.kr",
            "orbi.kr",
            "everytime.kr",
            "teamblind.com",
            // 국내 개발 커뮤니티
            "okky.kr",
            "hashcode.co.kr",
            "careerly.co.kr",
            "disquiet.io",
            // 글로벌 커뮤니티 / Q&A
            "reddit.com",
            "redd.it",
            "stackoverflow.com",
            "stackexchange.com",
            "superuser.com",
            "serverfault.com",
            "askubuntu.com",
            "mathoverflow.net",
            "quora.com",
            "zhihu.com",
            "news.ycombinator.com",
            "lobste.rs",
            "slashdot.org",
            "producthunt.com",
            // SNS
            "x.com",
            "twitter.com",
            "threads.net",
            "threads.com",
            "facebook.com",
            "instagram.com",
            "linkedin.com"
    );

    static final List<String> COMMUNITY_PREFIXES = List.of(
            "forum.",
            "forums.",
            "community.",
            "discuss."
    );

    // ===================== 뉴스 =====================

    static final List<String> NEWS_DOMAINS = List.of(
            // 포털 뉴스
            "news.naver.com",
            "media.naver.com",
            "entertain.naver.com",
            "sports.naver.com",
            "news.daum.net",
            "v.daum.net",
            "entertain.daum.net",
            "sports.daum.net",
            "news.nate.com",
            "news.zum.com",
            "news.google.com",
            // 통신사
            "yna.co.kr",
            "yonhapnewstv.co.kr",
            "news1.kr",
            "newsis.com",
            // 종합 일간지
            "chosun.com",
            "joongang.co.kr",
            "joins.com",
            "donga.com",
            "hani.co.kr",
            "khan.co.kr",
            "hankookilbo.com",
            "kmib.co.kr",
            "seoul.co.kr",
            "segye.com",
            "munhwa.com",
            "naeil.com",
            "nocutnews.co.kr",
            "koreaherald.com",
            "koreatimes.co.kr",
            // 경제지
            "mk.co.kr",
            "hankyung.com",
            "sedaily.com",
            "edaily.co.kr",
            "mt.co.kr",
            "fnnews.com",
            "asiae.co.kr",
            "heraldcorp.com",
            "ajunews.com",
            "newspim.com",
            "thebell.co.kr",
            "bizwatch.co.kr",
            "businesspost.co.kr",
            "wowtv.co.kr",
            // 방송
            "kbs.co.kr",
            "imbc.com",
            "mbc.co.kr",
            "sbs.co.kr",
            "jtbc.co.kr",
            "ytn.co.kr",
            "mbn.co.kr",
            "ichannela.com",
            "tvchosun.com",
            // 인터넷 / 시사 매체
            "ohmynews.com",
            "pressian.com",
            "mediatoday.co.kr",
            "sisain.co.kr",
            "sisajournal.com",
            "vop.co.kr",
            "newstapa.org",
            "insight.co.kr",
            "wikitree.co.kr",
            "huffingtonpost.kr",
            // IT / 테크
            "zdnet.co.kr",
            "etnews.com",
            "bloter.net",
            "itworld.co.kr",
            "ciokorea.com",
            "ddaily.co.kr",
            "dt.co.kr",
            "inews24.com",
            "byline.network",
            "platum.kr",
            "venturesquare.net",
            // 스포츠 / 연예
            "osen.co.kr",
            "sportschosun.com",
            "sportsseoul.com",
            "xportsnews.com",
            "mydaily.co.kr",
            "isplus.com",
            "spotvnews.co.kr",
            "dispatch.co.kr",
            "newsen.com",
            "topstarnews.net",
            // 지역
            "kookje.co.kr",
            "busan.com",
            "imaeil.com",
            "yeongnam.com",
            "kwnews.co.kr",
            // 글로벌
            "nytimes.com",
            "bbc.com",
            "bbc.co.uk",
            "cnn.com",
            "reuters.com",
            "bloomberg.com",
            "theguardian.com",
            "wsj.com",
            "apnews.com",
            "washingtonpost.com",
            "ft.com",
            "economist.com",
            "forbes.com",
            "cnbc.com",
            "foxnews.com",
            "nbcnews.com",
            "abcnews.go.com",
            "cbsnews.com",
            "npr.org",
            "aljazeera.com",
            "usatoday.com",
            "latimes.com",
            "time.com",
            "politico.com",
            "axios.com",
            "businessinsider.com",
            "independent.co.uk",
            "telegraph.co.uk",
            "dailymail.co.uk",
            "news.yahoo.com",
            "nikkei.com",
            "nhk.or.jp",
            "japantimes.co.jp",
            "scmp.com",
            "lemonde.fr",
            "spiegel.de",
            // 글로벌 IT / 테크
            "techcrunch.com",
            "theverge.com",
            "wired.com",
            "arstechnica.com",
            "engadget.com",
            "zdnet.com",
            "venturebeat.com",
            "theinformation.com"
    );

    static final List<String> NEWS_PREFIXES = List.of(
            "news."
    );

    // ===================== 기타 (접두사 규칙 예외) =====================

    /**
     * 접두사 규칙에 걸리지만 실제로는 다른 성격인 도메인. (예: docs.google.com 은 문서 편집기)
     */
    static final List<String> ETC_DOMAINS = List.of(
            "docs.google.com",
            "docs.qq.com"
    );
}
