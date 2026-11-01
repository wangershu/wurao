#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
把大圣（dy_ys88）那套旧规则转成「无扰」能导入的规则文本。

边界（重要）：**只在电脑上跑，不进 App**。App 里不引入任何旧格式解析代码 ——
我们只要它的结论（哪些域名、哪些组件、哪些弹窗文案），不要它的格式。

用法：
    python3 tools/import_dasheng.py dy_ys88.zip -o dasheng_rules.txt
    python3 tools/import_dasheng.py <解压目录> -o dasheng_rules.txt

输出格式（内部规则文本，设置页粘贴即可导入）：
    包名<TAB>动作<TAB>目标<TAB>备注<TAB>是否启用<TAB>域<TAB>来源
"""
import argparse
import json
import os
import re
import sys
import tempfile
import zipfile

# 可映射的动作（GKD/无扰的 key）
ACT_CLICK = "click"
ACT_BLOCK = "block"

STAT = {}


def bump(key, n=1):
    STAT[key] = STAT.get(key, 0) + n


def note(key, reason):
    bump("跳过:" + key + "(" + reason + ")")


def load_json_array(path):
    """旧文件是「一个 JSON 数组，但每个元素单独一行、行尾带逗号」。

    按行解析比逐字符拼括号稳得多 —— 之前用字符级拼接，92 个超长行全解析失败，
    结果组件规则只转出 9 条（实际有几百条），这种静默丢失最要命。
    """
    try:
        raw = open(path, encoding="utf-8", errors="ignore").read()
    except OSError:
        return []
    out = []
    for line in raw.split("\n"):
        s = line.strip()
        if not s or s in ("[", "]", ""):
            continue
        s = s.rstrip(",")
        if not (s.startswith("{") and s.endswith("}")):
            continue
        try:
            out.append(json.loads(s))
        except Exception:
            note("坏对象", "单行 JSON 解析失败")
    return out


def host_of(url_or_host):
    """从 URL 或 host 里抠出域名：只认 http(s):// 或 纯域名 两种。"""
    s = (url_or_host or "").strip()
    if not s:
        return None
    s = re.sub(r"^\w+://", "", s)
    # hosts 写法：0.0.0.0 domain / 127.0.0.1 domain
    m = re.match(r"^(\d+\.\d+\.\d+\.\d+)\s+(\S+)$", s)
    if m:
        s = m.group(2)
    s = s.split("/")[0].split("?")[0].strip()
    # 去端口
    s = s.split(":")[0]
    if not re.match(r"^[A-Za-z0-9.\-]+\.[A-Za-z]{2,}$", s):
        return None
    return s.lower()


def emit_domain(rules, host, remark, pkg="*"):
    if not host:
        note("域名", "抠不出域名")
        return
    rules.append((pkg, ACT_BLOCK, host, remark or "广告域名", "true", "HOST", "s:0"))
    bump("域名规则")


def emit_component(rules, pkg, comp, remark):
    if not pkg or not comp:
        note("组件", "缺 pkg 或组件名")
        return
    rules.append((pkg, ACT_BLOCK, comp, remark or "广告组件", "true", "COMPONENT", "s:0"))
    bump("组件规则")


def emit_click_text(rules, pkg, text, remark, act_hint=None):
    """弹窗点击：旧规则里的 clkTxt 是界面文案，转成按文本定位的点击规则。"""
    if not pkg or not text:
        note("弹窗点击", "缺 pkg 或文案")
        return
    text = text.strip()
    if len(text) > 12:
        note("弹窗点击", "文案太长，容易误点")
        return
    sel = '[text="%s"]' % text
    if act_hint:
        sel = '[h="%s"] << %s' % (act_hint, sel)
    rules.append((pkg, ACT_CLICK, sel, remark or "弹窗", "true", "NODE", "s:0"))
    bump("弹窗点击规则")


def convert(src_dir):
    rules = []

    # ---------- 1) 沙僧 hosts：纯域名列表 ----------
    p = os.path.join(src_dir, "sha_seng_hosts.txt")
    if os.path.exists(p):
        for line in open(p, encoding="utf-8", errors="ignore"):
            line = line.strip()
            if not line or line.startswith("#") or line.startswith("!"):
                continue
            emit_domain(rules, host_of(line), "沙僧 hosts")
    else:
        note("文件", "缺 sha_seng_hosts.txt")

    # ---------- 2) 悟空拦截组件 ----------
    p = os.path.join(src_dir, "wu_kong_block_component.txt")
    if os.path.exists(p):
        for app in load_json_array(p):
            if not app.get("isEnable", True):
                continue
            pkg = app.get("pkg")
            for r in app.get("rules", []) or []:
                if not r.get("isEnable", True):
                    continue
                emit_component(rules, pkg, r.get("ad"), r.get("remark") or r.get("localRemark"))
    else:
        note("文件", "缺 wu_kong_block_component.txt")

    # ---------- 3) 悟空拦截 URL：抠域名 ----------
    p = os.path.join(src_dir, "wu_kong_block_url.txt")
    if os.path.exists(p):
        for app in load_json_array(p):
            if not app.get("isEnable", True):
                continue
            pkg = app.get("pkg")
            for r in app.get("rules", []) or []:
                if not r.get("isEnable", True):
                    continue
                emit_domain(rules, host_of(r.get("ad") or r.get("adKey")), r.get("remark") or r.get("localRemark"), pkg)

    # ---------- 4) 悟空规则：开屏 Activity 与弹窗文案点击 ----------
    p = os.path.join(src_dir, "wu_kong_rules.txt")
    if os.path.exists(p):
        for app in load_json_array(p):
            if not app.get("isEnable", True):
                continue
            pkg = app.get("pkg")
            act_hint = app.get("h")
            for r in app.get("rules", []) or []:
                if not r.get("isEnable", True):
                    continue
                at = r.get("actionType")
                ad = r.get("ad") or ""
                ad_key = r.get("adKey") or ""
                remark = r.get("remark") or r.get("localRemark") or ""
                # adKey 里嵌着一段 JSON（含 clkTxt / clkId），试着取文案
                texts = []
                if ad_key.strip().startswith("["):
                    try:
                        for step in json.loads(ad_key):
                            t = (step.get("clkTxt") or "").strip()
                            if t:
                                texts.append(t)
                    except Exception:
                        note("adKey", "内嵌 JSON 解析失败")
                if texts:
                    for t in texts[:2]:
                        emit_click_text(rules, pkg, t, remark or "弹窗", act_hint)
                elif at == 3 or (ad and ".Splash" in ad):
                    # 3 = 拦截开屏 Activity
                    emit_component(rules, pkg, ad, remark or "开屏广告页")
                else:
                    note("悟空规则", "actionType=%s 无法映射" % at)

    # ---------- 明确不映射的部分 ----------
    for f, why in (
        ("ba_jie_tasks.txt", "多步骤自动化脚本，我们没有对应语义"),
        ("ba_jie_file_replace.txt", "文件替换，不做"),
        ("wu_kong_replaces.txt", "内容替换，不做"),
        ("wu_kong_block_view.txt", "按 View 拦截，语义不同"),
        ("ba_jie_auto_skip.txt", "包名白名单，需人工决定"),
        ("app_config.txt", "应用配置，非规则"),
    ):
        if os.path.exists(os.path.join(src_dir, f)):
            note("不映射", f + "：" + why)

    return rules


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("src", help="dy_ys88.zip 或解压后的目录")
    ap.add_argument("-o", "--out", default="dasheng_rules.txt")
    args = ap.parse_args()

    if os.path.isdir(args.src):
        src_dir = args.src
        tmp = None
    else:
        tmp = tempfile.mkdtemp()
        with zipfile.ZipFile(args.src) as z:
            z.extractall(tmp)
        src_dir = tmp

    rules = convert(src_dir)

    # 去重（同 pkg+target+act 只留一条）
    seen = set()
    uniq = []
    for r in rules:
        k = (r[0], r[1], r[2])
        if k in seen:
            continue
        seen.add(k)
        uniq.append(r)

    with open(args.out, "w", encoding="utf-8") as f:
        for r in uniq:
            f.write("\t".join(r) + "\n")

    print("输出: %s" % args.out)
    print("规则条数（去重后）: %d" % len(uniq))
    for k in sorted(STAT):
        print("  %s: %d" % (k, STAT[k]))
    return 0


if __name__ == "__main__":
    sys.exit(main())
