#!/usr/bin/env python3
"""示例零售 - 知识库服务 (port 18602)
# 示例零售 - 知识库服务 (独立微服务)
"""
import sqlite3, time, os, sys, threading, logging, json, signal, re
sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))
from utils.errors import ok_response, err_response, setup_logging
from utils.validate import validate_request, parse_json_body
from utils.logging import set_request_id
from knowledge.kb_common import ensure_kb_columns
from http.server import HTTPServer, BaseHTTPRequestHandler
from socketserver import ThreadingMixIn
from urllib.parse import urlparse, parse_qs
from typing import Any

DB_PATH = os.environ.get("WUJIN_DB_PATH", "/data/wujin.db")
START_TIME = time.time()

_local = threading.local()
_metrics_lock = threading.Lock()
_request_count = 0
_error_count = 0


# ─── 查询分词（2026-09-18：FTS unicode61 中文连串整 token，整句查询命中率低）───

_jieba = None
_JIEBA_TRIED = False
_KB_TOKEN_RE = re.compile(r"[0-9A-Za-z][0-9A-Za-z.*+×x\-]*|[\u4e00-\u9fff]+")
_KB_CJK_RE = re.compile(r"[\u4e00-\u9fff]+")
_KB_FTS_SAFE_RE = re.compile(r"[^0-9A-Za-z\u4e00-\u9fff.*+×\-]")
# 高频虚词单切出来只会稀释 LIKE-AND 命中（FTS OR 档同理），不入 token
_KB_STOPWORDS = {"什么", "怎么", "怎样", "请问", "意思", "如何", "为什么", "一下",
                 "是不是", "应该", "可以", "还是", "多大", "多少", "多大功率"}
# 硬件行话词表（jieba 默认词库切不开的五金词，search 同款惯例）
_KB_HARDWARE_WORDS = (
    "生料带", "拉爆螺栓", "膨胀螺栓", "漏保", "空开", "云石机", "百叶轮",
    "吊装带", "手拉葫芦", "电动葫芦", "线坠", "抱箍", "码钉", "胀塞", "涨塞",
    "电滚子", "自攻钉", "自攻丝", "干壁钉", "拉铆钉", "倒链", "地牛", "由令",
    "补芯", "丝堵", "热熔器", "角磨机", "云石片", "切割片", "磨光机", "电锤",
    "冲击钻", "麻花钻", "开孔器", "内六角", "梅花扳手", "呆扳手", "管子钳",
    "试压", "千斤顶", "卸扣", "铝塑管", "存水弯", "水封", "生胶带", "王八盒",
    "护套线", "花线", "平方线", "塑铜线", "橡皮线", "氩弧焊", "二保焊",
    "老虎钳", "改锥", "丝攻", "皮老虎", "洋钉", "水泥钉", "蚂蟥钉", "灯口",
    "闸刀", "摇表", "拉钉枪", "砂轮片", "胶枪", "氧气管", "拐尺", "铅丝",
    "盒尺", "起子",
)


def _get_jieba():
    """jieba 懒加载（首次调用才建词典），失败降级为整串中文不走切词。"""
    global _jieba, _JIEBA_TRIED
    if not _JIEBA_TRIED:
        _JIEBA_TRIED = True
        try:
            import jieba  # 运行时已依赖（search 同款）
            for _w in _KB_HARDWARE_WORDS:
                jieba.add_word(_w, freq=200)
            _jieba = jieba
        except Exception:
            _jieba = None
    return _jieba


def tokenize_kb_query(query: str) -> list:
    """查询切词：字母数字串整取（3.2/380V/10*100），中文串 jieba 切词。

    混合串子词：≥4 位的字母数字串额外提取"字母+数字"子词（3pc25→c25），
    型号口语（3pc25 空开）能各自命中 C25 词条。
    过滤：停用虚词；单 CJK 字保留参与打分（"地漏返臭"的"臭"字是
    关键判别信号），但 build_fts_match/build_like_and 各自再滤单字，
    避免 LIKE 单字全库扫。
    """
    q = str(query or "").strip()
    if not q:
        return []
    out: list = []
    seen_all = set()

    def _push(w):
        w = w.strip()
        if not w or w in _KB_STOPWORDS or w in seen_all:
            return
        if len(w) < 2:
            return
        seen_all.add(w)
        out.append(w)

    for run in _KB_TOKEN_RE.findall(q):
        if _KB_CJK_RE.fullmatch(run):
            j = _get_jieba()
            words = j.lcut(run) if j else [run]
            for w in words:
                _push(w)
        else:
            _push(run)
            if len(run) >= 4:
                # 型号口语子词：两遍 findall 取并集（一遍的 alternation 会被
                # 非重叠消费吃掉 c25 这类短子词）——3pc25→pc25/c25/p25
                subs = set(re.findall(r"[A-Za-z]+\d+", run)) | set(
                    re.findall(r"[A-Za-z]\d{1,3}", run))
                for sub in subs:
                    if sub != run:
                        _push(sub)
    return out


def build_fts_match(tokens: list, prefix: bool = True) -> str:
    """token → FTS5 OR 串（加引号屏蔽语法字符）。

    prefix=True 加 * 做前缀匹配：查询"生料"能命中词条 keyword 里的
    token"生料带"（unicode61 中文连串切得碎，精确短语常差一口气）；
    前缀会放大候选集，调用方先跑精确、不够再跑前缀。单字 token
    不进 FTS（命中面太大淹池），只留给打分层。"""
    parts = []
    for t in tokens:
        if len(t) < 2:
            continue
        t2 = _KB_FTS_SAFE_RE.sub("", t)
        if t2:
            parts.append('"%s"*' % t2 if prefix else '"%s"' % t2)
    return " OR ".join(parts)


def build_like_and(tokens: list) -> tuple:
    """token → 分词 AND 的 LIKE 子句（每词问句/关键词/答案三列），参数绑定。

    多词都含才命中，容错"3.2焊条"vs"3.2 焊条 直径"的空格/标点差异；
    单字 token 不进 LIKE（全库扫噪声大）；全为单字返回恒假子句。
    """
    strong = [t for t in tokens if len(t) >= 2]
    if not strong:
        return "1=0", []
    conds, params = [], []
    for t in strong:
        conds.append("(question LIKE ? OR keyword LIKE ? OR answer LIKE ?)")
        params += [f"%{t}%", f"%{t}%", f"%{t}%"]
    return " AND ".join(conds), params


def score_kb_row(row, tokens: list, query: str = "") -> float:
    """词条打分（2026-09-18）：FTS bm25 会把短答案的手敲卡压过正主词条
    （"380V是什么电"被接触器参数卡盖住）。改为按 token 命中数计分，且命中
    题面/关键词权重 2.5 倍于命中答案——顾客的词出现在问句里才是正主；
    问句精确包含整个查询核心串再 +3（问法几乎照抄词条 = 最强信号）。"""
    q = (row["question"] or "").lower()
    kw = (row["keyword"] or "").lower()
    a = (row["answer"] or "").lower()
    s = 0.0
    for t in tokens:
        tl = t.lower()
        if tl in q or tl in kw:
            s += 2.5
        elif tl in a:
            s += 1.0
    if query:
        core = re.sub(r"[，。？！?!.,'\"（）()\\s]+", "", query).lower()
        if len(core) >= 4 and core in q:
            s += 3.0
    # 商品解读卡降权（2026-09-19）：1.2 万张单品卡会让泛问句池子被淹没；
    # 知识词条（型号/工艺）优先，单品卡靠全名精确命中取胜（-1 分仍可反超弱命中）
    # 多角度模板卡（product-angle，2026-09-20）同性质：泛答案让位专项知识。
    try:
        src = row["source"] or ""
    except (KeyError, IndexError):
        src = ""
    if src in ("product-guide", "product-angle"):
        s -= 1.0
    else:
        # 并列定序（2026-09-21）：词法命中数相同的卡必须有确定次序，否则大库增删后
        # rowid 一洗牌，正主就被同分泛卡挤出首屏（实测「燃气管多久换一次」掉出前 2）。
        # 优先级与「一题一条」保留序同源：人工语料 > 手敲/结构化 > LLM 生成 > 商品模板卡。
        # 幅度刻意小于单 token 权重 2.5——只破同分，不让弱命中的精选卡压过强命中的卡。
        s += 0.5 if src in ("expert-faq", "expert-spec") else \
            0.25 if (src == "manual" or src.startswith("structured")) else 0.0
    return s


def _inc_req():
    global _request_count
    with _metrics_lock:
        _request_count += 1


def _inc_err():
    global _error_count
    with _metrics_lock:
        _error_count += 1


def _ok(data, **extra):
    _inc_req()
    return ok_response(data, **extra)


def _err(msg, code="UNKNOWN", user_message="系统异常，请稍后再试"):
    _inc_err()
    return err_response(msg, code, user_message)


def get_db() -> sqlite3.Connection:
    if not hasattr(_local, "conn") or _local.conn is None:
        conn = sqlite3.connect(DB_PATH, check_same_thread=False)
        conn.row_factory = sqlite3.Row
        conn.execute("PRAGMA journal_mode=WAL")
        conn.execute("PRAGMA foreign_keys=ON")
        conn.execute("PRAGMA cache_size=-8000")
        conn.execute("PRAGMA mmap_size=268435456")
        conn.execute("PRAGMA temp_store=MEMORY")
        conn.execute("PRAGMA synchronous=NORMAL")
        conn.execute("PRAGMA busy_timeout=5000")
        # I7：knowledge_base 补 source/review_status/risk_level/sku（幂等，新连接一次）
        try:
            ensure_kb_columns(conn)
        except Exception:
            pass
        _local.conn = conn
    return _local.conn


# ─── Tool Functions ─────────────────────────────────────────────────

def search_knowledge(query: str, limit: int = 5) -> dict:
    """Search hardware knowledge base（2026-09-18 三层检索）。

    ① FTS5 分词 OR 短语（rank 排序，接住词序不同的问法）；
    ② 整句 LIKE（最精确包含，原文短语型问法）；
    ③ 分词 AND LIKE（多词都含，容错空格/标点差异，如"3.2焊条"↔"3.2 焊条"）。
    FTS 表无 review_status，主表按 rowid 过滤：pending 永不暴露。
    """
    try:
        db = get_db()
        approved = "(review_status IS NULL OR review_status='approved')"
        results = []

        def _append(rows):
            seen = {r["question"] for r in results}
            for r in rows:
                if r["question"] not in seen and len(results) < limit:
                    results.append({"question": r["question"], "answer": r["answer"],
                                    "category": r["category"], "keyword": r["keyword"]})
                    seen.add(r["question"])

        tokens = tokenize_kb_query(query)

        # ① FTS：分词 OR + 题面加权重打分（不信任 bm25 rank）。
        # 两段池合并（精确 + 前缀）：只跑精确会漏掉"问句整串 token"型正主
        # （方柄四坑钻头配什么锤 只被前缀段捞出）；池深至少 400——1.2 万张
        # 商品卡入池后，常用词 bm25 会把正主压到深水区，池浅了打分器看不到
        pool: dict = {}
        for use_prefix in (False, True):
            match_q = build_fts_match(tokens, prefix=use_prefix)
            if not match_q:
                continue
            try:
                fts_results = db.execute(
                    "SELECT f.rowid AS rowid, f.question AS question, f.answer AS answer, "
                    "f.category AS category, f.keyword AS keyword, k.source AS source "
                    "FROM knowledge_base_fts f JOIN knowledge_base k ON k.rowid = f.rowid "
                    "WHERE knowledge_base_fts MATCH ? ORDER BY rank LIMIT ?",
                    (match_q, max(400, limit * 6))
                ).fetchall()
            except Exception:
                continue
            ok_ids = {
                r[0] for r in db.execute(
                    "SELECT rowid FROM knowledge_base WHERE rowid IN (%s) AND (review_status IS NULL OR review_status='approved')" % ",".join("?" * len(fts_results)),
                    [r[0] for r in fts_results],
                ).fetchall()
            }
            for r in fts_results:
                if r[0] in ok_ids and r[0] not in pool:
                    pool[r[0]] = r

        # FTS 池补充（2026-09-21）：FTS 按"查询词整词"命中巨 CJK token，
        # 库内正主若是不同切分（"燃气软管…"接不住"燃气管"*）就整条不在
        # 候选里——逐词 LIKE 并入同一打分池，词级信号兜住词法差异
        if len(tokens) >= 2:
            for t in tokens[:6]:
                if len(t) < 2:
                    continue
                pat = f"%{t}%"
                try:
                    for r in db.execute(
                        "SELECT rowid, question, answer, category, keyword, source "
                        "FROM knowledge_base WHERE (question LIKE ? OR keyword LIKE ? "
                        "OR answer LIKE ?) AND " + approved + " "
                        "ORDER BY (question LIKE ?) DESC, rowid LIMIT 50",
                        (pat, pat, pat, pat)).fetchall():
                        if r[0] not in pool:
                            pool[r[0]] = r
                except Exception:
                    pass

        # 合并池统一打分排序后输出
        if pool:
            scored = sorted(
                pool.values(),
                key=lambda r: (score_kb_row(r, tokens, query),
                               -len(r["question"] or "")),
                reverse=True,
            )
            _append(list(scored))

        # ② 整句 LIKE（原始问法整串包含）
        if len(results) < limit:
            like_q = f"%{query}%"
            _append(db.execute(
                "SELECT question, answer, category, keyword, source FROM knowledge_base "
                "WHERE (question LIKE ? OR answer LIKE ? OR keyword LIKE ?) AND "
                + approved + " LIMIT ?",
                (like_q, like_q, like_q, limit)).fetchall())

        # ③ 分词 AND LIKE（≥2 词才有意义；容错空格/标点差异）
        if len(tokens) >= 2 and len(results) < limit:
            clause, params = build_like_and(tokens)
            _append(db.execute(
                "SELECT question, answer, category, keyword, source FROM knowledge_base "
                "WHERE (" + clause + ") AND " + approved + " LIMIT ?",
                params + [limit]).fetchall())

        # ④ 逐词 OR + 题面打分兜底：多词问法里 AND 档全灭时，按单词各自
        #    LIKE 取候选、合并打分，保证"总有知识条"而不是空手（2026-09-18）
        if len(tokens) >= 2 and len(results) < limit:
            pool: dict = {}
            for t in tokens[:6]:
                pat = f"%{t}%"
                try:
                    for r in db.execute(
                        "SELECT question, answer, category, keyword, source FROM knowledge_base "
                        "WHERE (question LIKE ? OR keyword LIKE ? OR answer LIKE ?) AND "
                        + approved + " LIMIT 10",
                        (pat, pat, pat)).fetchall():
                        pool.setdefault(r["question"], r)
                except Exception:
                    pass
            if pool:
                ranked = sorted(pool.values(),
                                key=lambda r: score_kb_row(r, tokens, query),
                                reverse=True)
                _append(ranked)

        return _ok(results, total=len(results))
    except Exception as e:
        return _err(str(e))


def match_scenario(query: str) -> dict:
    """Identify which hardware scenario the user is describing."""
    try:
        db = get_db()
        results = []
        rows = db.execute(
            "SELECT id, name, keywords_json, products_json, category, priority, hint FROM intent_scenarios"
        ).fetchall()
        for r in rows:
            keywords = json.loads(r["keywords_json"] or "[]")
            name = r["name"] or ""
            score = 0
            for kw in keywords:
                if kw in query:
                    score += 10
            if name and name.replace(" ", "") in query.replace(" ", ""):
                score += 5
            if score > 0:
                results.append({
                    "id": r["id"],
                    "name": name,
                    "category": r["category"],
                    "priority": r["priority"],
                    "hint": r["hint"],
                    "products": json.loads(r["products_json"] or "[]"),
                    "matchScore": score,
                })
        results.sort(key=lambda x: -x["matchScore"])
        return _ok(results[:5], total=len(results))
    except Exception as e:
        return _err(str(e))


def health_check() -> dict:
    try:
        db = get_db()
        cnt = db.execute("SELECT COUNT(*) FROM products").fetchone()[0]
        kb_cnt = db.execute("SELECT COUNT(*) FROM knowledge_base").fetchone()[0]
        up = int(time.time() - START_TIME)
        h, m, s = up // 3600, (up % 3600) // 60, up % 60
        return _ok({
            "status": "healthy",
            "uptime": f"{h}h {m}m {s}s",
            "products": cnt,
            "knowledge_entries": kb_cnt,
            "requests_total": _request_count,
            "errors_total": _error_count,
        })
    except Exception as e:
        return _err(str(e))


def add_knowledge(data: dict) -> dict:
    """Add a knowledge entry. Accepts single entry or batch."""
    try:
        db = get_db()
        db.execute("PRAGMA busy_timeout=10000")
        entries = data if isinstance(data, list) else [data]
        added = 0
        for e in entries:
            eid = e.get("id") or f"kb-{int(time.time()*1000)}-{hash(e.get('question',''))%10000}"
            question = e.get("question", "").strip()
            answer = e.get("answer", "").strip()
            if not question or not answer:
                continue
            category = e.get("category", "")
            keyword = e.get("keyword", "")
            related = json.dumps(e.get("products", e.get("related_products", [])), ensure_ascii=False)
            # I7：四列随条目走（缺省 manual/approved//''，老调用零影响）
            cur = db.execute(
                "INSERT OR IGNORE INTO knowledge_base(id,category,keyword,question,answer,related_products_json,source,review_status,risk_level,sku) VALUES(?,?,?,?,?,?,?,?,?,?)",
                (eid, category, keyword, question, answer, related,
                 e.get("source", "manual"), e.get("review_status", "approved"),
                 e.get("risk_level", ""), e.get("sku", ""))
            )
            if cur.rowcount > 0:
                added += 1
        db.commit()
        # FTS rebuild 必须紧跟 commit：rebuild 是写操作，不提交会把 wujin.db
        # 的写锁攥在本线程连接手里不放，全库其他写者全部 BUSY（2026-09-19 实测）
        try:
            db.execute("INSERT INTO knowledge_base_fts(knowledge_base_fts) VALUES('rebuild')")
            db.commit()
        except sqlite3.Error:
            db.rollback()
        return _ok({"added": added}, total=added, message=f"成功导入 {added} 条知识")
    except Exception as e:
        return _err(str(e))


# ─── HTTP Handler ────────────────────────────────────────────────────

class KnowledgeHandler(BaseHTTPRequestHandler):
    def log_message(self, format, *args):
        pass

    def _send_json(self, data: dict, status: int = 200, request_id: str = ""):
        if request_id and isinstance(data, dict):
            data["_request_id"] = request_id
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Access-Control-Allow-Origin", "*")
        self.end_headers()
        self.wfile.write(json.dumps(data, ensure_ascii=False).encode("utf-8"))

    def do_GET(self):
        self.request_id = self.headers.get("X-Request-Id", "")
        set_request_id(self.request_id)
        parsed = urlparse(self.path)
        qs = parse_qs(parsed.query)
        path = parsed.path.rstrip("/")

        if path == "/health":
            return self._send_json(health_check(), request_id=self.request_id)

        if path == "/search":
            query = qs.get("query", [""])[0] or qs.get("q", [""])[0]
            try:
                limit = int(qs.get("limit", ["5"])[0])
            except (TypeError, ValueError):
                limit = 5
            limit = max(1, min(limit, 50))  # 2026-09-11 bug猎杀：LIMIT -1 在 sqlite 是全库（16k 条）
            if not query:
                return self._send_json({"success": False, "data": None, "error": "缺少 query 参数", "code": "MISSING_FIELD", "user_message": "请提供搜索关键词", "hint": "请提供搜索关键词"}, 400, request_id=self.request_id)
            result = search_knowledge(query, limit)
            return self._send_json(result, request_id=self.request_id)

        if path == "/scenario":
            query = qs.get("query", [""])[0]
            if not query:
                return self._send_json({"success": False, "data": None, "error": "缺少 query 参数", "code": "MISSING_FIELD", "user_message": "请提供场景描述", "hint": "请提供场景描述"}, 400, request_id=self.request_id)
            result = match_scenario(query)
            return self._send_json(result, request_id=self.request_id)

        self._send_json({"success": False, "data": None, "error": f"未知路径: {path}", "code": "NOT_FOUND", "user_message": "请求路径不存在", "hint": "请求路径不存在"}, 404, request_id=self.request_id)

    def do_POST(self):
        self.request_id = self.headers.get("X-Request-Id", "")
        set_request_id(self.request_id)
        content_length = int(self.headers.get("Content-Length", 0))
        body = self.rfile.read(content_length) if content_length else b"{}"
        parsed = urlparse(self.path)
        path = parsed.path.rstrip("/")

        if path == "/search":
            data, err = validate_request(body, {
                "query": {"required": True, "message": "请输入搜索内容"},
                "limit": {"type": int, "default": 5},
            })
            if err:
                return self._send_json(err, 400, request_id=self.request_id)
            result = search_knowledge(data["query"], data["limit"])
            return self._send_json(result, request_id=self.request_id)

        if path == "/scenario":
            data, err = validate_request(body, {
                "query": {"required": True, "message": "请输入场景描述"},
            })
            if err:
                return self._send_json(err, 400, request_id=self.request_id)
            result = match_scenario(data["query"])
            return self._send_json(result, request_id=self.request_id)

        if path == "/add":
            data, err = parse_json_body(body)
            if err:
                return self._send_json(err, 400, request_id=self.request_id)
            result = add_knowledge(data)
            return self._send_json(result, request_id=self.request_id)

        self._send_json({"success": False, "data": None, "error": f"未知路径: {path}", "code": "NOT_FOUND", "user_message": "请求路径不存在", "hint": "请求路径不存在"}, 404, request_id=self.request_id)

    def do_OPTIONS(self):
        self.request_id = self.headers.get("X-Request-Id", "")
        set_request_id(self.request_id)
        self._send_json({}, request_id=self.request_id)


class ThreadedKnowledgeHTTPServer(ThreadingMixIn, HTTPServer):
    allow_reuse_address = True
    daemon_threads = True


def _run_server(host: str = None, port: int = 18602):
    # 2026-09-09 审计：下游服务默认只绑回环（仅网关本机调用），0.0.0.0 需显式环境变量。
    host = host or os.environ.get("KNOWLEDGE_HOST", "127.0.0.1")
    setup_logging("knowledge")
    server = ThreadedKnowledgeHTTPServer((host, port), KnowledgeHandler)
    logging.info(f"Server listening on {host}:{port}")
    def _sigterm(signum, frame):
        logging.info("SIGTERM received, shutting down...")
        server.shutdown()
    signal.signal(signal.SIGTERM, _sigterm)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        logging.info("Shutting down...")
        server.server_close()


if __name__ == "__main__":
    _run_server()
