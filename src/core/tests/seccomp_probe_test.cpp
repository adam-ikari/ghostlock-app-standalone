/* Host fixed-vector test for the seccomp probe classifier. The direct and
 * forked probes share this predicate; both the KILL (SIGSYS) and RET_ERRNO
 * forms must count as blocked, while a real kernel errno must not. */

#include "session/seccomp_probe.hpp"

#include <cstdint>
#include <cstdio>

int main() {
    using ghostlock::session::victim::seccomp_probe_blocked;

    const std::uint32_t blocked[] = {0xfffffffdU, 0xfffffffeU, 0xffffffffU, 1U, 38U};
    for (const std::uint32_t code : blocked) {
        if (!seccomp_probe_blocked(code)) {
            std::fprintf(stderr, "expected blocked: 0x%x\n", code);
            return 1;
        }
    }

    const std::uint32_t allowed[] = {0U, 9U, 14U, 22U};
    for (const std::uint32_t code : allowed) {
        if (seccomp_probe_blocked(code)) {
            std::fprintf(stderr, "expected allowed: 0x%x\n", code);
            return 1;
        }
    }

    std::puts("seccomp_probe_test: ok");
    return 0;
}
