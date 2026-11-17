// pre-push — 정책 #15 브랜치 · 이슈 번호 (전체 테스트 · tsc(#14)는 .husky/pre-push 가 이어서 돈다)
// 기존 브랜치(원격에 이미 있는 브랜치)는 이름을 검사하지 않고, 새 브랜치에만 적용한다.
import { spawnSync } from "node:child_process";
import { readFileSync } from "node:fs";
import { currentBranch, fail, git, warn } from "./lib.mjs";

const BRANCH = /^(\d+)-(feature|fix|chore|refactor|docs|qa)-/;
// 이슈 라벨(이슈 양식) → 맞는 브랜치 종류
const LABEL_TYPES = { Bug: ["fix", "qa"], Feature: ["feature"], Refactor: ["refactor"], Chore: ["chore", "docs"] };

const branch = currentBranch();
const remote = process.argv[2] || "origin";
// stdin: <local ref> <local sha> <remote ref> <remote sha>
const lines = readFileSync(0, "utf8").split("\n").filter(Boolean);
const pushingMain = lines.some((l) => l.split(" ")[2] === "refs/heads/main");
const errors = [];
const warnings = [];

if (pushingMain) errors.push("main 에는 직접 푸시하지 않습니다. 브랜치를 푸시하고 PR 을 만드세요.");

let existsOnRemote = false;
try {
  existsOnRemote = git("ls-remote", "--heads", remote, branch) !== "";
} catch {
  existsOnRemote = false;
}

if (!existsOnRemote && branch !== "main") {
  const m = BRANCH.exec(branch);
  if (!m && /^qa-/.test(branch)) {
    warnings.push(`예전 형식 QA 브랜치(${branch})입니다. 새 QA 브랜치는 <이슈번호>-qa-<설명> 으로 만드세요.`);
  } else if (!m) {
    errors.push(`새 브랜치 이름은 '이슈번호-타입-이름' 형식이어야 합니다 (타입: feature, fix, chore, refactor, docs, qa). 지금: ${branch}`);
  } else {
    const [, number, type] = m;
    const view = spawnSync("gh", ["issue", "view", number, "--json", "state,labels"], { encoding: "utf8" });
    if (view.error) {
      warnings.push("gh 가 없어 이슈 확인을 건너뛰었습니다");
    } else if (view.status !== 0) {
      errors.push(`이슈 #${number} 를 찾지 못했습니다. 이슈를 먼저 만드세요.`);
    } else {
      const issue = JSON.parse(view.stdout);
      if (issue.state !== "OPEN") errors.push(`이슈 #${number} 가 열려 있지 않습니다 (${issue.state}).`);
      const labels = issue.labels.map((l) => l.name);
      const expected = Object.entries(LABEL_TYPES).filter(([label]) => labels.some((n) => n.includes(label))).flatMap(([, types]) => types);
      if (expected.length && !expected.includes(type)) {
        warnings.push(`이슈 #${number} 라벨(${labels.join(", ")})과 브랜치 종류(${type})가 다릅니다`);
      }
    }
  }
}

warn("푸시 전 경고", warnings);
if (errors.length) fail("푸시 전", errors);
