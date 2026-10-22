#ifndef GHOSTLOCK_RUNTIME_CONFIG_H
#define GHOSTLOCK_RUNTIME_CONFIG_H

#include "profile/model.h"


#include <string>

namespace ghostlock::config {
    /* One runtime snapshot captured once from the environment. Paths own their
 * storage; callers convert to c_str() only at syscall/exec boundaries so no
 * temporary string pointer can outlive the snapshot. */
    class RuntimeConfig final {
    public:
        int32_t main_cpu = 0;
        int32_t consumer_cpu = 1;
        std::string home_dir = "/data/local/tmp";
        std::string root_script_path = "/data/local/tmp/.ghostlock_root.sh";
        /* Per-run KernelSU log (U01-S14): Kotlin passes GHOSTLOCK_KSU_LOG so a
   * previous run's markers can never be misread; falls back to the legacy
   * home-dir name for CLI runs. */
        std::string ksu_log_path = "/data/local/tmp/.ghostlock_ksu.log";
        /* ksud binary from GHOSTLOCK_KSUD, shell-escaped for the root
         * script; empty keeps the script's own search order. */
        std::string ksud_path;
        /* Directory holding the ZygiskNext / Vector component packages, from
         * GHOSTLOCK_LSPD_MODULES, shell-escaped for the root script. Empty
         * means the LSPosed stack is not part of this run. The root script is
         * exec'd without the environment (execl, not execle), so the path has
         * to be embedded in the script itself. */
        std::string lspd_modules_path;
        /* Kernel-log dump directory from --dump-kernel-log; empty disables the dump. */
        std::string debug_dir;
        /* Whether the root script reloads the SELinux policy to restore
         * enforcing. "0" keeps SELinux permissive for this boot.
         *
         * The exploit leaves SELinux permissive; restoring enforcing means
         * reloading the policy, and the reload re-initializes the LSM hooks
         * while the kernel still holds the cred state the exploit wrote. On
         * the Titan 2 that is where the __secure_computing oops came from
         * (2026-10-22). Process-class switch (how this run behaves), read
         * from GHOSTLOCK_SELINUX_FIXUP; default 1 = reload. */
        int32_t selinux_fixup = 1;

        RuntimeConfig() noexcept = default;

        /* Capture the process environment exactly once. */
        int32_t init();

        /* Merge the profile's recommended CPU pair (validated). */
        int32_t apply_profile(const ghostlock::profile::TargetProfile *profile);

        void log() const;
    };

    /* The process snapshot is owned by ExploitSession. Callers reach it through
 * this accessor; the public reference alias is gone (CPP12/SESSION-01). */
    RuntimeConfig &runtime_config_snapshot() noexcept;
} // namespace ghostlock::config

#endif
