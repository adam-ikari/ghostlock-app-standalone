#ifndef GHOSTLOCK_SECCOMP_PROBE_HPP
#define GHOSTLOCK_SECCOMP_PROBE_HPP

#include <cstdint>

namespace ghostlock::session::victim {
    /* SIGSYS (0xfffffffd) = the filter killed the probe; EPERM/ENOSYS = the
     * filter's RET_ERRNO actions. With init_cred + permissive SELinux a real
     * probe fails with a normal errno instead. */
    [[nodiscard]] constexpr bool seccomp_probe_blocked(std::uint32_t code) noexcept {
        return code == 0xfffffffdU || code == 0xfffffffeU || code == 0xffffffffU ||
               code == 1U || code == 38U;
    }
} // namespace ghostlock::session::victim

#endif
