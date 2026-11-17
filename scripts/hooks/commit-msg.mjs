// commit-msg — 정책 #5 커밋 형식 · #6 Claude 흔적 · #7 QA 커밋·브랜치
// 사용: node scripts/hooks/commit-msg.mjs <메시지 파일>
import { existsSync, readFileSync } from "node:fs";
import { homedir } from "node:os";
import { join } from "node:path";
import { currentBranch, fail, isQaBranch, ok } from "./lib.mjs";

const TYPES = "feat|fix|chore|refactor|docs|test";
// 콜론 뒤가 이 단어 하나뿐이면 내용이 없는 메시지로 본다 (#5, 글자 수로는 막지 않는다)
const EMPTY_WORDS = ["수정", "변경", "업데이트", "추가", "삭제", "작업", "정리", "wip", "test", "fix", "update", "change", "edit", "tmp", "temp"];
const QA_DIR = join(homedir(), "Desktop/obsidian-db/justinneed-qa");

const file = process.argv[2];
const raw = readFileSync(file, "utf8");
// git 이 넣는 주석 줄은 메시지가 아니다
const message = raw.split("\n").filter((line) => !line.startsWith("#")).join("\n").trim();
const subject = message.split("\n")[0].trim();
const errors = [];

// git 이 만드는 메시지는 통과
if (/^(Merge|Revert) /.test(subject)) ok();

// #6 Claude 흔적
if (/co-authored-by:\s*claude/i.test(message) || /generated with \[?claude code/i.test(message) || /noreply@anthropic\.com/i.test(message)) {
  errors.push("Claude 흔적(Co-Authored-By: Claude, Generated with Claude Code)이 있습니다. 지우고 다시 커밋하세요.");
}

// #5 형식 · 내용
const format = new RegExp(`^(${TYPES}) : (.+)$`).exec(subject);
if (!format) {
  errors.push(`첫 줄은 '타입 : 내용' 형식이어야 합니다 (${TYPES.replaceAll("|", ", ")}, 콜론 앞뒤 공백 한 칸). 지금: "${subject}"`);
} else if (EMPTY_WORDS.includes(format[2].trim().toLowerCase())) {
  errors.push(`'${format[2].trim()}' 만으로는 무엇을 했는지 알 수 없습니다. 바꾼 내용을 적어 주세요.`);
}

// #7 QA 커밋 · 브랜치
const branch = currentBranch();
const qaIds = [...subject.matchAll(/\bQA-(\d{3,})(?:-\d+)?\b/g)].map((m) => `QA-${m[1]}`);
if (qaIds.length > 0 && !isQaBranch(branch)) {
  errors.push(`QA 커밋(${qaIds.join(", ")})은 QA 브랜치(<이슈번호>-qa-<설명>)에서만 만듭니다. 지금 브랜치: ${branch}`);
}
if (isQaBranch(branch) && format && !/^fix : QA-\d{3,}-\d+ \S/.test(subject)) {
  errors.push(`QA 브랜치의 커밋은 'fix : QA-NNN-N 내용' 형식입니다 (에러 단위 커밋). 지금: "${subject}"`);
}
// QA 페이지가 있어야 한다 (옵시디언 폴더가 있는 컴퓨터에서만)
if (qaIds.length > 0 && existsSync(QA_DIR)) {
  for (const id of new Set(qaIds)) {
    if (!existsSync(join(QA_DIR, "QA each", `${id}.md`)) && !existsSync(join(QA_DIR, `${id}.md`))) {
      errors.push(`${id}.md 가 QA 폴더에 없습니다. QA 번호를 확인하세요.`);
    }
  }
}

if (errors.length) fail("커밋 메시지", errors);
ok();
