package com.spazpeek;

import android.util.Base64;

/**
 * 反冻守护（FrostGuard）：独立进程，每秒巡检本应用的 cgroup.freeze，
 * 一旦被 Oplus Hans 冻结（=1）立即写 0 解冻。
 * ====================
 * - 不依赖 LSPosed / 不需要安装第二个 App；仅需 Shizuku（shell 或 root 模式）。
 * - 守护由 setsid 完全脱离 App 会话，App 被冻/被杀时它依然存活。
 * - 目标进程消失后守护自动退出（防残留）。
 * - 实测（一加 13T / ColorOS 16）：冻结后 ≤2 秒自动解冻，日志 THAW ok。
 */
public final class FrostGuard {

    private static final String SCRIPT = "/data/local/tmp/spz_guard.sh";

    private FrostGuard() {}

    private static final String SCRIPT_BODY =
            "#!/system/bin/sh\n" +
            "UID_T=$(stat -c %u /data/data/com.spazpeek 2>/dev/null)\n" +
            "F=/sys/fs/cgroup/apps/uid_${UID_T}/cgroup.freeze\n" +
            "LOG=/data/local/tmp/spz_guard.log\n" +
            "echo \"$(date '+%H:%M:%S') guard start uid=$UID_T\" >> $LOG\n" +
            "while true; do\n" +
            "  if ! pidof com.spazpeek >/dev/null 2>&1; then\n" +
            "    echo \"$(date '+%H:%M:%S') target gone, exit\" >> $LOG\n" +
            "    break\n" +
            "  fi\n" +
            "  if [ -f \"$F\" ]; then\n" +
            "    v=$(cat \"$F\" 2>/dev/null)\n" +
            "    if [ \"$v\" = \"1\" ]; then\n" +
            "      if echo 0 > \"$F\" 2>/dev/null; then\n" +
            "        echo \"$(date '+%H:%M:%S') THAW ok\" >> $LOG\n" +
            "      elif su -c \"echo 0 > $F\" 2>/dev/null; then\n" +
            "        echo \"$(date '+%H:%M:%S') THAW ok(su)\" >> $LOG\n" +
            "      else\n" +
            "        echo \"$(date '+%H:%M:%S') THAW fail\" >> $LOG\n" +
            "      fi\n" +
            "    fi\n" +
            "  fi\n" +
            "  sleep 0.3\n" +
            "done\n";

    /** 启动守护（后台线程执行，防 UI 卡顿）。 */
    public static void start() {
        new Thread(FrostGuard::startInner, "spz-guard").start();
    }

    private static void startInner() {
        try {
            String b64 = Base64.encodeToString(SCRIPT_BODY.getBytes(), Base64.NO_WRAP);
            // 1) 写脚本（base64 避免引号转义问题）+ 赋权
            ShizukuShell.sh("echo " + b64 + " | base64 -d > " + SCRIPT + " && chmod 755 " + SCRIPT);
            // 2) 防重复：先杀旧实例
            ShizukuShell.sh("pkill -f spz_guard.sh");
            // 3) setsid 完全脱离会话启动
            ShizukuShell.sh("setsid sh " + SCRIPT + " >/dev/null 2>&1 </dev/null &");
            // 4) 验证
            String pid = ShizukuShell.oneLine("pgrep -f spz_guard.sh");
            HTLog.log("KEEP", "guard start pid=" + pid);
        } catch (Throwable t) {
            HTLog.log("KEEP", "guard fail: " + t);
        }
    }

    /** 停止守护。 */
    public static void stop() {
        new Thread(() -> {
            try {
                String pid = ShizukuShell.oneLine("pgrep -f spz_guard.sh");
                ShizukuShell.sh("pkill -f spz_guard.sh");
                HTLog.log("KEEP", "guard stopped (was pid=" + pid + ")");
            } catch (Throwable ignored) {}
        }, "spz-guard-stop").start();
    }

    /** 查询守护状态（同步，勿在主线程调用）。 */
    public static String status() {
        try {
            String pid = ShizukuShell.oneLine("pgrep -f spz_guard.sh");
            if (pid == null || pid.trim().isEmpty()) return "未运行";
            return "运行中 ✓ (PID " + pid.trim() + ")";
        } catch (Throwable t) {
            return "未知";
        }
    }
}