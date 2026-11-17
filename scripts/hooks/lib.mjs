// git hook 공통 함수 (husky 에서 부른다)
import { execFileSync } from "node:child_process";

export function git(...args) {
  return execFileSync("git", args, { encoding: "utf8" }).trim();
}

export function currentBranch() {
  try {
    return git("rev-parse", "--abbrev-ref", "HEAD");
  } catch {
    return "";
  }
}

/** QA 브랜치: 새 형식 <이슈번호>-qa-<설명>, 예전 형식 qa-<번호>-<설명> */
export function isQaBranch(branch) {
  return /-qa-/.test(branch) || /^qa-/.test(branch);
}

export function fail(title, errors) {
  console.error(`\n✖ ${title} 검사에 걸렸습니다.`);
  for (const e of errors) console.error(`  - ${e}`);
  console.error("");
  process.exit(1);
}

export function warn(title, warnings) {
  if (!warnings.length) return;
  console.warn(`\n⚠ ${title}`);
  for (const w of warnings) console.warn(`  - ${w}`);
  console.warn("");
}

export function ok() {
  process.exit(0);
}
