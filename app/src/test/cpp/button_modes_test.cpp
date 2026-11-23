#include "button_modes.h"

#include <chrono>
#include <cstdlib>
#include <iostream>

using namespace thumbrest;
using namespace std::chrono_literals;

namespace {

const auto epoch = Clock::time_point{} + 1s;

void check(bool condition, const char* description) {
    if (!condition) {
        std::cerr << description << '\n';
        std::exit(1);
    }
}

void liveTapsFollowTheRaceWorkflow() {
    ButtonModes modes(bit(A) | bit(B));
    modes.physicalFrame(bit(A));
    check(modes.output(epoch) == bit(A) && !modes.held(), "Default must pass through the first hardware press");
    modes.physicalFrame(0);
    check(!modes.output(epoch), "Hardware release must pass through without changing a mode");

    modes.cycle(bit(A), epoch);
    check(modes.output(epoch) == bit(A), "One screen tap must start Held immediately");
    modes.physicalFrame(bit(B));
    check(modes.output(epoch) == (bit(A) | bit(B)), "Steering and other controls must remain live");
    modes.physicalFrame(0);
    check(modes.output(epoch) == bit(A), "Held must survive physical release");
    modes.cycle(bit(A), epoch + 1ms);
    check(modes.turbo() == bit(A) && !modes.held(), "A second tap must select Turbo");
    modes.cycle(bit(A), epoch + 2ms);
    check(!modes.output(epoch + 2ms) && !modes.turbo(), "Two taps from Held must restore Default immediately");
}

void turboHasBothEdgesDespitePhysicalPressure() {
    ButtonModes modes(bit(X) | bit(Y));
    modes.setRate(20, epoch);
    modes.cycle(bit(X), epoch);
    modes.cycle(bit(X), epoch);
    modes.cycle(bit(Y), epoch);
    modes.physicalFrame(bit(X));
    check(modes.output(epoch) == (bit(X) | bit(Y)), "Turbo must start down");
    check(modes.nextEdge(epoch) == epoch + 25ms, "Twenty presses/sec must switch every 25 ms");
    check(modes.output(epoch + 25ms) == bit(Y), "Turbo must release with X held physically");
    check(modes.output(epoch + 50ms) == (bit(X) | bit(Y)), "Turbo must emit another down edge");
    modes.cycle(bit(X), epoch + 75ms);
    check(modes.output(epoch + 75ms) == (bit(X) | bit(Y)), "Returning to Default must preserve a real press");
    modes.physicalFrame(0);
    check(modes.output(epoch + 75ms) == bit(Y), "Default must immediately follow the physical release");
}

void resetResyncAndUnsupportedControlsStayPredictable() {
    ButtonModes modes(bit(A));
    modes.physicalFrame(bit(A) | bit(B));
    modes.cycle(bit(B), epoch);
    check(!modes.held() && !modes.turbo() && modes.output(epoch) == bit(A), "Resync and unsupported input must not select modes");
    modes.cycle(bit(A), epoch);
    modes.clear();
    check(modes.output(epoch) == bit(A), "Reset must preserve current physical input");
    modes.physicalFrame(0);
    check(!modes.output(epoch), "Reset must release synthetic holds");
    check(modes.nextEdge(epoch) == Clock::time_point::max(), "Default must not schedule turbo work");
    modes.cycle(bit(A), epoch);
    modes.cycle(bit(A), epoch);
    modes.setRate(60, epoch);
    const auto edge = modes.nextEdge(epoch);
    modes.setRate(0, epoch);
    modes.setRate(61, epoch);
    check(modes.nextEdge(epoch) == edge, "Invalid rates must not change the live timing");
}

} // namespace

int main() {
    liveTapsFollowTheRaceWorkflow();
    turboHasBothEdgesDespitePhysicalPressure();
    resetResyncAndUnsupportedControlsStayPredictable();
    std::cout << "Button mode checks passed.\n";
}
