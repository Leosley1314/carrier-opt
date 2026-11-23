package com.aosp.carrier.root;

import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * 用 su 执行一段 shell。脚本先落到 App 缓存目录再执行，
 * 避免在 Java 里拼超长命令行导致引号地狱。
 */
public final class RootShell {

    /** KernelSU 系的 su 不一定在 PATH 里，逐个试 */
    private static final String[] SU_CANDIDATES = {
            "su",
            "/system/bin/su",
            "/system/xbin/su",
            "/debug_ramdisk/su",
            "/data/adb/ksu/bin/su",
            "/data/adb/magisk/su",
            "/sbin/su",
    };

    private RootShell() {
    }

    /**
     * @return 命令输出（stdout + stderr 合并）
     * @throws Exception su 不存在 / 超时
     */
    public static String exec(Context ctx, String script, int timeoutSec) throws Exception {
        File f = new File(ctx.getCacheDir(), "pe_l10n_exec.sh");
        try (FileOutputStream fos = new FileOutputStream(f)) {
            fos.write(script.getBytes(StandardCharsets.UTF_8));
            fos.flush();
        }

        Process p = null;
        Throwable last = null;
        for (String su : SU_CANDIDATES) {
            try {
                p = new ProcessBuilder(su, "-c", "sh " + f.getAbsolutePath())
                        .redirectErrorStream(true)
                        .start();
                break;
            } catch (Throwable e) {
                last = e;
            }
        }
        if (p == null) {
            throw new IllegalStateException(
                    "没找到 su。设备可能没 root，或者 root 管理器把本应用拦住了——"
                            + "到 Magisk / KernelSU 的「超级用户」里手动允许本应用，再点一次。", last);
        }

        final StringBuilder out = new StringBuilder();
        final Process proc = p;
        Thread reader = new Thread(() -> {
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    out.append(line).append('\n');
                }
            } catch (Throwable ignored) {
            }
        });
        reader.setDaemon(true);
        reader.start();

        boolean finished = p.waitFor(timeoutSec, TimeUnit.SECONDS);
        if (!finished) {
            p.destroyForcibly();
        }
        reader.join(2000);
        String text = out.toString().trim();
        if (!finished) {
            return text + "\n[超过 " + timeoutSec + " 秒还没结束]";
        }
        int code;
        try {
            code = p.exitValue();
        } catch (Throwable t) {
            code = -1;
        }
        if (code != 0 && text.isEmpty()) {
            throw new IllegalStateException("root 没执行成功（退出码 " + code
                    + "）。刚才若没弹授权框，去 Magisk / KernelSU 的「超级用户」里把本应用打开，再试一次。");
        }
        if (text.contains("Permission denied") || text.contains("not allowed to su")) {
            throw new IllegalStateException("root 管理器拒绝了这次请求。"
                    + "在「超级用户」列表里允许 com.aosp.carrier，然后重试。");
        }
        return text;
    }
}
