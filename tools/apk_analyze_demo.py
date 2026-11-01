#!/usr/bin/env python3
"""自动分析可行性 demo：从 APK 的 Manifest 里挖「疑似广告组件」。

只回答一个问题：静态自动分析能不能用 —— 能挖出多少、准不准、误伤多大。
"""
import re
import sys

AD_WORDS = {
    'Ad', 'Ads', 'Advert', 'Advertise', 'Advertisement', 'Banner',
    'Reward', 'RewardVideo', 'Interstitial', 'NativeAd', 'FeedAd',
    'Promote', 'Promotion', 'Splash',
}
# 含 ad 字母但跟广告无关的高频词（不排除会把 Adapter/Header/Load 全判成广告）
NOT_AD = {
    'Adapter', 'Address', 'Add', 'Added', 'Loading', 'Loader', 'Reader',
    'Header', 'Head', 'Read', 'Update', 'Upgrade', 'Advance', 'Advanced',
    'Load', 'Callback', 'Broadcast', 'Thread', 'Async', 'Shadow', 'Trade',
}
# 注意：必须用「广告 SDK 的确切路径」，不能用大厂前缀。
# 第一版用 'bytedance' 当特征，结果懂车帝自己 200 多个聊天页面全被判成广告 —— 字节 App
# 的业务代码就在 com.bytedance 下面。这条坑记下来。
SDK_HINT = (
    '/openadsdk/', 'pangle', 'com.qq.e.', 'com/qq/e/', 'com.kwad',
    'com.baidu.mobads', 'com.mbridge', 'com.beizi.ad', 'com.anythink',
    'com.sigmob', 'com.unity3d.ads', 'com.applovin', 'com.google.android.gms.ads',
    'com.inmobi', 'com.vungle', 'com.tanx', 'gromore', 'com.kuaishou',
)
# SDK 的基础设施（文件提供者、初始化服务）不是广告展示页，拦它们等于断 SDK，
# 属于风险级；和「广告页面」必须分开，不能混在一张候选表里。
INFRA_KIND = ('provider', 'service')


def parse_manifest(path):
    pkg, comps, cur = None, [], None
    for line in open(path, encoding='utf-8', errors='ignore'):
        s = line.strip()
        if s.startswith('A: package='):
            m = re.search(r'package="([^"]+)"', s)
            if m:
                pkg = m.group(1)
            continue
        m = re.match(r'E: (\w+)', s)
        if m:
            tag = m.group(1)
            cur = tag if tag in ('activity', 'service', 'receiver', 'provider') else None
            continue
        if cur:
            m = re.search(r'android:name\(0x[0-9a-f]+\)="([^"]+)"', s)
            if m:
                n = m.group(1)
                if n.startswith('.') and pkg:
                    n = pkg + n
                comps.append((cur, n))
                cur = None
    return pkg, comps


def split_camel(simple):
    return re.findall(r'[A-Z]?[a-z0-9]+|[A-Z]+(?![a-z])', simple)


def judge(full):
    """返回 (档位, 理由)。1=强信号，2=弱信号，0=不是。"""
    low = full.lower()
    for hint in SDK_HINT:
        if hint in low:
            return 1, 'SDK路径:%s' % hint
    simple = full.rsplit('.', 1)[-1]
    words = set(split_camel(simple))
    hits = words & AD_WORDS
    if not hits:
        return 0, ''
    noise = words & NOT_AD
    if noise and not (hits - {'Ad', 'Ads'}):
        return 0, ''
    # Splash 单独出现不算（很多 App 的启动页不叫广告），和 Ad 一起才算
    if hits == {'Splash'}:
        return 2, '含Splash'
    strong = hits - {'Splash', 'Promote', 'Promotion'}
    return (1 if strong else 2), ','.join(sorted(hits))


def main():
    for path in sys.argv[1:]:
        pkg, comps = parse_manifest(path)
        print('=== %s ===' % path)
        print('包名: %s    组件总数: %d' % (pkg, len(comps)))
        strong, weak = [], []
        for kind, name in comps:
            lvl, why = judge(name)
            if lvl == 1:
                strong.append((kind, name, why))
            elif lvl == 2:
                weak.append((kind, name, why))
        print('强信号: %d    弱信号: %d    占比: %.1f%%' % (
            len(strong), len(weak), 100.0 * (len(strong) + len(weak)) / max(1, len(comps))))
        print('-- 强信号样例（最多 25 条）--')
        for kind, name, why in strong[:25]:
            print('  [%-8s] %-70s (%s)' % (kind, name.replace(pkg + '.', './'), why))
        if weak:
            print('-- 弱信号样例（最多 10 条）--')
            for kind, name, why in weak[:10]:
                print('  [%-8s] %-70s (%s)' % (kind, name.replace(pkg + '.', './'), why))
        print()


if __name__ == '__main__':
    main()
