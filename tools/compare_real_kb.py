#!/usr/bin/env python3
"""证明仪器测试没有动真机上的真实知识库。

    python tools/compare_real_kb.py save      # 测试之前：记下基线
    python tools/compare_real_kb.py verify    # 测试之后：核对没变

为什么需要它：`ReplaceAllInstrumentedTest` 的第一个用例就会把库整表删光再写，
而它跑在**目标应用的进程里**（跟真实数据同一个 uid）。测试内部确实把库重定向到了
缓存目录，但"我以为隔离住了"和"确实没碰"是两件事 —— 这个脚本负责后者。

比的是**内容**（每条记录的五个字段），不是文件字节：WAL 模式下即使没人写，
文件本身也可能因为检查点而变化，那是正常的。内容变了才是真出事。
"""

import hashlib
import json
import sqlite3
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
ADB = "E:/Android/Sdk/platform-tools/adb.exe"
PKG = "com.example.explaindot"
BASELINE = ROOT / "build" / "real-kb-baseline.json"


def pull_and_fingerprint():
    """拉下真库，算一个内容指纹"""
    tmp = Path(tempfile.mkdtemp(prefix="edb-verify-"))
    for name in ("knowledge.db", "knowledge.db-wal", "knowledge.db-shm"):
        blob = subprocess.run(
            [ADB, "exec-out", "run-as", PKG, "cat", "databases/" + name],
            capture_output=True, check=True,
        ).stdout
        (tmp / name).write_bytes(blob)

    con = sqlite3.connect(tmp / "knowledge.db")
    rows = list(con.execute(
        "select term, tag, body, created_at, updated_at from concepts order by term, tag"))
    con.close()

    payload = json.dumps(rows, ensure_ascii=False).encode("utf-8")
    return {
        "count": len(rows),
        "fingerprint": hashlib.sha256(payload).hexdigest(),
        "terms": [r[0] for r in rows],
    }


def main() -> int:
    mode = sys.argv[1] if len(sys.argv) > 1 else "verify"
    if mode not in ("save", "verify"):
        print(__doc__)
        return 2

    if not Path(ADB).exists():
        print("找不到 adb: %s" % ADB)
        return 1

    now = pull_and_fingerprint()

    if mode == "save":
        BASELINE.parent.mkdir(exist_ok=True)
        BASELINE.write_text(json.dumps(now, ensure_ascii=False, indent=1), encoding="utf-8")
        print("基线已记录")
        print("  条数       %d" % now["count"])
        print("  内容指纹   %s" % now["fingerprint"][:16])
        print("  存到       %s" % BASELINE.relative_to(ROOT))
        return 0

    if not BASELINE.exists():
        print("还没有基线 —— 先跑：python tools/compare_real_kb.py save")
        return 1

    was = json.loads(BASELINE.read_text(encoding="utf-8"))
    same = was["fingerprint"] == now["fingerprint"]

    print("真机知识库（跑完仪器测试之后）")
    print("  测试前   %d 条   指纹 %s" % (was["count"], was["fingerprint"][:16]))
    print("  现在     %d 条   指纹 %s" % (now["count"], now["fingerprint"][:16]))
    print()
    if same:
        print("✓ 内容一字未变 —— 仪器测试确实没碰真实数据")
    else:
        print("✗ 内容变了！")
        before, after = set(was["terms"]), set(now["terms"])
        gone, added = before - after, after - before
        if gone:
            print("  少了 %d 个概念: %s" % (len(gone), sorted(gone)[:10]))
        if added:
            print("  多了 %d 个概念: %s" % (len(added), sorted(added)[:10]))
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
