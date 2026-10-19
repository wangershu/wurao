package com.adhammer.model;

/**
 * 规则动作类型（RuleInfo.at）枚举。
 *
 * <p>取值由原版 AdProcessor / RuleInfo 的分支反推得到，与博客《悟空使用说明》
 * 描述的四种去广告手段完全对应。</p>
 */
public final class ActionType {

    /** 0：无动作 / 仅登记。 */
    public static final int NONE = 0;

    /** 1：关闭活动页 —— finish 当前 Activity（d&gt;0 时延迟后 onBackPressed）。 */
    public static final int CLOSE = 1;

    /** 2：禁用活动页 —— setComponentEnabledSetting(DISABLED)，可能引起闪退。 */
    public static final int DISABLE = 2;

    /** 3：跳过到首页 —— 启动 home/to，再 finish 当前页，无转场动画。 */
    public static final int SKIP = 3;

    /** 4：改写跳转目标（与 7 同组）。 */
    public static final int RETARGET = 4;

    /** 5：广告文件替换（用同名空文件/夹替换，见 ReplaceInfo）。 */
    public static final int REPLACE_FILE = 5;

    /** 6：自定义 Hook 脚本（ak 为脚本，f 为解密密钥）。 */
    public static final int CUSTOM_HOOK = 6;

    /** 7：替换活动页为首页（移花接木，与 3 同类）。 */
    public static final int REPLACE = 7;

    /** 8：精准 View 匹配规则（ak 为 ViewHookInfo JSON 数组）。 */
    public static final int VIEW = 8;

    /** 9：防火墙拦截活动页 —— 清空 Intent，效果最好，不闪退。 */
    public static final int BLOCK = 9;

    /** 100：保留标记位。 */
    public static final int FLAG = 100;

    private ActionType() {
    }

    /** 人类可读描述，便于规则编辑 UI / 日志输出。 */
    public static String describe(int at) {
        switch (at) {
            case NONE:
                return "无动作";
            case CLOSE:
                return "关闭活动页";
            case DISABLE:
                return "禁用活动页";
            case SKIP:
                return "跳过到首页";
            case RETARGET:
                return "改写跳转目标";
            case REPLACE_FILE:
                return "广告文件替换";
            case CUSTOM_HOOK:
                return "自定义Hook";
            case REPLACE:
                return "替换为首页(移花接木)";
            case VIEW:
                return "精准View规则";
            case BLOCK:
                return "防火墙拦截";
            default:
                return at >= 500 && at <= 503 ? "拦截(扩展" + at + ")" : "未知(" + at + ")";
        }
    }
}
