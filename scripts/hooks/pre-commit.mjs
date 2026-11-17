// pre-commit — 정책 #9 로컬 전용 파일 · #10 비밀값 · #11 커밋 내용 · #12 작성자
// 커밋할 파일(staged) 기준으로 검사한다. 관련 테스트 · ESLint(#8)는 lint-staged 가 따로 돈다.
import { execFileSync, spawnSync } from "node:child_process";
import { statSync } from "node:fs";
import { fail, git, warn } from "./lib.mjs";

// #12 팀원 3명 (optiprime27 · beolene1 · lcj2008). 한 사람이 여러 이메일을 쓰면 모두 넣는다
const ALLOWED_EMAILS = [
  "yesong0427@gmail.com",
  "144100460+optiprime27@users.noreply.github.com",
  "beolene@gmail.com",
  "leechanjong01@gmail.com",
];
const MAX_BYTES = 1024 * 1024; // #11 1MB

const staged = git("diff", "--cached", "--name-only", "--diff-filter=ACMR").split("\n").filter(Boolean);
const errors = [];
const warnings = [];

// #12 작성자
const email = git("config", "user.email");
if (!ALLOWED_EMAILS.includes(email.toLowerCase())) {
  errors.push(`git 작성자 이메일(${email})이 팀원 목록에 없습니다. git config user.email 을 확인하세요.`);
}

// #9 로컬 전용 파일
const LOCAL_ONLY = [
  [/^docs\//, "docs/ 는 로컬 전용입니다"],
  [/(^|\/)\.env(\..+)?$/, ".env 계열은 커밋하지 않습니다"],
  [/(^|\/)(QA|DEV)-\d+\.md$/, "QA 문서는 옵시디언 전용입니다"],
  [/(^|\/)screenshots\//, "QA 스크린샷은 커밋하지 않습니다"],
  [/^\.claude\//, ".claude/ 는 로컬 전용입니다"],
  [/^[^/]*(트래킹표|정책|방법론)[^/]*\.md$/, "루트 md 문서는 로컬 전용입니다"],
];
for (const file of staged) {
  for (const [pattern, reason] of LOCAL_ONLY) {
    if (pattern.test(file)) errors.push(`${file}: ${reason}`);
  }
}

// #11 커밋 내용
for (const file of staged) {
  let size = 0;
  try {
    size = statSync(file).size;
  } catch {
    continue;
  }
  if (size > MAX_BYTES) {
    errors.push(`${file}: ${(size / 1024 / 1024).toFixed(1)}MB — 1MB 넘는 파일은 커밋하지 않습니다`);
    continue;
  }
  const text = execFileSync("git", ["show", `:${file}`], { encoding: "utf8", maxBuffer: MAX_BYTES * 2 });
  if (/^(<{7}|={7}|>{7})( |$)/m.test(text)) errors.push(`${file}: 충돌 표시(<<<<<<< ======= >>>>>>>)가 남아 있습니다`);
  if (/\.test\.[jt]sx?$/.test(file) && /\b(it|describe|test)\.only\s*\(/.test(text)) {
    errors.push(`${file}: it.only · describe.only 가 남아 있어 나머지 테스트가 돌지 않습니다`);
  }
  if (/\.[jt]sx?$/.test(file) && !/\.test\./.test(file) && /\bconsole\.log\s*\(/.test(text)) {
    warnings.push(`${file}: console.log 가 있습니다 (경고만)`);
  }
  if (/src\/main\/.*\.java$/.test(file) && /\bSystem\.(out|err)\.print/.test(text)) {
    warnings.push(`${file}: System.out.println 이 있습니다 (경고만)`);
  }
}

// #10 비밀값 (gitleaks)
const gitleaks = spawnSync("gitleaks", ["protect", "--staged", "--redact", "--no-banner"], { stdio: "inherit" });
if (gitleaks.error) {
  warnings.push("gitleaks 가 설치되어 있지 않아 비밀값 검사를 건너뛰었습니다 (brew install gitleaks)");
} else if (gitleaks.status !== 0) {
  errors.push("gitleaks 가 비밀값으로 보이는 내용을 찾았습니다. 위 결과를 확인하세요");
}

warn("커밋 내용 경고", warnings);
if (errors.length) fail("커밋 전", errors);
