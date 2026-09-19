package com.durable.demo;

import com.durable.db.Db;
import com.durable.effect.EffectLedger;
import com.durable.effect.EffectType;
import com.durable.engine.DurableExecutor;
import com.durable.engine.Step;
import com.durable.fault.CrashInjector;
import com.durable.fault.CrashPoint;
import com.durable.journal.mysql.MySqlJournalStore;
import com.durable.shell.HostTools;

import java.util.List;

/**
 * 供「真实进程强杀」测试使用的子进程入口。
 *
 * 它会：创建一个主机（副作用提交）→ 在提交后、写日志前**挂起**，
 * 把时间窗留给父进程执行 {@code destroyForcibly()}。
 *
 * 这不是测试里的模拟崩溃，而是真的让操作系统杀掉这个 JVM ——
 * 用来验证「真实强杀时未提交事务回滚、已提交事务保留」这类
 * 应用内注入验证不了的行为。
 *
 * 用法：{@code java -cp <classpath> com.durable.demo.CrashableRunner <workflowId> <pauseMillis>}
 */
public final class CrashableRunner {

    private CrashableRunner() {
    }

    public static void main(String[] args) {
        // 显式指定 UTF-8：本机是 Java 17，-Dstdout.encoding 是 18+ 才支持的参数，
        // 而 Windows 下 System.out 默认用系统代码页，中文会乱码。
        java.io.PrintStream out = new java.io.PrintStream(
                new java.io.FileOutputStream(java.io.FileDescriptor.out), true,
                java.nio.charset.StandardCharsets.UTF_8);

        if (args.length < 2) {
            out.println("用法: CrashableRunner <workflowId> <pauseMillis>");
            System.exit(2);
        }
        String workflowId = args[0];
        long pauseMillis = Long.parseLong(args[1]);

        try {
            MySqlJournalStore journal = new MySqlJournalStore(Db.dataSource());
            EffectLedger ledger = new EffectLedger(Db.dataSource());
            HostTools hosts = new HostTools(Db.dataSource());

            List<Step> steps = List.of(
                    new Step("create-host", "createHost", EffectType.NON_IDEMPOTENT,
                            hosts.createHost("host-kill", "web-kill", "child-process")),
                    new Step("list-hosts", "listHosts", EffectType.NONE, hosts.listHosts()));

            CrashInjector injector = new CrashInjector();
            // 关键：暂停点选在「副作用已提交、日志未写」—— 也就是计划 1 复现出来的那个窗口
            injector.armPause(CrashPoint.AFTER_EXECUTE_BEFORE_JOURNAL, 0, pauseMillis);

            out.println("[child] 开始执行，将在提交副作用后挂起 " + pauseMillis + "ms");
            out.flush();

            new DurableExecutor(journal, ledger, injector).run(workflowId, steps);

            out.println("[child] 执行完成（说明父进程没来得及强杀）");
        } catch (Exception e) {
            System.err.println("[child] 异常: " + e);
            System.exit(1);
        } finally {
            Db.shutdown();
        }
    }
}
