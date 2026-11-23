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

void physicalPressesSelectModesOnlyDuringEditing() {
    ButtonModes modes(bit(A) | bit(B));
    modes.physicalFrame(bit(A), true);
    modes.physicalFrame(bit(A), true);
    check(modes.held() == bit(A), "One physical press must select Held exactly once");
    check(modes.output(epoch) == 0, "Editing must release game output");
    modes.physicalFrame(0, true);
    modes.setEditing(false, epoch);
    modes.physicalFrame(bit(B), true);
    check(modes.output(epoch) == (bit(A) | bit(B)), "Held accelerator and ordinary input must coexist");
    modes.physicalFrame(0, true);
    check(modes.output(epoch) == bit(A), "Held must survive physical release");

    modes.setEditing(true, epoch);
    modes.physicalFrame(bit(A), true);
    check(modes.turbo() == bit(A) && !modes.held(), "The second edit press must select Turbo");
    modes.physicalFrame(0, true);
    modes.physicalFrame(bit(A), true);
    check(!modes.turbo() && !modes.held(), "The third edit press must restore Neutral");
}

void turboHasBothEdgesAndIgnoresConstantPhysicalPressure() {
    ButtonModes modes(bit(X) | bit(Y));
    modes.cycle(bit(X));
    modes.cycle(bit(X));
    modes.cycle(bit(Y));
    modes.setRate(20, epoch);
    modes.setEditing(false, epoch);
    modes.physicalFrame(bit(X), true);
    check(modes.output(epoch) == (bit(X) | bit(Y)), "Turbo must start down");
    check(modes.nextEdge(epoch) == epoch + 25ms, "Twenty presses/sec must switch every 25 ms");
    check(modes.output(epoch + 25ms) == bit(Y), "Turbo must release even with X held physically");
    check(modes.output(epoch + 50ms) == (bit(X) | bit(Y)), "Turbo must emit another down edge");
    modes.setEditing(true, epoch + 51ms);
    check(!modes.output(epoch + 51ms), "Returning to edit mode must release held and turbo buttons");
    modes.clear();
    check(!modes.held() && !modes.turbo(), "Reset must clear both modes");
}

void snapshotAndUnfinishedEditsDoNotGeneratePresses() {
    ButtonModes modes(bit(A));
    modes.physicalFrame(bit(A), false);
    modes.physicalFrame(bit(A), true);
    modes.cycle(bit(B));
    check(!modes.held() && !modes.turbo(), "Resync and unsupported controls must not select modes");
    modes.setEditing(false, epoch);
    check(!modes.output(epoch), "Applying while a neutral button is down must wait for its next press");
    modes.physicalFrame(0, true);
    modes.physicalFrame(bit(A), true);
    check(modes.output(epoch) == bit(A), "The next ordinary press must pass through");
    modes.physicalFrame(0, true);
    check(!modes.output(epoch), "An ordinary release must pass through");
}

} // namespace

int main() {
    physicalPressesSelectModesOnlyDuringEditing();
    turboHasBothEdgesAndIgnoresConstantPhysicalPressure();
    snapshotAndUnfinishedEditsDoNotGeneratePresses();
    std::cout << "Button mode checks passed.\n";
}
